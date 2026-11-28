//! JNI front door for the XYZTerm Rust runtime.
//!
//! Only functions backed by working Rust implementations are exported here.
//! Session, PTY, Proot, backup and loader entry points move in from their own
//! units (see `rust/MAP.md`) and are added when those units pass their gates.

use std::fs::File;
use std::io::Read;
use std::sync::atomic::{AtomicBool, Ordering};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use sha2::{Digest, Sha256};
use xyzterm_path::{link_compat, port};

static INITIALIZED: AtomicBool = AtomicBool::new(false);

fn jstring_value(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    let java_str = env.get_string(value).ok()?;
    Some(String::from(java_str))
}

fn return_string(env: &mut JNIEnv<'_>, value: String) -> jstring {
    match env.new_string(value) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn throw_bad_argument(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message);
}

/// Normalizes the SHA-256 formats used by the project: colon-separated bytes
/// (`core/main/src/main/assets/hash`) and plain lowercase hex.
fn normalize_digest(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .flat_map(|c| c.to_lowercase())
        .collect()
}

/// Streams the file through SHA-256; never loads a whole rootfs archive into memory.
fn sha256_file(path: &str) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_initialize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    INITIALIZED.store(true, Ordering::SeqCst);
    jni::sys::JNI_TRUE
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_shutdown(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    INITIALIZED.store(false, Ordering::SeqCst);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_isReady(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    if INITIALIZED.load(Ordering::SeqCst) {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}

/// `Comparison` from `path/path.h`: 0 equal, 1 path1 prefix, 2 path2 prefix, 3 incomparable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_comparePaths(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    left: JString<'_>,
    right: JString<'_>,
) -> jint {
    match (
        jstring_value(&mut env, &left),
        jstring_value(&mut env, &right),
    ) {
        (Some(a), Some(b)) => port::compare_paths(Some(a.as_bytes()), Some(b.as_bytes())),
        _ => {
            throw_bad_argument(&mut env, "comparePaths: null string");
            3
        }
    }
}

/// The C++ shim's absolute-path resolution, exposed for path handling in Kotlin
/// adapters. `flags` uses the Linux `AT_*` values.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_resolvePath(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    base: JString<'_>,
    path: JString<'_>,
    flags: jint,
) -> jstring {
    match (
        jstring_value(&mut env, &base),
        jstring_value(&mut env, &path),
    ) {
        (Some(base), Some(path)) => {
            let resolved =
                link_compat::resolve_to_absolute(base.as_bytes(), path.as_bytes(), flags);
            return_string(&mut env, String::from_utf8_lossy(&resolved).into_owned())
        }
        _ => {
            throw_bad_argument(&mut env, "resolvePath: null string");
            std::ptr::null_mut()
        }
    }
}

/// `.l2s.<name>.<counter:04}` — the real file backing a compatibility link.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_l2sFinalName(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    intermediate: JString<'_>,
    counter: jint,
) -> jstring {
    let Some(intermediate) = jstring_value(&mut env, &intermediate) else {
        throw_bad_argument(&mut env, "l2sFinalName: null name");
        return std::ptr::null_mut();
    };
    let Ok(name) = link_compat::final_name(intermediate.as_bytes(), counter.max(0) as u32) else {
        throw_bad_argument(&mut env, "l2sFinalName: counter out of range");
        return std::ptr::null_mut();
    };
    return_string(&mut env, String::from_utf8_lossy(&name).into_owned())
}

/// Verifies a build-time packaged rootfs archive against its expected SHA-256.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_verifyRootfs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    archive: JString<'_>,
    expected: JString<'_>,
) -> jboolean {
    let (Some(archive), Some(expected)) = (
        jstring_value(&mut env, &archive),
        jstring_value(&mut env, &expected),
    ) else {
        throw_bad_argument(&mut env, "verifyRootfs: null argument");
        return jni::sys::JNI_FALSE;
    };
    match sha256_file(&archive) {
        Ok(actual) if normalize_digest(&actual) == normalize_digest(&expected) => {
            jni::sys::JNI_TRUE
        }
        Ok(_) | Err(_) => jni::sys::JNI_FALSE,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digest_formats_match() {
        let plain = "aabbccdd";
        let colon = "AA:BB:CC:DD";
        assert_eq!(normalize_digest(plain), normalize_digest(colon));
    }

    #[test]
    fn digest_of_a_real_file() {
        let path = std::env::temp_dir().join("xyzterm-sha256-test.txt");
        std::fs::write(&path, b"xyzterm").expect("write test fixture");
        assert_eq!(
            sha256_file(path.to_str().expect("utf8 temp path")).expect("hash file"),
            "b9601fb9196746aeca365038a457761a5121f1a8ccecb86835a0a12653fa6b47"
        );
        std::fs::remove_file(path).expect("remove test fixture");
    }
}
