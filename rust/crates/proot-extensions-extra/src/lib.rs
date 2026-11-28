//! C ABI for `extension/extensions_extra_harness/`: drop-in replacements with the original names and signatures, generated
//! by rust-anything from `rust-anything.json`. All of the crate's `unsafe` code lives in this file.

pub mod port;

mod __rust_anything_c_abi {

    /// A `const` byte pointer and its length as a slice; empty for `NULL` or a zero length.
    pub(super) fn bytes<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
        if ptr.is_null() || len == 0 {
            return &[];
        }

        // SAFETY: the C API passes `len` readable bytes at `ptr` that stay valid for the call;
        // NULL is handled above.
        unsafe { std::slice::from_raw_parts(ptr, len) }
    }

    /// A writable byte pointer and its size as a slice; empty for `NULL` or a zero size.
    pub(super) fn bytes_mut<'a>(ptr: *mut u8, len: usize) -> &'a mut [u8] {
        if ptr.is_null() || len == 0 {
            return &mut [];
        }

        // SAFETY: the C API passes `len` writable bytes at `ptr`, used by nothing else during the
        // call; NULL is handled above.
        unsafe { std::slice::from_raw_parts_mut(ptr, len) }
    }

    /// A C length as `usize`; a negative length is zero.
    pub(super) fn len<T: TryInto<usize>>(n: T) -> usize {
        n.try_into().unwrap_or(0)
    }
}

/// `long long ra_ashmem_create(long long supported, long long flags, long long from_sigsys, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_ashmem.c:27`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_ashmem_create(
    supported: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    from_sigsys: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_create(
        supported,
        flags,
        from_sigsys,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_ashmem_create` as `rust_anything_ra_ashmem_create`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_ashmem_create(
    supported: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    from_sigsys: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_create(
        supported,
        flags,
        from_sigsys,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_ashmem_syscall(long long sysnum, long long is_ashmem, long long word_at_arg2, const unsigned char *regs_bytes, unsigned int regs_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_ashmem.c:50`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_ashmem_syscall(
    sysnum: std::ffi::c_longlong,
    is_ashmem: std::ffi::c_longlong,
    word_at_arg2: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_syscall(
        sysnum,
        is_ashmem,
        word_at_arg2,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_ashmem_syscall` as `rust_anything_ra_ashmem_syscall`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_ashmem_syscall(
    sysnum: std::ffi::c_longlong,
    is_ashmem: std::ffi::c_longlong,
    word_at_arg2: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_syscall(
        sysnum,
        is_ashmem,
        word_at_arg2,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_ashmem_stat_exit(long long result, long long word_at_addr, long long chain_state, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_ashmem.c:75`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_ashmem_stat_exit(
    result: std::ffi::c_longlong,
    word_at_addr: std::ffi::c_longlong,
    chain_state: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_stat_exit(
        result,
        word_at_addr,
        chain_state,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_ashmem_stat_exit` as `rust_anything_ra_ashmem_stat_exit`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_ashmem_stat_exit(
    result: std::ffi::c_longlong,
    word_at_addr: std::ffi::c_longlong,
    chain_state: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_stat_exit(
        result,
        word_at_addr,
        chain_state,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_ashmem_chained_exit(long long chain_state, long long result, long long addr, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_ashmem.c:95`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_ashmem_chained_exit(
    chain_state: std::ffi::c_longlong,
    result: std::ffi::c_longlong,
    addr: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_chained_exit(
        chain_state,
        result,
        addr,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_ashmem_chained_exit` as `rust_anything_ra_ashmem_chained_exit`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_ashmem_chained_exit(
    chain_state: std::ffi::c_longlong,
    result: std::ffi::c_longlong,
    addr: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_ashmem_chained_exit(
        chain_state,
        result,
        addr,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_extension_layout(unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_core.c:41`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_extension_layout(
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_layout(crate::__rust_anything_c_abi::bytes_mut(
        out.cast::<u8>(),
        crate::__rust_anything_c_abi::len(out_len),
    ))
}

/// `ra_extension_layout` as `rust_anything_ra_extension_layout`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_extension_layout(
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_layout(crate::__rust_anything_c_abi::bytes_mut(
        out.cast::<u8>(),
        crate::__rust_anything_c_abi::len(out_len),
    ))
}

/// `long long ra_extension_notify(const unsigned char *statuses, unsigned int statuses_len, long long event, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_core.c:59`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_extension_notify(
    statuses: *const std::ffi::c_uchar,
    statuses_len: std::ffi::c_uint,
    event: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_notify(
        crate::__rust_anything_c_abi::bytes(
            statuses.cast::<u8>(),
            crate::__rust_anything_c_abi::len(statuses_len),
        ),
        event,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_extension_notify` as `rust_anything_ra_extension_notify`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_extension_notify(
    statuses: *const std::ffi::c_uchar,
    statuses_len: std::ffi::c_uint,
    event: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_notify(
        crate::__rust_anything_c_abi::bytes(
            statuses.cast::<u8>(),
            crate::__rust_anything_c_abi::len(statuses_len),
        ),
        event,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_extension_initialize(long long init_status, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_core.c:92`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_extension_initialize(
    init_status: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_initialize(
        init_status,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_extension_initialize` as `rust_anything_ra_extension_initialize`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_extension_initialize(
    init_status: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_extension_initialize(
        init_status,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_kompat_needs(long long actual, long long virtual, long long expected)` (`extension/extensions_extra_harness/ext_kompat.c:27`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_kompat_needs(
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    expected: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_kompat_needs(actual, r#virtual, expected)
}

/// `ra_kompat_needs` as `rust_anything_ra_kompat_needs`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_kompat_needs(
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    expected: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_kompat_needs(actual, r#virtual, expected)
}

/// `long long ra_kompat_parse_release(const unsigned char *text, unsigned int text_len)` (`extension/extensions_extra_harness/ext_kompat.c:36`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_kompat_parse_release(
    text: *const std::ffi::c_uchar,
    text_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_parse_release(crate::__rust_anything_c_abi::bytes(
        text.cast::<u8>(),
        crate::__rust_anything_c_abi::len(text_len),
    ))
}

/// `ra_kompat_parse_release` as `rust_anything_ra_kompat_parse_release`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_kompat_parse_release(
    text: *const std::ffi::c_uchar,
    text_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_parse_release(crate::__rust_anything_c_abi::bytes(
        text.cast::<u8>(),
        crate::__rust_anything_c_abi::len(text_len),
    ))
}

/// `long long ra_kompat_parse_utsname(const unsigned char *text, unsigned int text_len, long long force, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_kompat.c:45`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_kompat_parse_utsname(
    text: *const std::ffi::c_uchar,
    text_len: std::ffi::c_uint,
    force: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_parse_utsname(
        crate::__rust_anything_c_abi::bytes(
            text.cast::<u8>(),
            crate::__rust_anything_c_abi::len(text_len),
        ),
        force,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_kompat_parse_utsname` as `rust_anything_ra_kompat_parse_utsname`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_kompat_parse_utsname(
    text: *const std::ffi::c_uchar,
    text_len: std::ffi::c_uint,
    force: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_parse_utsname(
        crate::__rust_anything_c_abi::bytes(
            text.cast::<u8>(),
            crate::__rust_anything_c_abi::len(text_len),
        ),
        force,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_kompat_sysenter(long long sysnum, long long actual, long long virtual, long long abi_cs, long long det_result, const unsigned char *regs_bytes, unsigned int regs_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_kompat.c:73`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_kompat_sysenter(
    sysnum: std::ffi::c_longlong,
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    abi_cs: std::ffi::c_longlong,
    det_result: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_sysenter(
        sysnum,
        actual,
        r#virtual,
        abi_cs,
        det_result,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_kompat_sysenter` as `rust_anything_ra_kompat_sysenter`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_kompat_sysenter(
    sysnum: std::ffi::c_longlong,
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    abi_cs: std::ffi::c_longlong,
    det_result: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_sysenter(
        sysnum,
        actual,
        r#virtual,
        abi_cs,
        det_result,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_kompat_sysexit(long long sysnum, long long modified_sysnum, long long actual, long long virtual, const unsigned char *regs_bytes, unsigned int regs_len, const unsigned char *guest_bytes, unsigned int guest_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_kompat.c:97`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_kompat_sysexit(
    sysnum: std::ffi::c_longlong,
    modified_sysnum: std::ffi::c_longlong,
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    guest_bytes: *const std::ffi::c_uchar,
    guest_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_sysexit(
        sysnum,
        modified_sysnum,
        actual,
        r#virtual,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            guest_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(guest_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_kompat_sysexit` as `rust_anything_ra_kompat_sysexit`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_kompat_sysexit(
    sysnum: std::ffi::c_longlong,
    modified_sysnum: std::ffi::c_longlong,
    actual: std::ffi::c_longlong,
    r#virtual: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    guest_bytes: *const std::ffi::c_uchar,
    guest_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_kompat_sysexit(
        sysnum,
        modified_sysnum,
        actual,
        r#virtual,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            guest_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(guest_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_mountinfo_bindings(const unsigned char *pairs, unsigned int pairs_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_mountinfo.c:31`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_mountinfo_bindings(
    pairs: *const std::ffi::c_uchar,
    pairs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_mountinfo_bindings(
        crate::__rust_anything_c_abi::bytes(
            pairs.cast::<u8>(),
            crate::__rust_anything_c_abi::len(pairs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_mountinfo_bindings` as `rust_anything_ra_mountinfo_bindings`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_mountinfo_bindings(
    pairs: *const std::ffi::c_uchar,
    pairs_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_mountinfo_bindings(
        crate::__rust_anything_c_abi::bytes(
            pairs.cast::<u8>(),
            crate::__rust_anything_c_abi::len(pairs_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_mountinfo_path(const unsigned char *path_bytes, unsigned int path_len, const unsigned char *real_text, unsigned int real_len, long long compare_result, long long get_tracee_ok, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_mountinfo.c:73`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_mountinfo_path(
    path_bytes: *const std::ffi::c_uchar,
    path_len: std::ffi::c_uint,
    real_text: *const std::ffi::c_uchar,
    real_len: std::ffi::c_uint,
    compare_result: std::ffi::c_longlong,
    get_tracee_ok: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_mountinfo_path(
        crate::__rust_anything_c_abi::bytes(
            path_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(path_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            real_text.cast::<u8>(),
            crate::__rust_anything_c_abi::len(real_len),
        ),
        compare_result,
        get_tracee_ok,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_mountinfo_path` as `rust_anything_ra_mountinfo_path`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_mountinfo_path(
    path_bytes: *const std::ffi::c_uchar,
    path_len: std::ffi::c_uint,
    real_text: *const std::ffi::c_uchar,
    real_len: std::ffi::c_uint,
    compare_result: std::ffi::c_longlong,
    get_tracee_ok: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_mountinfo_path(
        crate::__rust_anything_c_abi::bytes(
            path_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(path_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            real_text.cast::<u8>(),
            crate::__rust_anything_c_abi::len(real_len),
        ),
        compare_result,
        get_tracee_ok,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_port_mod(long long family, long long port, long long is_socketcall, long long is_bind, long long is_udp, const unsigned char *arg2_bytes, unsigned int arg2_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_port.c:46`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_port_mod(
    family: std::ffi::c_longlong,
    port: std::ffi::c_longlong,
    is_socketcall: std::ffi::c_longlong,
    is_bind: std::ffi::c_longlong,
    is_udp: std::ffi::c_longlong,
    arg2_bytes: *const std::ffi::c_uchar,
    arg2_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_mod(
        family,
        port,
        is_socketcall,
        is_bind,
        is_udp,
        crate::__rust_anything_c_abi::bytes(
            arg2_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(arg2_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_port_mod` as `rust_anything_ra_port_mod`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_port_mod(
    family: std::ffi::c_longlong,
    port: std::ffi::c_longlong,
    is_socketcall: std::ffi::c_longlong,
    is_bind: std::ffi::c_longlong,
    is_udp: std::ffi::c_longlong,
    arg2_bytes: *const std::ffi::c_uchar,
    arg2_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_mod(
        family,
        port,
        is_socketcall,
        is_bind,
        is_udp,
        crate::__rust_anything_c_abi::bytes(
            arg2_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(arg2_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_port_localhost(long long family, const unsigned char *addr_bytes, unsigned int addr_len)` (`extension/extensions_extra_harness/ext_port.c:79`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_port_localhost(
    family: std::ffi::c_longlong,
    addr_bytes: *const std::ffi::c_uchar,
    addr_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_localhost(
        family,
        crate::__rust_anything_c_abi::bytes(
            addr_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(addr_len),
        ),
    )
}

/// `ra_port_localhost` as `rust_anything_ra_port_localhost`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_port_localhost(
    family: std::ffi::c_longlong,
    addr_bytes: *const std::ffi::c_uchar,
    addr_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_localhost(
        family,
        crate::__rust_anything_c_abi::bytes(
            addr_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(addr_len),
        ),
    )
}

/// `long long ra_port_dispatch(long long sysnum, long long call, const unsigned char *regs_bytes, unsigned int regs_len, const unsigned char *guest_bytes, unsigned int guest_len, unsigned char *out, unsigned int out_len)` (`extension/extensions_extra_harness/ext_port.c:95`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_port_dispatch(
    sysnum: std::ffi::c_longlong,
    call: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    guest_bytes: *const std::ffi::c_uchar,
    guest_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_dispatch(
        sysnum,
        call,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            guest_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(guest_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_port_dispatch` as `rust_anything_ra_port_dispatch`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_port_dispatch(
    sysnum: std::ffi::c_longlong,
    call: std::ffi::c_longlong,
    regs_bytes: *const std::ffi::c_uchar,
    regs_len: std::ffi::c_uint,
    guest_bytes: *const std::ffi::c_uchar,
    guest_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_port_dispatch(
        sysnum,
        call,
        crate::__rust_anything_c_abi::bytes(
            regs_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(regs_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            guest_bytes.cast::<u8>(),
            crate::__rust_anything_c_abi::len(guest_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}
