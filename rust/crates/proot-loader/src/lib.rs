//! Loader data structures for starting a traced guest process. The stack
//! layout follows the Linux 64-bit ABI: argc, argv, envp, auxv, then strings.

pub const AT_NULL: u64 = 0;
pub const AT_ENTRY: u64 = 9;
pub const AT_PAGESZ: u64 = 6;

fn push_u64(out: &mut Vec<u8>, value: u64) {
    out.extend_from_slice(&value.to_le_bytes());
}

/// Builds the initial guest stack image for `base` as the stack pointer.
pub fn build_stack(base: u64, argv: &[&str], envp: &[&str], auxv: &[(u64, u64)]) -> Vec<u8> {
    let mut out = Vec::new();

    // argc
    push_u64(&mut out, argv.len() as u64);

    // Reserve pointer slots: argv, NULL, envp, NULL, auxv pairs, AT_NULL pair.
    let argv_offset = out.len();
    for _ in 0..argv.len() {
        push_u64(&mut out, 0);
    }
    push_u64(&mut out, 0);
    let envp_offset = out.len();
    for _ in 0..envp.len() {
        push_u64(&mut out, 0);
    }
    push_u64(&mut out, 0);
    let auxv_offset = out.len();
    for _ in 0..auxv.len() {
        push_u64(&mut out, 0);
        push_u64(&mut out, 0);
    }
    push_u64(&mut out, AT_NULL);
    push_u64(&mut out, 0);

    // Strings at the end, NUL terminated; pointers are absolute guest addresses.
    for (index, value) in argv.iter().enumerate() {
        let address = base + out.len() as u64;
        out.extend_from_slice(value.as_bytes());
        out.push(0);
        out[argv_offset + index * 8..argv_offset + index * 8 + 8]
            .copy_from_slice(&address.to_le_bytes());
    }
    for (index, value) in envp.iter().enumerate() {
        let address = base + out.len() as u64;
        out.extend_from_slice(value.as_bytes());
        out.push(0);
        out[envp_offset + index * 8..envp_offset + index * 8 + 8]
            .copy_from_slice(&address.to_le_bytes());
    }
    for (index, (tag, value)) in auxv.iter().enumerate() {
        let at = auxv_offset + index * 16;
        out[at..at + 8].copy_from_slice(&tag.to_le_bytes());
        out[at + 8..at + 16].copy_from_slice(&value.to_le_bytes());
    }
    out
}

/// Flattens PT_LOAD segments into `(vaddr, bytes)` pairs ready for injection
/// into a tracee's address space at the guest base address.
pub fn load_segments(image: &[u8], base: u64) -> Vec<(u64, Vec<u8>)> {
    let info = match proot_elf::parse(image) {
        Ok(info) => info,
        Err(_) => return Vec::new(),
    };
    info.load_segments()
        .iter()
        .filter_map(|segment| {
            let start = usize::try_from(segment.offset).ok()?;
            let length = usize::try_from(segment.filesz).ok()?;
            let bytes = image.get(start..start + length)?.to_vec();
            Some((base + segment.vaddr, bytes))
        })
        .collect()
}

/// AArch64 trampoline bytes for `exit` after the loader has finished:
/// `mov x0, #0; mov x8, #93 (exit); svc #0`. Kept as data in `.rs`, no `.S`.
pub const AARCH64_EXIT_TRAMPOLINE: &[u8] = &[
    0x00, 0x00, 0x80, 0xd2, // mov x0, #0
    0x5d, 0x0b, 0x80, 0xd2, // mov x8, #93
    0x01, 0x00, 0x00, 0xd4, // svc #0
];

#[cfg(test)]
mod tests {
    use super::*;

    fn read_u64(bytes: &[u8], offset: usize) -> u64 {
        u64::from_le_bytes(bytes[offset..offset + 8].try_into().expect("aligned u64"))
    }

    #[test]
    fn stack_layout_has_argc_argv_envp_and_auxv() {
        let base = 0x7fff_0000;
        let stack = build_stack(
            base,
            &["sh", "-c", "echo hi"],
            &["HOME=/root"],
            &[(AT_PAGESZ, 4096), (AT_ENTRY, 0x400000)],
        );
        assert_eq!(read_u64(&stack, 0), 3); // argc
        assert_eq!(read_u64(&stack, 8 * 4), 0); // argv NULL
        assert_eq!(read_u64(&stack, 8 * 6), 0); // envp NULL

        let first_arg = read_u64(&stack, 8) - base;
        assert_eq!(&stack[first_arg as usize..first_arg as usize + 3], b"sh\0");
        let env = read_u64(&stack, 8 * 5) - base;
        assert_eq!(&stack[env as usize..env as usize + 10], b"HOME=/root");

        let auxv_start = 8 * 7;
        assert_eq!(read_u64(&stack, auxv_start), AT_PAGESZ);
        assert_eq!(read_u64(&stack, auxv_start + 8), 4096);
        assert_eq!(read_u64(&stack, auxv_start + 16), AT_ENTRY);
        assert_eq!(read_u64(&stack, auxv_start + 24), 0x400000);
        assert_eq!(read_u64(&stack, auxv_start + 32), AT_NULL);
    }

    #[test]
    fn flattens_load_segments_for_injection() {
        let mut image = vec![0u8; 64 + 56 + 16];
        image[..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        image[4] = 2;
        image[5] = 1;
        image[16..18].copy_from_slice(&2u16.to_le_bytes());
        image[18..20].copy_from_slice(&183u16.to_le_bytes());
        image[32..40].copy_from_slice(&64u64.to_le_bytes());
        image[54..56].copy_from_slice(&56u16.to_le_bytes());
        image[56..58].copy_from_slice(&1u16.to_le_bytes());
        let header = 64;
        image[header..header + 4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
        image[header + 8..header + 16].copy_from_slice(&(120u64).to_le_bytes());
        image[header + 16..header + 24].copy_from_slice(&0x1000u64.to_le_bytes());
        image[header + 32..header + 40].copy_from_slice(&16u64.to_le_bytes());
        image[header + 40..header + 48].copy_from_slice(&16u64.to_le_bytes());
        image[120..136].copy_from_slice(b"loader-payload-1");

        let segments = load_segments(&image, 0x400000);
        assert_eq!(segments.len(), 1);
        assert_eq!(segments[0].0, 0x401000);
        assert_eq!(segments[0].1, b"loader-payload-1");
    }

    #[test]
    fn aarch64_trampoline_is_three_instructions() {
        assert_eq!(AARCH64_EXIT_TRAMPOLINE.len(), 12);
        assert_eq!(&AARCH64_EXIT_TRAMPOLINE[8..12], &[0x01, 0x00, 0x00, 0xd4]);
    }
}
