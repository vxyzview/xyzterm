//! JNI front door for the XYZTerm Rust runtime.
//!
//! Only functions backed by working Rust implementations are exported here.
//! Session, PTY, Proot, backup and loader entry points move in from their own
//! units (see `rust/MAP.md`) and are added when those units pass their gates.

pub mod grid_registry;
pub mod session_manager;

use std::fs::File;
use std::io::Read;
use std::sync::atomic::{AtomicBool, Ordering};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
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

/// Terminal key encoding from Termux v0.118.3 `KeyHandler.getCode`.
/// Returns `null` where the original returns Java `null`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_keyCode(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    key_code: jint,
    key_mode: jint,
    cursor_app: jboolean,
    keypad_application: jboolean,
) -> jstring {
    match terminal_engine::port::get_code(
        jlong::from(key_code),
        jlong::from(key_mode),
        cursor_app != 0,
        keypad_application != 0,
    ) {
        Ok(Some(value)) => return_string(&mut env, value),
        Ok(None) => std::ptr::null_mut(),
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_perceivedBrightness(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    color: jint,
) -> jint {
    terminal_engine::port::get_perceived_brightness_of_color(jlong::from(color)).unwrap_or(0)
        as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_decodeForeColor(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    style: jlong,
) -> jint {
    terminal_engine::port::decode_fore_color(style).unwrap_or(0) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_decodeBackColor(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    style: jlong,
) -> jint {
    terminal_engine::port::decode_back_color(style).unwrap_or(0) as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_decodeEffect(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    style: jlong,
) -> jint {
    terminal_engine::port::decode_effect(style).unwrap_or(0) as jint
}

/// Creates a Rust-owned terminal grid; Kotlin receives only an opaque handle.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_createTerminal(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    columns: jint,
    rows: jint,
) -> jlong {
    if columns <= 0 || rows <= 0 {
        return 0;
    }
    match grid_registry::registry().lock() {
        Ok(mut registry) => registry
            .create(columns as usize, rows as usize)
            .unwrap_or(0),
        Err(_) => 0,
    }
}

/// Appends a batch of child-process output bytes to a terminal grid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_appendTerminal(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    data: jni::objects::JByteArray<'_>,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&data) else {
        return jni::sys::JNI_FALSE;
    };
    match grid_registry::registry().lock() {
        Ok(mut registry) => {
            if registry.append(handle, &bytes) {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            }
        }
        Err(_) => jni::sys::JNI_FALSE,
    }
}

/// One batched read of cursor, title and visible screen as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_terminalSnapshot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let snapshot = grid_registry::registry()
        .lock()
        .ok()
        .and_then(|registry| registry.snapshot(handle));
    let Some(snapshot) = snapshot else {
        return std::ptr::null_mut();
    };
    let value = serde_json::json!({
        "row": snapshot.cursor_row,
        "col": snapshot.cursor_col,
        "title": snapshot.title,
        "screen": snapshot.screen,
    });
    return_string(&mut env, value.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_closeTerminal(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    match grid_registry::registry().lock() {
        Ok(mut registry) => {
            if registry.close(handle) {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            }
        }
        Err(_) => jni::sys::JNI_FALSE,
    }
}

/// Creates a real shell session backed by a Rust PTY and terminal grid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_createSession(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    shell: JString<'_>,
    columns: jint,
    rows: jint,
) -> jlong {
    let Some(shell) = jstring_value(&mut env, &shell) else {
        return 0;
    };
    if columns <= 0 || rows <= 0 {
        return 0;
    }
    match session_manager::sessions().lock() {
        Ok(mut registry) => registry
            .create(&shell, columns as usize, rows as usize)
            .unwrap_or(0) as jlong,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_writeInput(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    data: jni::objects::JByteArray<'_>,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&data) else {
        return jni::sys::JNI_FALSE;
    };
    match session_manager::sessions().lock() {
        Ok(mut registry) => match registry.get_mut(handle as u64) {
            Some(session) => {
                if session.write_input(&bytes).is_ok() {
                    jni::sys::JNI_TRUE
                } else {
                    jni::sys::JNI_FALSE
                }
            }
            None => jni::sys::JNI_FALSE,
        },
        Err(_) => jni::sys::JNI_FALSE,
    }
}

/// Drains available child output and feeds it into the session's Rust grid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_readOutput(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jni::sys::jbyteArray {
    let bytes = session_manager::sessions()
        .lock()
        .ok()
        .and_then(|mut registry| registry.get_mut(handle as u64).and_then(|s| s.pump().ok()))
        .unwrap_or_default();
    match env.byte_array_from_slice(&bytes) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_resizeTerminal(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    columns: jint,
    rows: jint,
) -> jboolean {
    match session_manager::sessions().lock() {
        Ok(mut registry) => match registry.get_mut(handle as u64) {
            Some(session) => {
                if session.resize(columns as usize, rows as usize) {
                    jni::sys::JNI_TRUE
                } else {
                    jni::sys::JNI_FALSE
                }
            }
            None => jni::sys::JNI_FALSE,
        },
        Err(_) => jni::sys::JNI_FALSE,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_getSessionState(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let state = session_manager::sessions()
        .lock()
        .ok()
        .and_then(|registry| registry.get(handle as u64).map(|s| s.state().to_string()));
    match state {
        Some(state) => return_string(&mut env, state),
        None => std::ptr::null_mut(),
    }
}

/// One batched read of a live session's cursor, title and visible screen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_sessionSnapshot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let snapshot = session_manager::sessions()
        .lock()
        .ok()
        .and_then(|registry| registry.get(handle as u64).map(|s| s.snapshot()));
    let Some(snapshot) = snapshot else {
        return std::ptr::null_mut();
    };
    let value = serde_json::json!({
        "row": snapshot.cursor_row,
        "col": snapshot.cursor_col,
        "title": snapshot.title,
        "screen": snapshot.screen,
    });
    return_string(&mut env, value.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_closeSession(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    match session_manager::sessions().lock() {
        Ok(mut registry) => {
            if registry.close(handle as u64) {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            }
        }
        Err(_) => jni::sys::JNI_FALSE,
    }
}

/// Installs a packaged rootfs from its zip + TSV manifest into `destination`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_installRootfs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    archive: JString<'_>,
    manifest_text: JString<'_>,
    destination: JString<'_>,
) -> jboolean {
    let (Some(archive), Some(manifest_text), Some(destination)) = (
        jstring_value(&mut env, &archive),
        jstring_value(&mut env, &manifest_text),
        jstring_value(&mut env, &destination),
    ) else {
        throw_bad_argument(&mut env, "installRootfs: null argument");
        return jni::sys::JNI_FALSE;
    };
    let manifest = match rootfs_manager::parse_manifest(&manifest_text) {
        Ok(manifest) => manifest,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            return jni::sys::JNI_FALSE;
        }
    };
    match rootfs_manager::install(
        std::path::Path::new(&archive),
        &manifest,
        std::path::Path::new(&destination),
    ) {
        Ok(_) => jni::sys::JNI_TRUE,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", error.to_string());
            jni::sys::JNI_FALSE
        }
    }
}

/// True when every manifest entry is present with the recorded size.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_isRootfsInstalled(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    manifest_text: JString<'_>,
    destination: JString<'_>,
) -> jboolean {
    let (Some(manifest_text), Some(destination)) = (
        jstring_value(&mut env, &manifest_text),
        jstring_value(&mut env, &destination),
    ) else {
        throw_bad_argument(&mut env, "isRootfsInstalled: null argument");
        return jni::sys::JNI_FALSE;
    };
    match rootfs_manager::parse_manifest(&manifest_text) {
        Ok(manifest)
            if rootfs_manager::is_install_complete(
                std::path::Path::new(&destination),
                &manifest,
            ) =>
        {
            jni::sys::JNI_TRUE
        }
        Ok(_) | Err(_) => jni::sys::JNI_FALSE,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_createBackup(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    source: JString<'_>,
    destination: JString<'_>,
    passphrase: JString<'_>,
) -> jboolean {
    let (Some(source), Some(destination), Some(passphrase)) = (
        jstring_value(&mut env, &source),
        jstring_value(&mut env, &destination),
        jstring_value(&mut env, &passphrase),
    ) else {
        throw_bad_argument(&mut env, "createBackup: null argument");
        return jni::sys::JNI_FALSE;
    };
    match backup_engine::create_backup(
        std::path::Path::new(&source),
        std::path::Path::new(&destination),
        passphrase.as_bytes(),
    ) {
        Ok(()) => jni::sys::JNI_TRUE,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", error.to_string());
            jni::sys::JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_restoreBackup(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    archive: JString<'_>,
    destination: JString<'_>,
    passphrase: JString<'_>,
) -> jboolean {
    let (Some(archive), Some(destination), Some(passphrase)) = (
        jstring_value(&mut env, &archive),
        jstring_value(&mut env, &destination),
        jstring_value(&mut env, &passphrase),
    ) else {
        throw_bad_argument(&mut env, "restoreBackup: null argument");
        return jni::sys::JNI_FALSE;
    };
    match backup_engine::restore_backup(
        std::path::Path::new(&archive),
        std::path::Path::new(&destination),
        passphrase.as_bytes(),
    ) {
        Ok(()) => jni::sys::JNI_TRUE,
        Err(error) => {
            let _ = env.throw_new("java/io/IOException", error.to_string());
            jni::sys::JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_exportSessions(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let json = session_manager::sessions()
        .lock()
        .map(|registry| registry.export_json())
        .unwrap_or_else(|_| "[]".to_string());
    return_string(&mut env, json)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_xyzterm_bridge_XyztermNative_importSessions(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    json: JString<'_>,
) -> jint {
    let Some(json) = jstring_value(&mut env, &json) else {
        return 0;
    };
    match session_manager::sessions().lock() {
        Ok(mut registry) => registry.import_json(&json) as jint,
        Err(_) => 0,
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
