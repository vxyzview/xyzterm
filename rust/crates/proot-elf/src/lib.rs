//! Minimal ELF parser used by the Rust Proot loader. Reads only the fields the
//! loader needs: class, machine, entry point, program headers, PT_INTERP and
//! whether the image is position-independent.

use std::collections::BTreeMap;

const ET_DYN: u16 = 3;
const PT_LOAD: u32 = 1;
const PT_DYNAMIC: u32 = 2;
const PT_INTERP: u32 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ElfError {
    TooShort,
    BadMagic,
    UnsupportedClass,
    UnsupportedEncoding,
    BadProgramHeader,
}

impl std::fmt::Display for ElfError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for ElfError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProgramHeader {
    pub kind: u32,
    pub offset: u64,
    pub vaddr: u64,
    pub filesz: u64,
    pub memsz: u64,
    pub flags: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ElfInfo {
    pub class: u8,
    pub machine: u16,
    pub elf_type: u16,
    pub entry: u64,
    pub phoff: u64,
    pub phentsize: u16,
    pub phnum: u16,
    pub program_headers: Vec<ProgramHeader>,
}

impl ElfInfo {
    /// Returns the PT_INTERP byte range in the original file.
    pub fn interp_range(&self) -> Option<(u64, u64)> {
        self.program_headers
            .iter()
            .find(|header| header.kind == PT_INTERP)
            .map(|header| (header.offset, header.filesz))
    }

    pub fn is_pie(&self) -> bool {
        self.elf_type == ET_DYN
    }

    pub fn load_segments(&self) -> Vec<ProgramHeader> {
        self.program_headers
            .iter()
            .filter(|header| header.kind == PT_LOAD)
            .copied()
            .collect()
    }

    pub fn has_dynamic(&self) -> bool {
        self.program_headers.iter().any(|h| h.kind == PT_DYNAMIC)
    }

    /// Highest virtual address covered by a PT_LOAD segment.
    pub fn max_vaddr(&self) -> u64 {
        self.load_segments()
            .iter()
            .map(|h| h.vaddr.saturating_add(h.memsz))
            .max()
            .unwrap_or(0)
    }
}

fn read_u16(bytes: &[u8], offset: usize, little: bool) -> Result<u16, ElfError> {
    let pair: [u8; 2] = bytes
        .get(offset..offset + 2)
        .ok_or(ElfError::TooShort)?
        .try_into()
        .map_err(|_| ElfError::TooShort)?;
    Ok(if little {
        u16::from_le_bytes(pair)
    } else {
        u16::from_be_bytes(pair)
    })
}

fn read_u32(bytes: &[u8], offset: usize, little: bool) -> Result<u32, ElfError> {
    let quad: [u8; 4] = bytes
        .get(offset..offset + 4)
        .ok_or(ElfError::TooShort)?
        .try_into()
        .map_err(|_| ElfError::TooShort)?;
    Ok(if little {
        u32::from_le_bytes(quad)
    } else {
        u32::from_be_bytes(quad)
    })
}

fn read_u64(bytes: &[u8], offset: usize, little: bool) -> Result<u64, ElfError> {
    let octet: [u8; 8] = bytes
        .get(offset..offset + 8)
        .ok_or(ElfError::TooShort)?
        .try_into()
        .map_err(|_| ElfError::TooShort)?;
    Ok(if little {
        u64::from_le_bytes(octet)
    } else {
        u64::from_be_bytes(octet)
    })
}

pub fn parse(bytes: &[u8]) -> Result<ElfInfo, ElfError> {
    if bytes.len() < 16 || !bytes.starts_with(&[0x7f, b'E', b'L', b'F']) {
        return Err(ElfError::BadMagic);
    }
    let class = bytes[4];
    let little = match bytes[5] {
        1 => true,
        2 => false,
        _ => return Err(ElfError::UnsupportedEncoding),
    };
    let (elf_type, machine, entry, phoff, phentsize, phnum) = match class {
        1 => (
            read_u16(bytes, 16, little)?,
            read_u16(bytes, 18, little)?,
            u64::from(read_u32(bytes, 24, little)?),
            u64::from(read_u32(bytes, 28, little)?),
            read_u16(bytes, 42, little)?,
            read_u16(bytes, 44, little)?,
        ),
        2 => (
            read_u16(bytes, 16, little)?,
            read_u16(bytes, 18, little)?,
            read_u64(bytes, 24, little)?,
            read_u64(bytes, 32, little)?,
            read_u16(bytes, 54, little)?,
            read_u16(bytes, 56, little)?,
        ),
        _ => return Err(ElfError::UnsupportedClass),
    };

    let mut program_headers = Vec::with_capacity(phnum as usize);
    for index in 0..phnum as usize {
        let base = phoff as usize + index * phentsize as usize;
        let header = if class == 1 {
            // Elf32_Phdr: type, offset, vaddr, paddr, filesz, memsz, flags, align
            if read_u16(bytes, base, little)? == 0 {
                return Err(ElfError::BadProgramHeader);
            }
            ProgramHeader {
                kind: read_u32(bytes, base, little)?,
                offset: u64::from(read_u32(bytes, base + 4, little)?),
                vaddr: u64::from(read_u32(bytes, base + 8, little)?),
                filesz: u64::from(read_u32(bytes, base + 16, little)?),
                memsz: u64::from(read_u32(bytes, base + 20, little)?),
                flags: read_u32(bytes, base + 24, little)?,
            }
        } else {
            // Elf64_Phdr: type, flags, offset, vaddr, paddr, filesz, memsz, align
            let kind = read_u32(bytes, base, little)?;
            if kind == 0 {
                return Err(ElfError::BadProgramHeader);
            }
            ProgramHeader {
                kind,
                flags: read_u32(bytes, base + 4, little)?,
                offset: read_u64(bytes, base + 8, little)?,
                vaddr: read_u64(bytes, base + 16, little)?,
                filesz: read_u64(bytes, base + 32, little)?,
                memsz: read_u64(bytes, base + 40, little)?,
            }
        };
        program_headers.push(header);
    }

    Ok(ElfInfo {
        class,
        machine,
        elf_type,
        entry,
        phoff,
        phentsize,
        phnum,
        program_headers,
    })
}

/// Returns the PT_INTERP string from the original file bytes.
pub fn interpreter_string(bytes: &[u8], info: &ElfInfo) -> Option<String> {
    let (offset, length) = info.interp_range()?;
    let start = usize::try_from(offset).ok()?;
    let end = start.checked_add(usize::try_from(length).ok()?)?;
    let raw = bytes.get(start..end)?;
    let trimmed = raw.split(|b| *b == 0).next()?;
    String::from_utf8(trimmed.to_vec()).ok()
}

/// Groups load segments by page-aligned virtual address, which is what the
/// loader maps when starting a tracee.
pub fn load_groups(info: &ElfInfo, page_size: u64) -> BTreeMap<u64, u64> {
    let mut groups: BTreeMap<u64, u64> = BTreeMap::new();
    for segment in info.load_segments() {
        let start = segment.vaddr / page_size * page_size;
        let end = (segment.vaddr + segment.memsz).div_ceil(page_size) * page_size;
        groups
            .entry(start)
            .and_modify(|v| *v = (*v).max(end))
            .or_insert(end);
    }
    groups
}

#[cfg(test)]
mod tests {
    use super::*;

    fn elf64(machine: u16, elf_type: u16, entry: u64, headers: &[ProgramHeader]) -> Vec<u8> {
        let mut bytes = vec![0u8; 64 + 56 * headers.len() + 11];
        bytes[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        bytes[4] = 2; // ELF64
        bytes[5] = 1; // little endian
        bytes[6] = 1; // version
        bytes[16..18].copy_from_slice(&elf_type.to_le_bytes());
        bytes[18..20].copy_from_slice(&machine.to_le_bytes());
        bytes[24..32].copy_from_slice(&entry.to_le_bytes());
        bytes[32..40].copy_from_slice(&64u64.to_le_bytes()); // phoff
        bytes[54..56].copy_from_slice(&56u16.to_le_bytes()); // phentsize
        bytes[56..58].copy_from_slice(&(headers.len() as u16).to_le_bytes());
        for (index, header) in headers.iter().enumerate() {
            let base = 64 + index * 56;
            bytes[base..base + 4].copy_from_slice(&header.kind.to_le_bytes());
            bytes[base + 4..base + 8].copy_from_slice(&header.flags.to_le_bytes());
            bytes[base + 8..base + 16].copy_from_slice(&header.offset.to_le_bytes());
            bytes[base + 16..base + 24].copy_from_slice(&header.vaddr.to_le_bytes());
            bytes[base + 32..base + 40].copy_from_slice(&header.filesz.to_le_bytes());
            bytes[base + 40..base + 48].copy_from_slice(&header.memsz.to_le_bytes());
        }
        bytes[64 + 56 * headers.len()..][..11].copy_from_slice(b"/lib/ld.so\0");
        bytes
    }

    #[test]
    fn parses_elf64_aarch64_with_interpreter() {
        let headers = [
            ProgramHeader {
                kind: PT_INTERP,
                offset: 64 + 56 * 2,
                vaddr: 0,
                filesz: 11,
                memsz: 11,
                flags: 4,
            },
            ProgramHeader {
                kind: PT_LOAD,
                offset: 0,
                vaddr: 0x400000,
                filesz: 0x1000,
                memsz: 0x2000,
                flags: 5,
            },
        ];
        let bytes = elf64(183, ET_DYN, 0x401000, &headers);
        let info = parse(&bytes).expect("parse");
        assert_eq!(info.machine, 183);
        assert_eq!(info.entry, 0x401000);
        assert!(info.is_pie());
        assert_eq!(
            interpreter_string(&bytes, &info).as_deref(),
            Some("/lib/ld.so")
        );
        assert_eq!(info.max_vaddr(), 0x402000);
        assert_eq!(load_groups(&info, 0x1000).len(), 1);
    }

    #[test]
    fn parses_elf32_arm() {
        let mut bytes = vec![0u8; 52 + 32];
        bytes[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        bytes[4] = 1;
        bytes[5] = 1;
        bytes[16..18].copy_from_slice(&2u16.to_le_bytes()); // ET_EXEC
        bytes[18..20].copy_from_slice(&40u16.to_le_bytes()); // EM_ARM
        bytes[24..28].copy_from_slice(&0x8000u32.to_le_bytes());
        bytes[28..32].copy_from_slice(&52u32.to_le_bytes());
        bytes[42..44].copy_from_slice(&32u16.to_le_bytes());
        bytes[44..46].copy_from_slice(&1u16.to_le_bytes());
        let base = 52;
        bytes[base..base + 4].copy_from_slice(&PT_LOAD.to_le_bytes());
        bytes[base + 4..base + 8].copy_from_slice(&0u32.to_le_bytes());
        bytes[base + 8..base + 12].copy_from_slice(&0x8000u32.to_le_bytes());
        bytes[base + 16..base + 20].copy_from_slice(&32u32.to_le_bytes());
        bytes[base + 20..base + 24].copy_from_slice(&32u32.to_le_bytes());
        let info = parse(&bytes).expect("parse");
        assert_eq!(info.class, 1);
        assert_eq!(info.entry, 0x8000);
        assert!(!info.is_pie());
        assert_eq!(info.load_segments().len(), 1);
    }

    #[test]
    fn rejects_bad_inputs() {
        assert_eq!(parse(b"not elf"), Err(ElfError::BadMagic));
        let mut bytes = vec![0u8; 64];
        bytes[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        bytes[4] = 9;
        bytes[5] = 1;
        assert_eq!(parse(&bytes), Err(ElfError::UnsupportedClass));
    }
}
