//! Rootfs installer for the archm packages produced by DownloadRootfsTask.
//! The build still downloads and SHA-256-verifies the tarball; this crate only
//! handles the packaged zip + TSV manifest on the device.

use std::fs;
use std::io::{Read, Write};
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestEntry {
    pub kind: char,
    pub path: String,
    pub mode: u32,
    pub target: Option<String>,
    pub size: u64,
}

#[derive(Debug, Clone, Default)]
pub struct Manifest {
    pub entries: Vec<ManifestEntry>,
    pub total_bytes: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RootfsError {
    BadManifest,
    UnsafePath,
    BadArchive,
    UnsupportedCompression,
    IoFailed,
}

impl std::fmt::Display for RootfsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for RootfsError {}

/// Rejects absolute paths, parent traversal and embedded NULs, matching the
/// installer's safety rule without following symlinks.
pub fn is_safe_entry_path(path: &str) -> bool {
    if path.is_empty() || path.contains('\0') {
        return false;
    }
    let candidate = Path::new(path);
    if candidate.is_absolute() {
        return false;
    }
    candidate
        .components()
        .all(|component| matches!(component, Component::Normal(_) | Component::CurDir))
}

/// Parses the build-time TSV: first line is `B\t<total bytes>`.
pub fn parse_manifest(text: &str) -> Result<Manifest, RootfsError> {
    let mut lines = text.lines();
    let header = lines.next().ok_or(RootfsError::BadManifest)?;
    let total_bytes = header
        .strip_prefix("B\t")
        .and_then(|value| value.trim().parse().ok())
        .ok_or(RootfsError::BadManifest)?;
    let mut manifest = Manifest {
        total_bytes,
        entries: Vec::new(),
    };
    for line in lines {
        let fields: Vec<&str> = line.split('\t').collect();
        let kind = fields
            .first()
            .and_then(|f| f.chars().next())
            .ok_or(RootfsError::BadManifest)?;
        let path = fields.get(1).copied().ok_or(RootfsError::BadManifest)?;
        if !is_safe_entry_path(path) {
            return Err(RootfsError::UnsafePath);
        }
        let mode = fields
            .get(2)
            .and_then(|value| value.parse().ok())
            .ok_or(RootfsError::BadManifest)?;
        let (target, size) = match kind {
            'D' => (None, 0),
            'L' => (
                Some(fields.get(3).ok_or(RootfsError::BadManifest)?.to_string()),
                0,
            ),
            'F' => (
                None,
                fields
                    .get(3)
                    .and_then(|value| value.parse().ok())
                    .ok_or(RootfsError::BadManifest)?,
            ),
            _ => return Err(RootfsError::BadManifest),
        };
        manifest.entries.push(ManifestEntry {
            kind,
            path: path.to_string(),
            mode,
            target,
            size,
        });
    }
    Ok(manifest)
}

fn u16le(bytes: &[u8]) -> Option<u16> {
    Some(u16::from_le_bytes(bytes.get(..2)?.try_into().ok()?))
}

fn u32le(bytes: &[u8]) -> Option<u32> {
    Some(u32::from_le_bytes(bytes.get(..4)?.try_into().ok()?))
}

fn find_eocd(data: &[u8]) -> Option<usize> {
    let start = data.len().saturating_sub(65_557);
    (start..data.len().saturating_sub(21))
        .rev()
        .find(|&i| data[i..].starts_with(&[0x50, 0x4b, 0x05, 0x06]))
}

/// Reads one file from a zip archive by its central-directory entry.
fn read_zip_entry(data: &[u8], name: &str) -> Result<Vec<u8>, RootfsError> {
    let eocd = find_eocd(data).ok_or(RootfsError::BadArchive)?;
    let count = u16le(&data[eocd + 10..]).ok_or(RootfsError::BadArchive)? as usize;
    let mut offset = u32le(&data[eocd + 16..]).ok_or(RootfsError::BadArchive)? as usize;
    for _ in 0..count {
        if !data[offset..].starts_with(&[0x50, 0x4b, 0x01, 0x02]) {
            return Err(RootfsError::BadArchive);
        }
        let method = u16le(&data[offset + 10..]).ok_or(RootfsError::BadArchive)?;
        let compressed_size = u32le(&data[offset + 20..]).ok_or(RootfsError::BadArchive)? as usize;
        let name_len = u16le(&data[offset + 28..]).ok_or(RootfsError::BadArchive)? as usize;
        let extra_len = u16le(&data[offset + 30..]).ok_or(RootfsError::BadArchive)? as usize;
        let comment_len = u16le(&data[offset + 32..]).ok_or(RootfsError::BadArchive)? as usize;
        let local_offset = u32le(&data[offset + 42..]).ok_or(RootfsError::BadArchive)? as usize;
        let entry_name = data
            .get(offset + 46..offset + 46 + name_len)
            .ok_or(RootfsError::BadArchive)?;
        if entry_name == name.as_bytes() {
            if !data[local_offset..].starts_with(&[0x50, 0x4b, 0x03, 0x04]) {
                return Err(RootfsError::BadArchive);
            }
            let local_name =
                u16le(&data[local_offset + 26..]).ok_or(RootfsError::BadArchive)? as usize;
            let local_extra =
                u16le(&data[local_offset + 28..]).ok_or(RootfsError::BadArchive)? as usize;
            let start = local_offset + 30 + local_name + local_extra;
            let compressed = data
                .get(start..start + compressed_size)
                .ok_or(RootfsError::BadArchive)?;
            return match method {
                0 => Ok(compressed.to_vec()),
                8 => {
                    let mut decoder = flate2::read::DeflateDecoder::new(compressed);
                    let mut output = Vec::new();
                    decoder
                        .read_to_end(&mut output)
                        .map_err(|_| RootfsError::BadArchive)?;
                    Ok(output)
                }
                _ => Err(RootfsError::UnsupportedCompression),
            };
        }
        offset += 46 + name_len + extra_len + comment_len;
    }
    Err(RootfsError::BadArchive)
}

/// Extracts the packaged tree under `root`, creating directories, symlinks and
/// file modes from the manifest. Existing complete installs are left alone.
pub fn install(zip_path: &Path, manifest: &Manifest, root: &Path) -> Result<usize, RootfsError> {
    let archive = fs::read(zip_path).map_err(|_| RootfsError::IoFailed)?;
    fs::create_dir_all(root).map_err(|_| RootfsError::IoFailed)?;
    for entry in &manifest.entries {
        let destination = root.join(&entry.path);
        match entry.kind {
            'D' => fs::create_dir_all(&destination).map_err(|_| RootfsError::IoFailed)?,
            'L' => {
                if let Some(parent) = destination.parent() {
                    fs::create_dir_all(parent).map_err(|_| RootfsError::IoFailed)?;
                }
                let target = entry.target.as_deref().ok_or(RootfsError::BadManifest)?;
                if !is_safe_entry_path(target) && !target.starts_with('/') {
                    return Err(RootfsError::UnsafePath);
                }
                if fs::symlink_metadata(&destination).is_err() {
                    symlink(target, &destination).map_err(|_| RootfsError::IoFailed)?;
                }
            }
            'F' => {
                if let Some(parent) = destination.parent() {
                    fs::create_dir_all(parent).map_err(|_| RootfsError::IoFailed)?;
                }
                let bytes = read_zip_entry(&archive, &entry.path)?;
                let mut file = fs::File::create(&destination).map_err(|_| RootfsError::IoFailed)?;
                file.write_all(&bytes).map_err(|_| RootfsError::IoFailed)?;
                fs::set_permissions(&destination, fs::Permissions::from_mode(entry.mode))
                    .map_err(|_| RootfsError::IoFailed)?;
            }
            _ => return Err(RootfsError::BadManifest),
        }
    }
    Ok(manifest.entries.len())
}

/// Verifies that every manifest file exists with the recorded size.
pub fn is_install_complete(root: &Path, manifest: &Manifest) -> bool {
    manifest.entries.iter().all(|entry| {
        let path: PathBuf = root.join(&entry.path);
        match entry.kind {
            'F' => fs::metadata(path)
                .map(|m| m.len() == entry.size)
                .unwrap_or(false),
            'L' => fs::symlink_metadata(path).is_ok(),
            'D' => fs::metadata(path).map(|m| m.is_dir()).unwrap_or(false),
            _ => false,
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stored_zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut offsets = Vec::new();
        for (name, content) in entries {
            offsets.push(out.len());
            out.extend_from_slice(&0x04034b50u32.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes()); // version
            out.extend_from_slice(&0u16.to_le_bytes()); // flags
            out.extend_from_slice(&0u16.to_le_bytes()); // stored
            out.extend_from_slice(&0u16.to_le_bytes()); // time
            out.extend_from_slice(&0u16.to_le_bytes()); // date
            out.extend_from_slice(&0u32.to_le_bytes()); // crc
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(name.len() as u16).to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(name.as_bytes());
            out.extend_from_slice(content);
        }
        let central = out.len();
        for ((name, content), offset) in entries.iter().zip(offsets) {
            out.extend_from_slice(&0x02014b50u32.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&20u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes()); // flags
            out.extend_from_slice(&0u16.to_le_bytes()); // method
            out.extend_from_slice(&0u16.to_le_bytes()); // time
            out.extend_from_slice(&0u16.to_le_bytes()); // date
            out.extend_from_slice(&0u32.to_le_bytes());
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(content.len() as u32).to_le_bytes());
            out.extend_from_slice(&(name.len() as u16).to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u16.to_le_bytes());
            out.extend_from_slice(&0u32.to_le_bytes());
            out.extend_from_slice(&(offset as u32).to_le_bytes());
            out.extend_from_slice(name.as_bytes());
        }
        let central_size = out.len() - central;
        out.extend_from_slice(&0x06054b50u32.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
        out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
        out.extend_from_slice(&(central_size as u32).to_le_bytes());
        out.extend_from_slice(&(central as u32).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out
    }

    #[test]
    fn parses_and_rejects_manifest_paths() {
        let text = "B\t5\nD\tusr\t493\nF\tusr/a\t420\t5\nL\tusr/l\t777\t../a\n";
        let manifest = parse_manifest(text).expect("manifest");
        assert_eq!(manifest.total_bytes, 5);
        assert_eq!(manifest.entries.len(), 3);
        assert_eq!(manifest.entries[1].size, 5);
        assert_eq!(manifest.entries[2].target.as_deref(), Some("../a"));
        assert!(parse_manifest("B\t1\nF\t../evil\t420\t1\n").is_err());
        assert!(parse_manifest("B\t1\nF\t/etc/passwd\t420\t1\n").is_err());
    }

    #[test]
    fn deflate_roundtrip_is_readable() {
        let mut encoder =
            flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(b"compressed").expect("deflate");
        let compressed = encoder.finish().expect("finish");
        let mut zip = Vec::new();
        zip.extend_from_slice(&0x04034b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&8u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
        zip.extend_from_slice(&10u32.to_le_bytes());
        zip.extend_from_slice(&4u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(b"data");
        zip.extend_from_slice(&compressed);
        let central = zip.len();
        zip.extend_from_slice(&0x02014b50u32.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&20u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes()); // flags
        zip.extend_from_slice(&8u16.to_le_bytes()); // method
        zip.extend_from_slice(&0u16.to_le_bytes()); // time
        zip.extend_from_slice(&0u16.to_le_bytes()); // date
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
        zip.extend_from_slice(&10u32.to_le_bytes());
        zip.extend_from_slice(&4u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(&0u32.to_le_bytes());
        zip.extend_from_slice(b"data");
        let central_size = zip.len() - central;
        zip.extend_from_slice(&0x06054b50u32.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&1u16.to_le_bytes());
        zip.extend_from_slice(&(central_size as u32).to_le_bytes());
        zip.extend_from_slice(&(central as u32).to_le_bytes());
        zip.extend_from_slice(&0u16.to_le_bytes());
        assert_eq!(read_zip_entry(&zip, "data").expect("entry"), b"compressed");
    }

    #[test]
    fn installs_and_verifies_a_tree() {
        let dir = std::env::temp_dir().join(format!("xyzterm-rootfs-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let zip = dir.with_extension("zip");
        let content = b"hello";
        fs::write(&zip, stored_zip(&[("usr/a", content)])).expect("zip fixture");
        let manifest = parse_manifest(&format!(
            "B\t{}\nD\tusr\t493\nF\tusr/a\t420\t{}\nL\tusr/l\t777\ta\n",
            content.len(),
            content.len()
        ))
        .expect("manifest");
        let root = dir.join("root");
        assert_eq!(install(&zip, &manifest, &root).expect("install"), 3);
        assert_eq!(fs::read(root.join("usr/a")).expect("file"), content);
        assert_eq!(
            fs::read_link(root.join("usr/l")).expect("link"),
            Path::new("a")
        );
        assert!(is_install_complete(&root, &manifest));
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_file(&zip);
    }
}
