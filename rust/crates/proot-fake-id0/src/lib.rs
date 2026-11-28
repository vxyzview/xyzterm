//! C ABI for `extension/fakeid0_harness/`: drop-in replacements with the original names and signatures, generated
//! by rust-anything from `rust-anything.json`. All of the crate's `unsafe` code lives in this file.

pub mod port;

use std::ffi::c_char;

mod __rust_anything_c_abi {
    use std::ffi::{CStr, c_char};

    /// A `const char *` argument as bytes; `None` for `NULL`.
    pub(super) fn c_str<'a>(ptr: *const c_char) -> Option<&'a [u8]> {
        if ptr.is_null() {
            return None;
        }

        // SAFETY: the C API takes NUL-terminated strings that stay valid for the call;
        // NULL is handled above.
        Some(unsafe { CStr::from_ptr(ptr) }.to_bytes())
    }

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

/// `int ra_fid0_dtoo(int n)` (`extension/fakeid0_harness/helpers_harness.c:65`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_dtoo(n: std::ffi::c_int) -> std::ffi::c_int {
    port::ra_fid0_dtoo(n)
}

/// `ra_fid0_dtoo` as `rust_anything_ra_fid0_dtoo`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_dtoo(n: std::ffi::c_int) -> std::ffi::c_int {
    port::ra_fid0_dtoo(n)
}

/// `int ra_fid0_otod(int n)` (`extension/fakeid0_harness/helpers_harness.c:70`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_otod(n: std::ffi::c_int) -> std::ffi::c_int {
    port::ra_fid0_otod(n)
}

/// `ra_fid0_otod` as `rust_anything_ra_fid0_otod`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_otod(n: std::ffi::c_int) -> std::ffi::c_int {
    port::ra_fid0_otod(n)
}

/// `int ra_fid0_get_name(const char *path)` (`extension/fakeid0_harness/helpers_harness.c:76`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_get_name(path: *const c_char) -> std::ffi::c_int {
    port::ra_fid0_get_name(crate::__rust_anything_c_abi::c_str(path))
}

/// `ra_fid0_get_name` as `rust_anything_ra_fid0_get_name`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_get_name(path: *const c_char) -> std::ffi::c_int {
    port::ra_fid0_get_name(crate::__rust_anything_c_abi::c_str(path))
}

/// `int ra_fid0_get_dir_path(const char *path, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/helpers_harness.c:86`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_get_dir_path(
    path: *const c_char,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_get_dir_path(
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_get_dir_path` as `rust_anything_ra_fid0_get_dir_path`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_get_dir_path(
    path: *const c_char,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_get_dir_path(
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_get_meta_path(const char *path, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/helpers_harness.c:102`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_get_meta_path(
    path: *const c_char,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_get_meta_path(
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_get_meta_path` as `rust_anything_ra_fid0_get_meta_path`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_get_meta_path(
    path: *const c_char,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_get_meta_path(
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_path_exists(const char *path)` (`extension/fakeid0_harness/helpers_harness.c:121`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_path_exists(path: *const c_char) -> std::ffi::c_int {
    port::ra_fid0_path_exists(crate::__rust_anything_c_abi::c_str(path))
}

/// `ra_fid0_path_exists` as `rust_anything_ra_fid0_path_exists`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_path_exists(path: *const c_char) -> std::ffi::c_int {
    port::ra_fid0_path_exists(crate::__rust_anything_c_abi::c_str(path))
}

/// `int ra_fid0_get_permissions(const char *meta_path, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, int uses_real)` (`extension/fakeid0_harness/helpers_harness.c:129`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_get_permissions(
    meta_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    uses_real: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_get_permissions(
        crate::__rust_anything_c_abi::c_str(meta_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        uses_real,
    )
}

/// `ra_fid0_get_permissions` as `rust_anything_ra_fid0_get_permissions`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_get_permissions(
    meta_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    uses_real: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_get_permissions(
        crate::__rust_anything_c_abi::c_str(meta_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        uses_real,
    )
}

/// `int ra_fid0_read_meta_file(const char *meta_path, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/helpers_harness.c:142`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_read_meta_file(
    meta_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_read_meta_file(
        crate::__rust_anything_c_abi::c_str(meta_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_read_meta_file` as `rust_anything_ra_fid0_read_meta_file`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_read_meta_file(
    meta_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_read_meta_file(
        crate::__rust_anything_c_abi::c_str(meta_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_write_meta_file(const char *meta_path, unsigned int mode, unsigned int owner, unsigned int group, int is_creat, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/helpers_harness.c:166`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_write_meta_file(
    meta_path: *const c_char,
    mode: std::ffi::c_uint,
    owner: std::ffi::c_uint,
    group: std::ffi::c_uint,
    is_creat: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_write_meta_file(
        crate::__rust_anything_c_abi::c_str(meta_path),
        mode,
        owner,
        group,
        is_creat,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_write_meta_file` as `rust_anything_ra_fid0_write_meta_file`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_write_meta_file(
    meta_path: *const c_char,
    mode: std::ffi::c_uint,
    owner: std::ffi::c_uint,
    group: std::ffi::c_uint,
    is_creat: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_write_meta_file(
        crate::__rust_anything_c_abi::c_str(meta_path),
        mode,
        owner,
        group,
        is_creat,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_check_dir_perms(int type, const char *path, const char *rel_path, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps)` (`extension/fakeid0_harness/helpers_harness.c:198`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_check_dir_perms(
    r#type: std::ffi::c_int,
    path: *const c_char,
    rel_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_check_dir_perms(
        r#type,
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::c_str(rel_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    )
}

/// `ra_fid0_check_dir_perms` as `rust_anything_ra_fid0_check_dir_perms`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_check_dir_perms(
    r#type: std::ffi::c_int,
    path: *const c_char,
    rel_path: *const c_char,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_check_dir_perms(
        r#type,
        crate::__rust_anything_c_abi::c_str(path),
        crate::__rust_anything_c_abi::c_str(rel_path),
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
    )
}

/// `int ra_fid0_setxid(int kind, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned int new_id, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:235`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_setxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_id: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_id,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_setxid` as `rust_anything_ra_fid0_setxid`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_setxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_id: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_id,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_setrexid(int kind, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned int new_r, unsigned int new_e, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:250`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_setrexid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_r: std::ffi::c_uint,
    new_e: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setrexid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_r,
        new_e,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_setrexid` as `rust_anything_ra_fid0_setrexid`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_setrexid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_r: std::ffi::c_uint,
    new_e: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setrexid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_r,
        new_e,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_setresxid(int kind, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned int new_r, unsigned int new_e, unsigned int new_s, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:266`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_setresxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_r: std::ffi::c_uint,
    new_e: std::ffi::c_uint,
    new_s: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setresxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_r,
        new_e,
        new_s,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_setresxid` as `rust_anything_ra_fid0_setresxid`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_setresxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_r: std::ffi::c_uint,
    new_e: std::ffi::c_uint,
    new_s: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setresxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_r,
        new_e,
        new_s,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_setfsxid(int kind, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned int new_fs, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:284`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_setfsxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_fs: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setfsxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_fs,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_setfsxid` as `rust_anything_ra_fid0_setfsxid`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_setfsxid(
    kind: std::ffi::c_int,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    new_fs: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_setfsxid(
        kind,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        new_fs,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_init_ids(const char *uid_string, int fallback_uid, int fallback_gid, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:300`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_init_ids(
    uid_string: *const c_char,
    fallback_uid: std::ffi::c_int,
    fallback_gid: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_init_ids(
        crate::__rust_anything_c_abi::c_str(uid_string),
        fallback_uid,
        fallback_gid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_init_ids` as `rust_anything_ra_fid0_init_ids`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_init_ids(
    uid_string: *const c_char,
    fallback_uid: std::ffi::c_int,
    fallback_gid: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_init_ids(
        crate::__rust_anything_c_abi::c_str(uid_string),
        fallback_uid,
        fallback_gid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_prctl_keepcaps(int op, int result, unsigned int arg, int keep_caps)` (`extension/fakeid0_harness/ids_harness.c:334`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_prctl_keepcaps(
    op: std::ffi::c_int,
    result: std::ffi::c_int,
    arg: std::ffi::c_uint,
    keep_caps: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_prctl_keepcaps(op, result, arg, keep_caps)
}

/// `ra_fid0_prctl_keepcaps` as `rust_anything_ra_fid0_prctl_keepcaps`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_prctl_keepcaps(
    op: std::ffi::c_int,
    result: std::ffi::c_int,
    arg: std::ffi::c_uint,
    keep_caps: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_prctl_keepcaps(op, result, arg, keep_caps)
}

/// `int ra_fid0_exec_setid(unsigned int st_mode, int ruid, int euid, int suid, int fsuid, int rgid, int egid, int sgid, int fsgid, int umask, int caps_active, int keep_caps, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/ids_harness.c:342`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_exec_setid(
    st_mode: std::ffi::c_uint,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_exec_setid(
        st_mode,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_exec_setid` as `rust_anything_ra_fid0_exec_setid`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_exec_setid(
    st_mode: std::ffi::c_uint,
    ruid: std::ffi::c_int,
    euid: std::ffi::c_int,
    suid: std::ffi::c_int,
    fsuid: std::ffi::c_int,
    rgid: std::ffi::c_int,
    egid: std::ffi::c_int,
    sgid: std::ffi::c_int,
    fsgid: std::ffi::c_int,
    umask: std::ffi::c_int,
    caps_active: std::ffi::c_int,
    keep_caps: std::ffi::c_int,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_exec_setid(
        st_mode,
        ruid,
        euid,
        suid,
        fsuid,
        rgid,
        egid,
        sgid,
        fsgid,
        umask,
        caps_active,
        keep_caps,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `int ra_fid0_statx(unsigned int mask, unsigned int st_uid, unsigned int st_gid, unsigned int real_uid, unsigned int real_gid, unsigned int suid, unsigned int sgid, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/misc_harness.c:35`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_statx(
    mask: std::ffi::c_uint,
    st_uid: std::ffi::c_uint,
    st_gid: std::ffi::c_uint,
    real_uid: std::ffi::c_uint,
    real_gid: std::ffi::c_uint,
    suid: std::ffi::c_uint,
    sgid: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_statx(
        mask,
        st_uid,
        st_gid,
        real_uid,
        real_gid,
        suid,
        sgid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_statx` as `rust_anything_ra_fid0_statx`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_statx(
    mask: std::ffi::c_uint,
    st_uid: std::ffi::c_uint,
    st_gid: std::ffi::c_uint,
    real_uid: std::ffi::c_uint,
    real_gid: std::ffi::c_uint,
    suid: std::ffi::c_uint,
    sgid: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_statx(
        mask,
        st_uid,
        st_gid,
        real_uid,
        real_gid,
        suid,
        sgid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `unsigned int ra_fid0_stat_mode(unsigned int meta_mode, unsigned int st_mode)` (`extension/fakeid0_harness/misc_harness.c:72`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_stat_mode(
    meta_mode: std::ffi::c_uint,
    st_mode: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_fid0_stat_mode(meta_mode, st_mode)
}

/// `ra_fid0_stat_mode` as `rust_anything_ra_fid0_stat_mode`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_stat_mode(
    meta_mode: std::ffi::c_uint,
    st_mode: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_fid0_stat_mode(meta_mode, st_mode)
}

/// `int ra_fid0_access_check(int mode, int perms)` (`extension/fakeid0_harness/misc_harness.c:79`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_access_check(
    mode: std::ffi::c_int,
    perms: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_access_check(mode, perms)
}

/// `ra_fid0_access_check` as `rust_anything_ra_fid0_access_check`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_access_check(
    mode: std::ffi::c_int,
    perms: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_access_check(mode, perms)
}

/// `int ra_fid0_open_check(int flags, int perms)` (`extension/fakeid0_harness/misc_harness.c:97`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_open_check(
    flags: std::ffi::c_int,
    perms: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_open_check(flags, perms)
}

/// `ra_fid0_open_check` as `rust_anything_ra_fid0_open_check`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_open_check(
    flags: std::ffi::c_int,
    perms: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_open_check(flags, perms)
}

/// `int ra_fid0_socket_check(int result, int family, int protocol, int euid)` (`extension/fakeid0_harness/misc_harness.c:109`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_socket_check(
    result: std::ffi::c_int,
    family: std::ffi::c_int,
    protocol: std::ffi::c_int,
    euid: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_socket_check(result, family, protocol, euid)
}

/// `ra_fid0_socket_check` as `rust_anything_ra_fid0_socket_check`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_socket_check(
    result: std::ffi::c_int,
    family: std::ffi::c_int,
    protocol: std::ffi::c_int,
    euid: std::ffi::c_int,
) -> std::ffi::c_int {
    port::ra_fid0_socket_check(result, family, protocol, euid)
}

/// `int ra_fid0_sendmsg_scan(const unsigned char *buf, unsigned int controllen, int is32, unsigned int real_uid, unsigned int real_gid, unsigned char *out, unsigned int out_n)` (`extension/fakeid0_harness/misc_harness.c:140`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fid0_sendmsg_scan(
    buf: *const std::ffi::c_uchar,
    controllen: std::ffi::c_uint,
    is32: std::ffi::c_int,
    real_uid: std::ffi::c_uint,
    real_gid: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_sendmsg_scan(
        crate::__rust_anything_c_abi::bytes(
            buf.cast::<u8>(),
            crate::__rust_anything_c_abi::len(controllen),
        ),
        is32,
        real_uid,
        real_gid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}

/// `ra_fid0_sendmsg_scan` as `rust_anything_ra_fid0_sendmsg_scan`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fid0_sendmsg_scan(
    buf: *const std::ffi::c_uchar,
    controllen: std::ffi::c_uint,
    is32: std::ffi::c_int,
    real_uid: std::ffi::c_uint,
    real_gid: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_n: std::ffi::c_uint,
) -> std::ffi::c_int {
    port::ra_fid0_sendmsg_scan(
        crate::__rust_anything_c_abi::bytes(
            buf.cast::<u8>(),
            crate::__rust_anything_c_abi::len(controllen),
        ),
        is32,
        real_uid,
        real_gid,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_n),
        ),
    )
}
