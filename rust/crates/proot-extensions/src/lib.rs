//! C ABI for `extension/proot_ext_harness/`: drop-in replacements with the original names and signatures, generated
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

/// `long long ra_fs_corrected_size(const char *path)` (`extension/proot_ext_harness/fix_symlink_size_harness.c:20`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_fs_corrected_size(path: *const c_char) -> std::ffi::c_longlong {
    port::ra_fs_corrected_size(crate::__rust_anything_c_abi::c_str(path))
}

/// `ra_fs_corrected_size` as `rust_anything_ra_fs_corrected_size`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_fs_corrected_size(path: *const c_char) -> std::ffi::c_longlong {
    port::ra_fs_corrected_size(crate::__rust_anything_c_abi::c_str(path))
}

/// `unsigned int ra_hf_hasprefix(const char *prefix, const char *str)` (`extension/proot_ext_harness/hidden_files_harness.c:39`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_hf_hasprefix(prefix: *const c_char, str: *const c_char) -> std::ffi::c_uint {
    port::ra_hf_hasprefix(
        crate::__rust_anything_c_abi::c_str(prefix),
        crate::__rust_anything_c_abi::c_str(str),
    )
}

/// `ra_hf_hasprefix` as `rust_anything_ra_hf_hasprefix`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_hf_hasprefix(
    prefix: *const c_char,
    str: *const c_char,
) -> std::ffi::c_uint {
    port::ra_hf_hasprefix(
        crate::__rust_anything_c_abi::c_str(prefix),
        crate::__rust_anything_c_abi::c_str(str),
    )
}

/// `unsigned int ra_hf_mybcopy(const unsigned char *src, unsigned int num, unsigned char *dst, unsigned int dst_n)` (`extension/proot_ext_harness/hidden_files_harness.c:44`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_hf_mybcopy(
    src: *const std::ffi::c_uchar,
    num: std::ffi::c_uint,
    dst: *mut std::ffi::c_uchar,
    dst_n: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_hf_mybcopy(
        crate::__rust_anything_c_abi::bytes(
            src.cast::<u8>(),
            crate::__rust_anything_c_abi::len(num),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            dst.cast::<u8>(),
            crate::__rust_anything_c_abi::len(dst_n),
        ),
    )
}

/// `ra_hf_mybcopy` as `rust_anything_ra_hf_mybcopy`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_hf_mybcopy(
    src: *const std::ffi::c_uchar,
    num: std::ffi::c_uint,
    dst: *mut std::ffi::c_uchar,
    dst_n: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_hf_mybcopy(
        crate::__rust_anything_c_abi::bytes(
            src.cast::<u8>(),
            crate::__rust_anything_c_abi::len(num),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            dst.cast::<u8>(),
            crate::__rust_anything_c_abi::len(dst_n),
        ),
    )
}

/// `unsigned int ra_hf_filter(int is64, const unsigned char *orig, unsigned int res, unsigned char *copy, unsigned int copy_n)` (`extension/proot_ext_harness/hidden_files_harness.c:53`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_hf_filter(
    is64: std::ffi::c_int,
    orig: *const std::ffi::c_uchar,
    res: std::ffi::c_uint,
    copy: *mut std::ffi::c_uchar,
    copy_n: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_hf_filter(
        is64,
        crate::__rust_anything_c_abi::bytes(
            orig.cast::<u8>(),
            crate::__rust_anything_c_abi::len(res),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            copy.cast::<u8>(),
            crate::__rust_anything_c_abi::len(copy_n),
        ),
    )
}

/// `ra_hf_filter` as `rust_anything_ra_hf_filter`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_hf_filter(
    is64: std::ffi::c_int,
    orig: *const std::ffi::c_uchar,
    res: std::ffi::c_uint,
    copy: *mut std::ffi::c_uchar,
    copy_n: std::ffi::c_uint,
) -> std::ffi::c_uint {
    port::ra_hf_filter(
        is64,
        crate::__rust_anything_c_abi::bytes(
            orig.cast::<u8>(),
            crate::__rust_anything_c_abi::len(res),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            copy.cast::<u8>(),
            crate::__rust_anything_c_abi::len(copy_n),
        ),
    )
}
