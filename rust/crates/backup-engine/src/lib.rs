//! Backup and restoration engine. Archives are gzip-compressed tar streams
//! encrypted with AES-256-GCM; the key is PBKDF2-HMAC-SHA256 of a passphrase.

use std::fs::{self, File};
use std::io::{Cursor, Read};
use std::path::Path;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use pbkdf2::pbkdf2_hmac;
use sha2::Sha256;

const MAGIC: &[u8; 7] = b"XYZBAK1";
const SALT_LEN: usize = 16;
const NONCE_LEN: usize = 12;
const PBKDF2_ROUNDS: u32 = 100_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupError {
    BadArchive,
    WrongPassphrase,
    IoFailed,
}

impl std::fmt::Display for BackupError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for BackupError {}

fn random_bytes(count: usize) -> Result<Vec<u8>, BackupError> {
    let mut file = File::open("/dev/urandom").map_err(|_| BackupError::IoFailed)?;
    let mut bytes = vec![0u8; count];
    file.read_exact(&mut bytes)
        .map_err(|_| BackupError::IoFailed)?;
    Ok(bytes)
}

fn derive_key(passphrase: &[u8], salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(passphrase, salt, PBKDF2_ROUNDS, &mut key);
    key
}

fn compress(source: &Path) -> Result<Vec<u8>, BackupError> {
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    {
        let mut builder = tar::Builder::new(&mut encoder);
        builder.follow_symlinks(false);
        builder
            .append_dir_all("", source)
            .map_err(|_| BackupError::IoFailed)?;
        builder.finish().map_err(|_| BackupError::IoFailed)?;
    }
    encoder.finish().map_err(|_| BackupError::IoFailed)
}

/// Creates an encrypted backup of `source` at `destination`.
pub fn create_backup(
    source: &Path,
    destination: &Path,
    passphrase: &[u8],
) -> Result<(), BackupError> {
    let salt = random_bytes(SALT_LEN)?;
    let nonce = random_bytes(NONCE_LEN)?;
    let cipher = Aes256Gcm::new_from_slice(&derive_key(passphrase, &salt))
        .map_err(|_| BackupError::BadArchive)?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &compress(source)?,
                aad: MAGIC,
            },
        )
        .map_err(|_| BackupError::BadArchive)?;
    let mut output = Vec::with_capacity(MAGIC.len() + salt.len() + nonce.len() + ciphertext.len());
    output.extend_from_slice(MAGIC);
    output.extend_from_slice(&salt);
    output.extend_from_slice(&nonce);
    output.extend_from_slice(&ciphertext);
    fs::write(destination, output).map_err(|_| BackupError::IoFailed)
}

/// Restores an encrypted backup into `destination`.
pub fn restore_backup(
    archive: &Path,
    destination: &Path,
    passphrase: &[u8],
) -> Result<(), BackupError> {
    let input = fs::read(archive).map_err(|_| BackupError::IoFailed)?;
    let header = MAGIC.len() + SALT_LEN + NONCE_LEN;
    if input.len() < header || !input.starts_with(MAGIC) {
        return Err(BackupError::BadArchive);
    }
    let salt = &input[MAGIC.len()..MAGIC.len() + SALT_LEN];
    let nonce = &input[MAGIC.len() + SALT_LEN..header];
    let cipher = Aes256Gcm::new_from_slice(&derive_key(passphrase, salt))
        .map_err(|_| BackupError::BadArchive)?;
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: &input[header..],
                aad: MAGIC,
            },
        )
        .map_err(|_| BackupError::WrongPassphrase)?;
    let mut archive = tar::Archive::new(flate2::read::GzDecoder::new(Cursor::new(plaintext)));
    archive
        .unpack(destination)
        .map_err(|_| BackupError::IoFailed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    fn fixture(name: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("xyzterm-backup-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("home")).expect("fixture dir");
        fs::write(dir.join("home/note.txt"), b"hello xyzterm").expect("fixture file");
        symlink("note.txt", dir.join("home/link")).expect("fixture symlink");
        dir
    }

    #[test]
    fn backup_and_restore_roundtrip() {
        let dir = fixture("roundtrip");
        let archive = dir.with_extension("bak");
        create_backup(&dir.join("home"), &archive, b"secret").expect("backup");
        let restored = dir.join("restored");
        restore_backup(&archive, &restored, b"secret").expect("restore");
        assert_eq!(
            fs::read(restored.join("note.txt")).expect("restored file"),
            b"hello xyzterm"
        );
        assert_eq!(
            fs::read_link(restored.join("link")).expect("restored symlink"),
            Path::new("note.txt")
        );
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_file(&archive);
    }

    #[test]
    fn wrong_passphrase_is_rejected() {
        let dir = fixture("wrongpass");
        let archive = dir.with_extension("bak");
        create_backup(&dir.join("home"), &archive, b"right").expect("backup");
        assert_eq!(
            restore_backup(&archive, &dir.join("out"), b"wrong"),
            Err(BackupError::WrongPassphrase)
        );
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_file(&archive);
    }

    #[test]
    fn corrupted_archive_is_rejected() {
        let dir = fixture("corrupt");
        let bad = dir.join("bad.bak");
        fs::write(&bad, b"NOTXYZTERM").expect("bad archive");
        assert_eq!(
            restore_backup(&bad, &dir.join("out"), b"pw"),
            Err(BackupError::BadArchive)
        );
        let _ = fs::remove_dir_all(&dir);
    }
}
