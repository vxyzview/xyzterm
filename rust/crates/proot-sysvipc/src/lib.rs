//! C ABI for `extension/sysvipc_harness/`: drop-in replacements with the original names and signatures, generated
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

/// `long long ra_msgget(long long id, long long flags, unsigned char *objects, unsigned int objects_len)` (`extension/sysvipc_harness/originals.c:204`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_msgget(
    id: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgget(
        id,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `ra_msgget` as `rust_anything_ra_msgget`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_msgget(
    id: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgget(
        id,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `long long ra_msg_match(long long sender, long long filter, long long flags)` (`extension/sysvipc_harness/originals.c:216`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_msg_match(
    sender: std::ffi::c_longlong,
    filter: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_msg_match(sender, filter, flags)
}

/// `ra_msg_match` as `rust_anything_ra_msg_match`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_msg_match(
    sender: std::ffi::c_longlong,
    filter: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_msg_match(sender, filter, flags)
}

/// `long long ra_semget(long long key, long long nsems, long long flags, unsigned char *objects, unsigned int objects_len)` (`extension/sysvipc_harness/originals.c:222`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_semget(
    key: std::ffi::c_longlong,
    nsems: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_semget(
        key,
        nsems,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `ra_semget` as `rust_anything_ra_semget`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_semget(
    key: std::ffi::c_longlong,
    nsems: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_semget(
        key,
        nsems,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `long long ra_msgrcv_flags(long long msgsz, long long msgflg, unsigned char *state_out, unsigned int state_len)` (`extension/sysvipc_harness/originals.c:243`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_msgrcv_flags(
    msgsz: std::ffi::c_longlong,
    msgflg: std::ffi::c_longlong,
    state_out: *mut std::ffi::c_uchar,
    state_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgrcv_flags(
        msgsz,
        msgflg,
        crate::__rust_anything_c_abi::bytes_mut(
            state_out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(state_len),
        ),
    )
}

/// `ra_msgrcv_flags` as `rust_anything_ra_msgrcv_flags`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_msgrcv_flags(
    msgsz: std::ffi::c_longlong,
    msgflg: std::ffi::c_longlong,
    state_out: *mut std::ffi::c_uchar,
    state_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgrcv_flags(
        msgsz,
        msgflg,
        crate::__rust_anything_c_abi::bytes_mut(
            state_out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(state_len),
        ),
    )
}

/// `long long ra_msgrcv_deliver(const unsigned char *items, unsigned int items_len, long long msgsz, long long msgtyp, long long msgflg, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:260`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_msgrcv_deliver(
    items: *const std::ffi::c_uchar,
    items_len: std::ffi::c_uint,
    msgsz: std::ffi::c_longlong,
    msgtyp: std::ffi::c_longlong,
    msgflg: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgrcv_deliver(
        crate::__rust_anything_c_abi::bytes(
            items.cast::<u8>(),
            crate::__rust_anything_c_abi::len(items_len),
        ),
        msgsz,
        msgtyp,
        msgflg,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_msgrcv_deliver` as `rust_anything_ra_msgrcv_deliver`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_msgrcv_deliver(
    items: *const std::ffi::c_uchar,
    items_len: std::ffi::c_uint,
    msgsz: std::ffi::c_longlong,
    msgtyp: std::ffi::c_longlong,
    msgflg: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgrcv_deliver(
        crate::__rust_anything_c_abi::bytes(
            items.cast::<u8>(),
            crate::__rust_anything_c_abi::len(items_len),
        ),
        msgsz,
        msgtyp,
        msgflg,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_msgctl(long long object_id, long long cmd, unsigned char *objects, unsigned int objects_len)` (`extension/sysvipc_harness/originals.c:298`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_msgctl(
    object_id: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgctl(
        object_id,
        cmd,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `ra_msgctl` as `rust_anything_ra_msgctl`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_msgctl(
    object_id: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_msgctl(
        object_id,
        cmd,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `long long ra_sem_check(unsigned char *sems, unsigned int sems_len, const unsigned char *ops, unsigned int ops_len, long long wait_type_in)` (`extension/sysvipc_harness/originals.c:313`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_sem_check(
    sems: *mut std::ffi::c_uchar,
    sems_len: std::ffi::c_uint,
    ops: *const std::ffi::c_uchar,
    ops_len: std::ffi::c_uint,
    wait_type_in: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_sem_check(
        crate::__rust_anything_c_abi::bytes_mut(
            sems.cast::<u8>(),
            crate::__rust_anything_c_abi::len(sems_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            ops.cast::<u8>(),
            crate::__rust_anything_c_abi::len(ops_len),
        ),
        wait_type_in,
    )
}

/// `ra_sem_check` as `rust_anything_ra_sem_check`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_sem_check(
    sems: *mut std::ffi::c_uchar,
    sems_len: std::ffi::c_uint,
    ops: *const std::ffi::c_uchar,
    ops_len: std::ffi::c_uint,
    wait_type_in: std::ffi::c_longlong,
) -> std::ffi::c_longlong {
    port::ra_sem_check(
        crate::__rust_anything_c_abi::bytes_mut(
            sems.cast::<u8>(),
            crate::__rust_anything_c_abi::len(sems_len),
        ),
        crate::__rust_anything_c_abi::bytes(
            ops.cast::<u8>(),
            crate::__rust_anything_c_abi::len(ops_len),
        ),
        wait_type_in,
    )
}

/// `long long ra_semctl(long long semnum, long long cmd, long long cmdarg, unsigned char *sems, unsigned int sems_len, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:340`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_semctl(
    semnum: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    cmdarg: std::ffi::c_longlong,
    sems: *mut std::ffi::c_uchar,
    sems_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_semctl(
        semnum,
        cmd,
        cmdarg,
        crate::__rust_anything_c_abi::bytes_mut(
            sems.cast::<u8>(),
            crate::__rust_anything_c_abi::len(sems_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_semctl` as `rust_anything_ra_semctl`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_semctl(
    semnum: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    cmdarg: std::ffi::c_longlong,
    sems: *mut std::ffi::c_uchar,
    sems_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_semctl(
        semnum,
        cmd,
        cmdarg,
        crate::__rust_anything_c_abi::bytes_mut(
            sems.cast::<u8>(),
            crate::__rust_anything_c_abi::len(sems_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_shmget(long long key, long long size, long long flags, unsigned char *objects, unsigned int objects_len)` (`extension/sysvipc_harness/originals.c:364`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_shmget(
    key: std::ffi::c_longlong,
    size: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmget(
        key,
        size,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `ra_shmget` as `rust_anything_ra_shmget`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_shmget(
    key: std::ffi::c_longlong,
    size: std::ffi::c_longlong,
    flags: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmget(
        key,
        size,
        flags,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
    )
}

/// `long long ra_shmctl(long long object_id, long long cmd, long long mappings, unsigned char *objects, unsigned int objects_len, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:377`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_shmctl(
    object_id: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    mappings: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmctl(
        object_id,
        cmd,
        mappings,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_shmctl` as `rust_anything_ra_shmctl`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_shmctl(
    object_id: std::ffi::c_longlong,
    cmd: std::ffi::c_longlong,
    mappings: std::ffi::c_longlong,
    objects: *mut std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmctl(
        object_id,
        cmd,
        mappings,
        crate::__rust_anything_c_abi::bytes_mut(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_shmdt(long long addr, const unsigned char *mappings, unsigned int mappings_len, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:400`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_shmdt(
    addr: std::ffi::c_longlong,
    mappings: *const std::ffi::c_uchar,
    mappings_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmdt(
        addr,
        crate::__rust_anything_c_abi::bytes(
            mappings.cast::<u8>(),
            crate::__rust_anything_c_abi::len(mappings_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_shmdt` as `rust_anything_ra_shmdt`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_shmdt(
    addr: std::ffi::c_longlong,
    mappings: *const std::ffi::c_uchar,
    mappings_len: std::ffi::c_uint,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shmdt(
        addr,
        crate::__rust_anything_c_abi::bytes(
            mappings.cast::<u8>(),
            crate::__rust_anything_c_abi::len(mappings_len),
        ),
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_shm_recvmsg_pointers(long long is32, long long guest_buf, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:421`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_shm_recvmsg_pointers(
    is32: std::ffi::c_longlong,
    guest_buf: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shm_recvmsg_pointers(
        is32,
        guest_buf,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_shm_recvmsg_pointers` as `rust_anything_ra_shm_recvmsg_pointers`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_shm_recvmsg_pointers(
    is32: std::ffi::c_longlong,
    guest_buf: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shm_recvmsg_pointers(
        is32,
        guest_buf,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_wake(long long reason, long long state, long long status, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:445`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_wake(
    reason: std::ffi::c_longlong,
    state: std::ffi::c_longlong,
    status: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_wake(
        reason,
        state,
        status,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_wake` as `rust_anything_ra_wake`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_wake(
    reason: std::ffi::c_longlong,
    state: std::ffi::c_longlong,
    status: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_wake(
        reason,
        state,
        status,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `long long ra_layout(unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:461`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_layout(
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_layout(crate::__rust_anything_c_abi::bytes_mut(
        out.cast::<u8>(),
        crate::__rust_anything_c_abi::len(out_len),
    ))
}

/// `ra_layout` as `rust_anything_ra_layout`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_layout(
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_layout(crate::__rust_anything_c_abi::bytes_mut(
        out.cast::<u8>(),
        crate::__rust_anything_c_abi::len(out_len),
    ))
}

/// `long long ra_shm_fill_proc(const unsigned char *objects, unsigned int objects_len, long long page_size, unsigned char *out, unsigned int out_len)` (`extension/sysvipc_harness/originals.c:506`).
#[cfg(not(feature = "shadow"))]
// SAFETY: exported under the C name it replaces: link it instead of the original object.
#[unsafe(no_mangle)]
pub extern "C" fn ra_shm_fill_proc(
    objects: *const std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
    page_size: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shm_fill_proc(
        crate::__rust_anything_c_abi::bytes(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
        page_size,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}

/// `ra_shm_fill_proc` as `rust_anything_ra_shm_fill_proc`, for shadow builds beside the original.
// SAFETY: a name no C code defines.
#[unsafe(no_mangle)]
pub extern "C" fn rust_anything_ra_shm_fill_proc(
    objects: *const std::ffi::c_uchar,
    objects_len: std::ffi::c_uint,
    page_size: std::ffi::c_longlong,
    out: *mut std::ffi::c_uchar,
    out_len: std::ffi::c_uint,
) -> std::ffi::c_longlong {
    port::ra_shm_fill_proc(
        crate::__rust_anything_c_abi::bytes(
            objects.cast::<u8>(),
            crate::__rust_anything_c_abi::len(objects_len),
        ),
        page_size,
        crate::__rust_anything_c_abi::bytes_mut(
            out.cast::<u8>(),
            crate::__rust_anything_c_abi::len(out_len),
        ),
    )
}
