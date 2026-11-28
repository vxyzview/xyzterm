//! C ABI for `path/`: drop-in replacements with the original names and signatures, generated
//! by rust-anything from `rust-anything.json`. All of the crate's `unsafe` code lives in this file.

pub mod link_compat;
pub mod port;
pub mod translate;

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
}

/// `Comparison compare_paths2(const char *path1, size_t length1, const char *path2, size_t length2)` (`path/path.c:536`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn compare_paths2(
    path1: *const c_char,
    length1: usize,
    path2: *const c_char,
    length2: usize,
) -> std::ffi::c_int {
    port::compare_paths2(
        crate::__rust_anything_c_abi::c_str(path1),
        length1,
        crate::__rust_anything_c_abi::c_str(path2),
        length2,
    )
}

/// `compare_paths2` as `rust_anything_compare_paths2`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_compare_paths2(
    path1: *const c_char,
    length1: usize,
    path2: *const c_char,
    length2: usize,
) -> std::ffi::c_int {
    port::compare_paths2(
        crate::__rust_anything_c_abi::c_str(path1),
        length1,
        crate::__rust_anything_c_abi::c_str(path2),
        length2,
    )
}

/// `Comparison compare_paths(const char *path1, const char *path2)` (`path/path.c:589`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn compare_paths(path1: *const c_char, path2: *const c_char) -> std::ffi::c_int {
    port::compare_paths(
        crate::__rust_anything_c_abi::c_str(path1),
        crate::__rust_anything_c_abi::c_str(path2),
    )
}

/// `compare_paths` as `rust_anything_compare_paths`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_compare_paths(
    path1: *const c_char,
    path2: *const c_char,
) -> std::ffi::c_int {
    port::compare_paths(
        crate::__rust_anything_c_abi::c_str(path1),
        crate::__rust_anything_c_abi::c_str(path2),
    )
}
