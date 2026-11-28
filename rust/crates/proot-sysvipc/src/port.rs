//! Pure Rust port of PRoot's System V IPC extension
//! (`features/terminal/proot/src/main/cpp/extension/sysvipc/`).
//!
//! Only host-testable logic is ported: IPC key/id translation and the object
//! slot rules, C struct layouts, permission/mode mapping, the message-match,
//! msgrcv/semctl/shmctl decision rules, the wake state machine and the
//! `/proc/sysvipc/shm` text layout.  Everything that needs a live tracee
//! (register peek/poke, tracee memory read/write, chained syscalls, the shm
//! helper process and the extension callback wiring) stays in C.
//!
//! The functions below keep the plain-argument shape of the recording harness:
//! object slots are 24-byte little-endian records, semaphore ops are 8-byte
//! records and the output buffer carries the bytes written to "guest memory".

#![forbid(unsafe_code)]

use std::ffi::{c_long, c_longlong};
use std::mem::{offset_of, size_of};

const IPC_PRIVATE: i32 = 0;
const IPC_CREAT: i32 = 0o1000;
const IPC_EXCL: i32 = 0o2000;
const IPC_NOWAIT: i32 = 0o4000;
const IPC_RMID: i32 = 0;
const IPC_STAT: i32 = 2;
const IPC_64: i32 = 0x100;
const MSG_NOERROR: i32 = 0o10000;
const MSG_EXCEPT: i32 = 0o20000;
const MSG_COPY: i32 = 0o40000;
const SYSVIPC_GETVAL: i32 = 12;
const SYSVIPC_SETVAL: i32 = 16;
const SYSVIPC_GETALL: i32 = 13;
const SYSVIPC_IPC_INFO: i32 = 3;
const SYSVIPC_SEM_INFO: i32 = 19;

const MAX_MSG_SIZE: i32 = 0xFFFF;
const MAX_SEMS: i32 = 512;
const MAX_NSEMS: i32 = 512;
const MAX_NSOPS: i32 = 512;
const MAX_SEMVAL: i32 = 0x7000;

const EINVAL: c_longlong = -22;
const ENOENT: c_longlong = -2;
const EEXIST: c_longlong = -17;
const EAGAIN: c_longlong = -11;
const ERANGE: c_longlong = -34;
const E2BIG: c_longlong = -7;
const ENOMSG: c_longlong = -42;
const ENOSPC: c_longlong = -28;

const WR_NOT_WAITING: i32 = 0;
const WR_WAIT_QUEUE_RECV: i32 = 1;
const WSTATE_RESTARTED_INTO_PPOLL: i32 = 2;
const WSTATE_ENTERED_PPOLL: i32 = 3;
const WSTATE_SIGNALED_PPOLL: i32 = 4;
const WSTATE_RESTARTED_INTO_PPOLL_CANCELED: i32 = 1;

const FAKE_PID: u32 = 1234;
const FAKE_FD: i32 = 42;

/* aarch64 PR_* numbers from syscall/sysnums-arm64.h; the C layout probe
 * exposes them at indexes 31..35 of ra_layout. */
const PR_MUNMAP: i32 = 210;
const PR_SOCKET: i32 = 356;
const PR_GETPID: i32 = 119;
const PR_PPOLL: i32 = 238;
const PR_VOID: i32 = 0;

/// 24-byte object record shared by the message, semaphore and shm wrappers.
const OBJ_LEN: usize = 24;
/// 72-byte `/proc/sysvipc/shm` input record.
const PROC_LEN: usize = 72;

#[repr(C)]
#[derive(Clone, Copy)]
struct Sembuf {
    sem_num: u16,
    sem_op: i16,
    sem_flg: i16,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct Seminfo {
    semmap: i32,
    semmni: i32,
    semmns: i32,
    semmnu: i32,
    semmsl: i32,
    semopm: i32,
    semume: i32,
    semusz: i32,
    semvmx: i32,
    semaem: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct IpcPerm {
    key: i32,
    uid: u32,
    gid: u32,
    cuid: u32,
    cgid: u32,
    mode: u32,
    seq: u16,
    pad2: u16,
    reserved1: u64,
    reserved2: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct ShmidDs {
    perm: IpcPerm,
    shm_segsz: usize,
    shm_atime: i64,
    shm_dtime: i64,
    shm_ctime: i64,
    shm_cpid: i32,
    shm_lpid: i32,
    shm_nattch: u64,
    reserved5: u64,
    reserved6: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct MsqidDs {
    msg_perm: IpcPerm,
    msg_stime: i64,
    msg_rtime: i64,
    msg_ctime: i64,
    msg_cbytes: usize,
    msg_qnum: u16,
    msg_qbytes: usize,
    msg_lspid: i32,
    msg_lrpid: i32,
    reserved4: usize,
    reserved5: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SockaddrUn {
    sun_family: u16,
    sun_path: [u8; 108],
}

fn i16_at(bytes: &[u8], at: usize) -> i16 {
    let mut raw = [0u8; 2];
    let n = 2.min(bytes.len().saturating_sub(at));
    raw[..n].copy_from_slice(&bytes[at..at + n]);
    i16::from_le_bytes(raw)
}

fn i32_at(bytes: &[u8], at: usize) -> i32 {
    let mut raw = [0u8; 4];
    let n = 4.min(bytes.len().saturating_sub(at));
    raw[..n].copy_from_slice(&bytes[at..at + n]);
    i32::from_le_bytes(raw)
}

fn u64_at(bytes: &[u8], at: usize) -> u64 {
    let mut raw = [0u8; 8];
    let n = 8.min(bytes.len().saturating_sub(at));
    raw[..n].copy_from_slice(&bytes[at..at + n]);
    u64::from_le_bytes(raw)
}

fn put_i16(out: &mut [u8], at: usize, value: i16) {
    if let Some(slot) = out.get_mut(at..at + 2) {
        slot.copy_from_slice(&value.to_le_bytes());
    }
}

fn put_i32(out: &mut [u8], at: usize, value: i32) {
    if let Some(slot) = out.get_mut(at..at + 4) {
        slot.copy_from_slice(&value.to_le_bytes());
    }
}

fn put_i64(out: &mut [u8], at: usize, value: i64) {
    put_u64(out, at, value as u64);
}

fn put_u64(out: &mut [u8], at: usize, value: u64) {
    if let Some(slot) = out.get_mut(at..at + 8) {
        slot.copy_from_slice(&value.to_le_bytes());
    }
}

/// Mirror of the C harness' 24-byte `ObjRec`.
#[derive(Clone, Copy, Default)]
struct Obj {
    key: i32,
    generation: i16,
    valid: bool,
    aux0: i32,
    aux1: i32,
    aux2: i32,
    aux3: i32,
}

fn parse_objs(bytes: &[u8]) -> Vec<Obj> {
    bytes
        .as_chunks::<OBJ_LEN>()
        .0
        .iter()
        .map(|b| Obj {
            key: i32_at(b, 0),
            generation: i16_at(b, 4),
            valid: i16_at(b, 6) != 0,
            aux0: i32_at(b, 8),
            aux1: i32_at(b, 12),
            aux2: i32_at(b, 16),
            aux3: i32_at(b, 20),
        })
        .collect()
}

/// `(index + 1) | (generation << 12)` — `IPC_OBJECT_ID` / the return of
/// `sysvipc_{msg,sem,shm}get`.
fn object_id(index: usize, generation: i16) -> c_longlong {
    c_longlong::from((index + 1) as i32 | ((generation as i32) << 12))
}

/// `LOOKUP_IPC_OBJECT`: low 12 bits are the 1-based index, the next 16 the
/// generation; generation 0 is the default for a freshly created slot.
fn lookup(id: c_longlong, objects: &[Obj]) -> Result<usize, c_longlong> {
    let id = id as i32;
    let index = (id & 0xFFF) as usize;
    if index == 0 || index > objects.len() {
        return Err(EINVAL);
    }
    let object = &objects[index - 1];
    if !object.valid || object.generation != ((id >> 12) & 0xFFFF) as i16 {
        return Err(EINVAL);
    }
    Ok(index - 1)
}

/// `sysvipc_msg_match()` including the MSG_EXCEPT inversion.
fn msg_match(sender: i32, filter: i32, flags: i32) -> bool {
    let matched =
        filter == 0 || sender == filter || (filter < 0 && sender <= filter.wrapping_neg());
    if flags & MSG_EXCEPT != 0 {
        !matched
    } else {
        matched
    }
}

/// First matching valid slot, or the first free slot — the search shared by
/// msgget, semget and shmget.
fn find_slot(objects: &[Obj], key: i32) -> (Option<usize>, Option<usize>) {
    let mut free = None;
    for (index, object) in objects.iter().enumerate() {
        if object.valid {
            if key != IPC_PRIVATE && object.key == key {
                return (Some(index), free);
            }
        } else if free.is_none() {
            free = Some(index);
        }
    }
    (None, free)
}

/// Validation shared by `sysvipc_do_msgrcv()` for a receive with no usable
/// message in the queue.
fn msgrcv_flags(msgsz: c_longlong, flags: i32) -> Result<bool, c_longlong> {
    if (msgsz as i32) < 0 {
        return Err(EINVAL);
    }
    if flags & !(IPC_NOWAIT | MSG_NOERROR | MSG_COPY | MSG_EXCEPT) != 0 {
        return Err(EINVAL);
    }
    let copy = flags & MSG_COPY != 0;
    if copy && (flags & IPC_NOWAIT == 0 || flags & MSG_EXCEPT != 0) {
        return Err(EINVAL);
    }
    Ok(copy)
}

/// Port of `ra_layout()`: sizes and offsets of the C structures and the IPC
/// constants the decisions use.
pub fn ra_layout(out: &mut [u8]) -> c_longlong {
    const VALUES: [u64; 36] = [
        size_of::<Sembuf>() as u64,
        size_of::<Seminfo>() as u64,
        size_of::<ShmidDs>() as u64,
        size_of::<MsqidDs>() as u64,
        size_of::<IpcPerm>() as u64,
        offset_of!(ShmidDs, shm_segsz) as u64,
        offset_of!(ShmidDs, shm_atime) as u64,
        offset_of!(ShmidDs, shm_nattch) as u64,
        offset_of!(Sembuf, sem_num) as u64,
        offset_of!(Sembuf, sem_op) as u64,
        offset_of!(Sembuf, sem_flg) as u64,
        offset_of!(ShmidDs, perm) as u64,
        size_of::<SockaddrUn>() as u64,
        offset_of!(Seminfo, semmni) as u64,
        size_of::<c_long>() as u64,
        size_of::<usize>() as u64,
        IPC_PRIVATE as u64,
        IPC_64 as u64,
        MSG_EXCEPT as u64,
        MSG_COPY as u64,
        MSG_NOERROR as u64,
        IPC_CREAT as u64,
        IPC_EXCL as u64,
        IPC_NOWAIT as u64,
        IPC_RMID as u64,
        IPC_STAT as u64,
        MAX_SEMVAL as u64,
        MAX_MSG_SIZE as u64,
        MAX_SEMS as u64,
        MAX_NSEMS as u64,
        MAX_NSOPS as u64,
        PR_MUNMAP as u64,
        PR_SOCKET as u64,
        PR_GETPID as u64,
        PR_PPOLL as u64,
        PR_VOID as u64,
    ];
    for (index, value) in VALUES.iter().enumerate() {
        put_u64(out, index * 8, *value);
    }
    VALUES.len() as c_longlong
}

/// Port of `ra_msg_match()` (the original's static `sysvipc_msg_match`).
pub fn ra_msg_match(sender: c_longlong, filter: c_longlong, flags: c_longlong) -> c_longlong {
    i64::from(msg_match(sender as i32, filter as i32, flags as i32))
}

/// Port of `ra_msgget()`: key lookup, `IPC_CREAT`/`IPC_EXCL` rules and the
/// returned `(slot + 1) | (generation << 12)` id.
pub fn ra_msgget(id: c_longlong, flags: c_longlong, objects: &mut [u8]) -> c_longlong {
    let key = id as i32;
    let msgflg = flags as i32;
    let mut slots = parse_objs(objects);
    let (found, free) = find_slot(&slots, key);
    let index = match found {
        Some(index) => {
            if msgflg & IPC_CREAT != 0 && msgflg & IPC_EXCL != 0 {
                return EEXIST;
            }
            index
        }
        None => {
            if msgflg & IPC_CREAT == 0 {
                return ENOENT;
            }
            match free {
                Some(index) => index,
                None => {
                    slots.push(Obj {
                        key,
                        valid: true,
                        aux0: 64 * 1024, /* msg_qbytes: not enforced */
                        ..Obj::default()
                    });
                    let index = slots.len() - 1;
                    return object_id(index, slots[index].generation);
                }
            }
        }
    };
    if found.is_none() {
        slots[index].key = key;
        slots[index].valid = true;
        slots[index].aux0 = 64 * 1024;
    }
    for (slot, object) in slots.iter().enumerate() {
        if slot >= objects.len() / OBJ_LEN {
            break;
        }
        let at = slot * OBJ_LEN;
        put_i32(objects, at + 8, object.aux0);
        put_i32(objects, at + 12, object.aux1);
        put_i32(objects, at + 16, object.aux2);
        put_i32(objects, at + 20, object.aux3);
    }
    object_id(index, slots[index].generation)
}

/// Port of `ra_semget()`: `nsems` validation, slot reuse and the 512-object
/// cap; the newly created semaphore array starts all-zero.
pub fn ra_semget(
    key: c_longlong,
    nsems: c_longlong,
    flags: c_longlong,
    objects: &mut [u8],
) -> c_longlong {
    let key = key as i32;
    let nsems = nsems as i32;
    let semflg = flags as i32;
    if nsems <= 0 || nsems > MAX_NSEMS {
        return EINVAL;
    }
    let mut slots = parse_objs(objects);
    let (found, free) = find_slot(&slots, key);
    let index = match found {
        Some(index) => {
            if semflg & IPC_CREAT != 0 && semflg & IPC_EXCL != 0 {
                return EEXIST;
            }
            if slots[index].aux0 < nsems {
                return EINVAL;
            }
            index
        }
        None => {
            if semflg & IPC_CREAT == 0 {
                return ENOENT;
            }
            match free {
                Some(index) => index,
                None => {
                    if slots.len() as i32 >= MAX_SEMS {
                        return ENOSPC;
                    }
                    slots.push(Obj::default());
                    slots.len() - 1
                }
            }
        }
    };
    if found.is_none() {
        slots[index].key = key;
        slots[index].valid = true;
        slots[index].aux0 = nsems;
        slots[index].aux1 = 0;
        slots[index].aux2 = 0;
        slots[index].aux3 = 0;
    }
    for (slot, object) in slots.iter().enumerate() {
        if slot >= objects.len() / OBJ_LEN {
            break;
        }
        let at = slot * OBJ_LEN;
        put_i32(objects, at + 8, object.aux0);
        put_i32(objects, at + 12, object.aux1);
        put_i32(objects, at + 16, object.aux2);
        put_i32(objects, at + 20, object.aux3);
    }
    object_id(index, slots[index].generation)
}

/// Port of `ra_shmget()`: mode masking (`shmflg & 0777`), size, and the
/// create/reuse rules; the shm helper's fd is the harness' fixed `FAKE_FD`.
pub fn ra_shmget(
    key: c_longlong,
    size: c_longlong,
    flags: c_longlong,
    objects: &mut [u8],
) -> c_longlong {
    let key = key as i32;
    let size = size as u64;
    let shmflg = flags as i32;
    let mut slots = parse_objs(objects);
    let (found, free) = find_slot(&slots, key);
    let index = match found {
        Some(index) => {
            if shmflg & IPC_CREAT != 0 && shmflg & IPC_EXCL != 0 {
                return EEXIST;
            }
            let existing =
                (slots[index].aux0 as u32 as u64) | ((slots[index].aux1 as u32 as u64) << 32);
            if size != 0 && size != existing {
                return EINVAL;
            }
            index
        }
        None => {
            if shmflg & IPC_CREAT == 0 {
                return ENOENT;
            }
            match free {
                Some(index) => index,
                None => {
                    slots.push(Obj::default());
                    slots.len() - 1
                }
            }
        }
    };
    if found.is_none() {
        slots[index].key = key;
        slots[index].valid = true;
        slots[index].aux0 = size as u32 as i32;
        slots[index].aux1 = (size >> 32) as u32 as i32;
        slots[index].aux2 = shmflg & 0o777;
        slots[index].aux3 = FAKE_FD;
    }
    for (slot, object) in slots.iter().enumerate() {
        if slot >= objects.len() / OBJ_LEN {
            break;
        }
        let nattch = if object.valid && object.aux3 & 0xFFFF == FAKE_FD {
            object.aux3 >> 24
        } else {
            0
        };
        put_i32(objects, slot * OBJ_LEN + 8, object.aux0);
        put_i32(objects, slot * OBJ_LEN + 12, object.aux1);
        put_i32(objects, slot * OBJ_LEN + 16, object.aux2);
        put_i32(
            objects,
            slot * OBJ_LEN + 20,
            object.aux3 & 0xFFFF
                | (if object.aux3 & 0x1_0000 != 0 {
                    0x1_0000
                } else {
                    0
                })
                | nattch << 24,
        );
    }
    object_id(index, slots[index].generation)
}

/// Port of `ra_msgctl()`: IPC_RMID (plain and `IPC_64`) invalidates the queue
/// and bumps its generation; IPC_STAT is a no-op in the recording harness.
pub fn ra_msgctl(object_id: c_longlong, cmd: c_longlong, objects: &mut [u8]) -> c_longlong {
    let mut slots = parse_objs(objects);
    let index = match lookup(object_id, &slots) {
        Ok(index) => index,
        Err(status) => return status,
    };
    let cmd = cmd as i32;
    match cmd {
        x if x == IPC_RMID || x == (IPC_RMID | IPC_64) => {
            slots[index].valid = false;
            slots[index].generation = slots[index].generation.wrapping_add(1);
        }
        x if x == IPC_STAT || x == (IPC_STAT | IPC_64) => {}
        _ => return EINVAL,
    }
    0
}

/// Port of `ra_sem_check()`: atomic semop validation — zero/negative waits,
/// `IPC_NOWAIT` → EAGAIN, overflow → ERANGE, wait type 'z'/'n'.  The returned
/// low 32 bits are the status and the high byte is the wait type.
pub fn ra_sem_check(sems: &mut [u8], ops: &[u8], wait_type_in: c_longlong) -> c_longlong {
    let mut values: Vec<i32> = (0..sems.len() / 2)
        .map(|i| i16_at(sems, i * 2) as i32)
        .collect();
    let mut wait_type = wait_type_in as u8;
    let mut status = 0i32;
    let mut waiting = false;
    for chunk in ops.as_chunks::<8>().0 {
        let sem_num = i16_at(chunk, 0) as u16 as usize;
        let op = i16_at(chunk, 2) as i32;
        let flags = i16_at(chunk, 4) as i32;
        let Some(current) = values.get_mut(sem_num) else {
            continue;
        };
        if op == 0 {
            if *current != 0 {
                if flags & IPC_NOWAIT != 0 {
                    status = EAGAIN as i32;
                } else {
                    wait_type = b'z';
                    status = 1;
                    waiting = true;
                }
                break;
            }
        } else {
            let new_value = *current + op;
            if new_value < 0 {
                if flags & IPC_NOWAIT != 0 {
                    status = EAGAIN as i32;
                } else {
                    wait_type = b'n';
                    status = 1;
                    waiting = true;
                }
                break;
            }
            if new_value > MAX_SEMVAL {
                status = ERANGE as i32;
                break;
            }
            *current = new_value;
        }
    }
    if !waiting {
        for (index, value) in values.iter().enumerate() {
            put_i16(sems, index * 2, *value as i16);
        }
    }
    i64::from(status) | (i64::from(wait_type) << 32)
}

/// Port of `ra_semctl()`: GETVAL/SETVAL/GETALL, IPC_INFO/SEM_INFO, IPC_RMID
/// and the IPC_64-tolerant command decoding.
pub fn ra_semctl(
    semnum: c_longlong,
    cmd: c_longlong,
    cmdarg: c_longlong,
    sems: &mut [u8],
    out: &mut [u8],
) -> c_longlong {
    let mut slots = parse_objs(sems);
    if lookup(1, &slots).is_err() {
        for (slot, object) in slots.iter().enumerate() {
            if slot >= sems.len() / OBJ_LEN {
                break;
            }
            put_i32(sems, slot * OBJ_LEN + 8, object.aux0);
            if !object.valid {
                for at in [12, 16, 20] {
                    put_i32(sems, slot * OBJ_LEN + at, 0);
                }
            }
        }
        return EINVAL;
    }
    let index = 0usize;
    let semnum = semnum as i32;
    let cmd = cmd as i32;
    let base = cmd & !IPC_64;
    let mut info = [0i32; 10];
    match base {
        SYSVIPC_GETVAL => {
            if semnum < 0 || semnum >= slots[index].aux0 {
                return EINVAL;
            }
            return i64::from(sem(slots[index], semnum as usize) as i32);
        }
        SYSVIPC_SETVAL => {
            if cmdarg as u64 > MAX_SEMVAL as u64 {
                return ERANGE;
            }
            if semnum < 0 || semnum >= slots[index].aux0 {
                return EINVAL;
            }
            set_sem(&mut slots[index], semnum as usize, cmdarg as i32);
        }
        SYSVIPC_GETALL => {
            for sem_index in 0..slots[index].aux0.max(0) as usize {
                put_i16(out, sem_index * 2, sem(slots[index], sem_index));
            }
        }
        SYSVIPC_IPC_INFO | SYSVIPC_SEM_INFO => {
            info[1] = MAX_SEMS;
            info[2] = MAX_SEMS * MAX_NSEMS;
            info[4] = MAX_NSEMS;
            info[5] = MAX_NSOPS;
            info[8] = MAX_SEMVAL;
            if base == SYSVIPC_SEM_INFO {
                info[7] = slots.len() as i32;
                for slot in &slots {
                    info[9] += slot.aux0;
                }
            }
            for (at, value) in info.iter().enumerate() {
                put_i32(out, at * 4, *value);
            }
        }
        IPC_RMID => {
            slots[index].valid = false;
            slots[index].generation = slots[index].generation.wrapping_add(1);
            slots[index].aux1 = 0;
            slots[index].aux2 = 0;
            slots[index].aux3 = 0;
        }
        _ => return EINVAL,
    }
    for (slot, object) in slots.iter().enumerate() {
        if slot >= sems.len() / OBJ_LEN {
            break;
        }
        let at = slot * OBJ_LEN;
        put_i32(sems, at + 8, object.aux0);
        if object.valid {
            put_i32(sems, at + 12, object.aux1);
            put_i32(sems, at + 16, object.aux2);
            put_i32(sems, at + 20, object.aux3);
        } else {
            put_i32(sems, at + 12, 0);
            put_i32(sems, at + 16, 0);
            put_i32(sems, at + 20, 0);
        }
    }
    0
}

fn sem(object: Obj, semnum: usize) -> i16 {
    match semnum {
        0 => object.aux1 as i16,
        1 => object.aux2 as i16,
        2 => object.aux3 as i16,
        _ => 0,
    }
}

fn set_sem(object: &mut Obj, semnum: usize, value: i32) {
    match semnum {
        0 => object.aux1 = value,
        1 => object.aux2 = value,
        2 => object.aux3 = value,
        _ => {}
    }
}

/// Port of `ra_msgrcv_flags()`: flag validation and the wait decision for an
/// empty queue.  The state buffer holds `[wait_reason, waiting_object_index]`.
pub fn ra_msgrcv_flags(msgsz: c_longlong, msgflg: c_longlong, state_out: &mut [u8]) -> c_longlong {
    let flags = msgflg as i32;
    if let Err(status) = msgrcv_flags(msgsz, flags) {
        return status;
    }
    if flags & IPC_NOWAIT != 0 {
        ENOMSG
    } else {
        put_i32(state_out, 0, WR_WAIT_QUEUE_RECV);
        put_i32(state_out, 4, 0);
        0
    }
}

/// Port of `ra_msgrcv_deliver()`: MSG_COPY index selection, type/except
/// matching, E2BIG/MSG_NOERROR and queue-stat updates.  The output buffer ends
/// with `[qnum, cbytes, lrpid, wait_reason]`; before that it is the guest bytes
/// written by the delivery.
pub fn ra_msgrcv_deliver(
    items: &[u8],
    msgsz: c_longlong,
    msgtyp: c_longlong,
    msgflg: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    let mut messages: Vec<(i32, Vec<u8>)> = Vec::new();
    let mut at = 0usize;
    while at + 8 <= items.len() {
        let mtype = i32_at(items, at);
        let len = i32_at(items, at + 4);
        at += 8;
        if len < 0 || len as usize > items.len() - at {
            break;
        }
        messages.push((mtype, items[at..at + len as usize].to_vec()));
        at += len as usize;
    }
    let mut qnum = messages.len() as i32;
    let mut cbytes: i32 = messages.iter().map(|(_, text)| text.len() as i32).sum();
    let msgtyp = msgtyp as i32;
    let flags = msgflg as i32;
    let mut status = 0i64;
    let mut wait_reason = WR_NOT_WAITING;
    let mut delivered_type = 0i64;
    let mut delivered_lrpid = 0i32;
    let mut delivered_text: &[u8] = &[];
    let mut delivered = 0usize;
    match msgrcv_flags(msgsz, flags) {
        Ok(copy) => {
            let found = if copy {
                if msgtyp >= 0 {
                    usize::try_from(msgtyp)
                        .ok()
                        .filter(|index| *index < messages.len())
                } else {
                    None
                }
            } else {
                messages
                    .iter()
                    .position(|(mtype, _)| msg_match(*mtype, msgtyp, flags))
            };
            match found {
                None => {
                    if flags & IPC_NOWAIT != 0 {
                        status = ENOMSG;
                    } else {
                        wait_reason = WR_WAIT_QUEUE_RECV;
                    }
                }
                Some(index) => {
                    let (mtype, text) = &messages[index];
                    delivered = msgsz as usize;
                    if text.len() > delivered {
                        if flags & MSG_NOERROR == 0 {
                            status = E2BIG;
                        } else {
                            delivered_type = i64::from(*mtype);
                            delivered_text = text;
                            delivered_lrpid = FAKE_PID as i32;
                        }
                    } else {
                        delivered = text.len();
                        delivered_type = i64::from(*mtype);
                        delivered_text = text;
                        delivered_lrpid = FAKE_PID as i32;
                    }
                    if status == 0 {
                        if !copy {
                            qnum -= 1;
                            cbytes -= text.len() as i32;
                        }
                        status = delivered as i64;
                    }
                }
            }
        }
        Err(error) => status = error,
    }
    write_msgrcv_out(
        out,
        delivered_type,
        delivered_text,
        delivered,
        delivered_lrpid,
        qnum,
        cbytes,
        wait_reason,
    );
    status
}

#[expect(
    clippy::too_many_arguments,
    reason = "mirrors the C write_data + stats call with plain values"
)]
fn write_msgrcv_out(
    out: &mut [u8],
    mtype: i64,
    text: &[u8],
    delivered: usize,
    lrpid: i32,
    qnum: i32,
    cbytes: i32,
    wait_reason: i32,
) {
    let stats_at = out.len().saturating_sub(16);
    put_i64(out, 0, mtype);
    let copy_len = stats_at
        .min(16)
        .saturating_sub(8)
        .min(delivered)
        .min(text.len());
    if let Some(slot) = out.get_mut(8..8 + copy_len) {
        slot.copy_from_slice(&text[..copy_len]);
    }
    put_i32(out, stats_at, qnum);
    put_i32(out, stats_at + 4, cbytes);
    put_i32(out, stats_at + 8, lrpid);
    put_i32(out, stats_at + 12, wait_reason);
}

/// Port of `ra_shmctl()`: rmid is deferred while mappings exist, IPC_STAT
/// recomputes `shm_nattch`, and (as in the original) IPC_STAT|IPC_64 is an
/// error.  The output buffer starts with the 112-byte `SysVIpcShmidDs` bytes.
pub fn ra_shmctl(
    object_id: c_longlong,
    cmd: c_longlong,
    mappings: c_longlong,
    objects: &mut [u8],
    out: &mut [u8],
) -> c_longlong {
    let mut slots = parse_objs(objects);
    let index = match lookup(object_id, &slots) {
        Ok(index) => index,
        Err(status) => return status,
    };
    let cmd = cmd as i32;
    let attached = if index == 0 {
        mappings.clamp(0, 8) as u64
    } else {
        0
    };
    let mut rmid_pending = slots[index].aux3 & 0x1_0000 != 0;
    match cmd {
        x if x == IPC_RMID || x == (IPC_RMID | IPC_64) => {
            if attached == 0 {
                slots[index].valid = false;
                slots[index].generation = (i32::from(slots[index].generation) + 1) as i16;
                slots[index].aux3 = -1;
                rmid_pending = false;
            } else {
                rmid_pending = true;
            }
        }
        IPC_STAT => {
            let object = slots[index];
            let mut stats = [0u8; 112];
            put_i32(&mut stats, 20, object.aux2); /* shm_perm.mode */
            let size = (object.aux0 as u32 as u64) | ((object.aux1 as u32 as u64) << 32);
            put_u64(&mut stats, 48, size);
            put_u64(&mut stats, 88, attached);
            for (at, byte) in stats.iter().enumerate() {
                if let Some(slot) = out.get_mut(at) {
                    *slot = *byte;
                }
            }
        }
        _ => return EINVAL,
    }
    for (slot, object) in slots.iter().enumerate() {
        if slot >= objects.len() / OBJ_LEN {
            break;
        }
        let nattch = if slot == 0 { attached as i32 } else { 0 };
        put_i32(objects, slot * OBJ_LEN + 8, object.aux0);
        put_i32(objects, slot * OBJ_LEN + 12, object.aux1);
        put_i32(objects, slot * OBJ_LEN + 16, object.aux2);
        put_i32(
            objects,
            slot * OBJ_LEN + 20,
            object.aux3 & 0xFFFF
                | (if rmid_pending && slot == index {
                    0x1_0000
                } else {
                    0
                })
                | nattch << 24,
        );
    }
    0
}

/// Port of `ra_shmdt()`: find the mapping by address (the harness inserts at
/// the list head) and report the munmap rewriting through the state buffer
/// `[status, sysnum, new_arg2]`.
pub fn ra_shmdt(addr: c_longlong, mappings: &[u8], out: &mut [u8]) -> c_longlong {
    let mut status = EINVAL;
    let mut sysnum = PR_VOID;
    let mut size = 0u64;
    for chunk in mappings.as_chunks::<16>().0.iter().rev() {
        if u64_at(chunk, 0) == addr as u64 {
            status = 0;
            sysnum = PR_MUNMAP;
            size = i32_at(chunk, 8) as u32 as u64;
            break;
        }
    }
    put_i32(out, 0, status as i32);
    put_i32(out, 4, sysnum);
    put_u64(out, 8, size);
    status
}

/// Port of `ra_shm_recvmsg_pointers()`: the address arithmetic that builds
/// the recvmsg/msghdr/cmsg layout in guest memory for shmat.
pub fn ra_shm_recvmsg_pointers(
    is32: c_longlong,
    guest_buf: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    let ptr_len = if is32 != 0 { 4u64 } else { 8u64 };
    let buf_end = (guest_buf as u64).wrapping_add(size_of::<SockaddrUn>() as u64);
    let data_addr = buf_end.wrapping_sub(4);
    let data_iov_length = data_addr.wrapping_sub(ptr_len);
    let data_iov_addr = data_iov_length.wrapping_sub(ptr_len);
    let msghdr_flags = data_iov_addr.wrapping_sub(ptr_len);
    let msghdr_controllen = msghdr_flags.wrapping_sub(ptr_len);
    let msghdr_control = msghdr_controllen.wrapping_sub(ptr_len);
    let msghdr_iovlen = msghdr_control.wrapping_sub(ptr_len);
    let msghdr_iov = msghdr_iovlen.wrapping_sub(ptr_len);
    let msghdr = msghdr_iov.wrapping_sub(ptr_len * 2);
    put_u64(out, 0, msghdr);
    put_u64(out, 8, msghdr_controllen);
    put_u64(out, 16, guest_buf as u64);
    0
}

/// Port of `ra_wake()`: `sysvipc_wake_tracee()`'s reason/state transition.
pub fn ra_wake(
    reason: c_longlong,
    state: c_longlong,
    status: c_longlong,
    out: &mut [u8],
) -> c_longlong {
    let wait_reason = WR_NOT_WAITING;
    let mut wait_state = state as i32;
    match state as i32 {
        WSTATE_ENTERED_PPOLL => wait_state = WSTATE_SIGNALED_PPOLL,
        WSTATE_RESTARTED_INTO_PPOLL => wait_state = WSTATE_RESTARTED_INTO_PPOLL_CANCELED,
        _ => {}
    }
    let _ = reason; /* the original asserts it is not WR_NOT_WAITING */
    put_i32(out, 0, wait_reason);
    put_i32(out, 4, wait_state);
    put_u64(out, 8, status as u64);
    0
}

/// Port of `ra_shm_fill_proc()`: the exact `/proc/sysvipc/shm` text layout,
/// including the original's `& ~page_size` rounding quirk.
pub fn ra_shm_fill_proc(objects: &[u8], page_size: c_longlong, out: &mut [u8]) -> c_longlong {
    let mut text = String::from(
        "       key      shmid perms                  size  cpid  lpid nattch   uid   gid  cuid  cgid      atime      dtime      ctime                   rss                  swap\n",
    );
    let page = page_size as u64;
    let records = objects.len() / PROC_LEN;
    for index in 0..records {
        let b = &objects[index * PROC_LEN..(index + 1) * PROC_LEN];
        if i16_at(b, 6) == 0 {
            continue;
        }
        let key = i32_at(b, 0);
        let generation = i16_at(b, 4);
        let mode = i32_at(b, 8) as u32;
        let cpid = i32_at(b, 12) as u32;
        let lpid = i32_at(b, 16) as u32;
        let nattch = i32_at(b, 20) as u32;
        let size = u64_at(b, 24);
        let atime = u64_at(b, 32);
        let dtime = u64_at(b, 40);
        let ctime = u64_at(b, 48);
        let uid = i32_at(b, 56) as u32;
        let gid = i32_at(b, 60) as u32;
        let cuid = i32_at(b, 64) as u32;
        let cgid = i32_at(b, 68) as u32;
        let map_size = size.wrapping_add(page.wrapping_sub(1)) & !page;
        let zero = 0u64;
        text.push_str(&format!(
            "{key:10} {:10}  {mode:4o} {size:21} {cpid:5} {lpid:5}  {nattch:5} {uid:5} {gid:5} {cuid:5} {cgid:5} {atime:10} {dtime:10} {ctime:10} {map_size:21} {zero:21}\n",
            (index + 1) as i32 | ((generation as i32) << 12)
        ));
    }
    let bytes = text.as_bytes();
    let n = bytes.len().min(out.len());
    out[..n].copy_from_slice(&bytes[..n]);
    n as c_longlong
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ll(value: i32) -> c_longlong {
        value as c_longlong
    }

    #[test]
    fn layout_matches_the_recorded_c_structs() {
        let mut bytes = [0u8; 320];
        assert_eq!(ra_layout(&mut bytes), 36);
        for (index, expected) in [
            6usize, 40, 112, 120, 48, 48, 56, 88, 0, 2, 4, 0, 110, 4, 8, 8, 0, 256,
        ]
        .iter()
        .enumerate()
        {
            assert_eq!(u64_at(&bytes, index * 8), *expected as u64);
        }
    }

    #[test]
    fn msg_match_inverts_on_except() {
        assert!(msg_match(5, 0, 0));
        assert!(msg_match(1, -5, 0));
        assert!(!msg_match(6, -5, 0));
        assert!(!msg_match(5, 0, MSG_EXCEPT));
        assert!(msg_match(7, 5, MSG_EXCEPT));
    }

    fn obj(key: i32, generation: i16, valid: bool, aux0: i32) -> [u8; OBJ_LEN] {
        let mut b = [0u8; OBJ_LEN];
        put_i32(&mut b, 0, key);
        put_i16(&mut b, 4, generation);
        put_i16(&mut b, 6, i16::from(valid));
        put_i32(&mut b, 8, aux0);
        b
    }

    #[test]
    fn msgget_creates_reuses_and_rejects_existing() {
        let mut empty = [0u8; 0];
        assert_eq!(ra_msgget(0, ll(IPC_CREAT), &mut empty), 1i64);
        let mut slots = obj(42, 0, true, 64 * 1024);
        assert_eq!(ra_msgget(42, 0, &mut slots), 1);
        assert_eq!(ra_msgget(42, ll(IPC_CREAT | IPC_EXCL), &mut slots), EEXIST);
        assert_eq!(ra_msgget(42, 0, &mut []), ENOENT);
        assert_eq!(lookup(0x1001, &parse_objs(&slots)), Err(EINVAL));
    }

    #[test]
    fn semget_validates_nsems_and_generation() {
        assert_eq!(ra_semget(0, 0, ll(IPC_CREAT), &mut []), EINVAL);
        assert_eq!(ra_semget(0, 513, ll(IPC_CREAT), &mut []), EINVAL);
        let mut slots = obj(5, 0, true, 4);
        assert_eq!(ra_semget(5, 5, 0, &mut slots), EINVAL);
        assert_eq!(ra_semget(5, 3, 0, &mut slots), 1);
    }

    #[test]
    fn shmget_masks_mode_and_checks_size() {
        let mut slots = [0u8; 0];
        assert_eq!(ra_shmget(0, 4096, ll(IPC_CREAT | 0o7777), &mut slots), 1);
        let mut existing = obj(7, 0, true, 4096);
        put_i32(&mut existing, 16, 0o644);
        assert_eq!(ra_shmget(7, 8192, 0, &mut existing), EINVAL);
        assert_eq!(ra_shmget(7, 0, 0, &mut existing), 1);
    }

    #[test]
    fn semop_waits_and_ranges() {
        let mut sems = [5i16.to_le_bytes(), 7i16.to_le_bytes()].concat();
        let mut enough = sems.clone();
        assert_eq!(
            ra_sem_check(&mut enough, &[0, 0, 2, 0, 0, 0, 0, 0], 0) as i32,
            0
        );
        assert_eq!(i16_at(&enough, 0), 7);
        let ret = ra_sem_check(&mut sems, &[1, 0, 0, 0, 0, 0, 0, 0], b'x' as i64);
        assert_eq!(ret as i32, 1);
        assert_eq!((ret >> 32) as u8, b'z');
        let mut nowait = sems.clone();
        assert_eq!(
            ra_sem_check(&mut nowait, &[1, 0, 0, 0, 0, 8, 0, 0], 0) as i32,
            EAGAIN as i32
        );
        let mut overflow = sems.clone();
        assert_eq!(
            ra_sem_check(&mut overflow, &[0, 0, 0, 120, 0, 0, 0, 0], 0) as i32,
            ERANGE as i32
        );
    }

    #[test]
    fn semctl_decodes_ipc64_and_info() {
        let mut slots = obj(5, 0, true, 3);
        put_i32(&mut slots, 12, 5);
        put_i32(&mut slots, 16, 7);
        put_i32(&mut slots, 20, 9);
        assert_eq!(
            ra_semctl(1, ll(SYSVIPC_GETVAL | IPC_64), 0, &mut slots, &mut [0; 64]),
            7
        );
        assert_eq!(
            ra_semctl(
                1,
                ll(SYSVIPC_SETVAL),
                MAX_SEMVAL as i64 + 1,
                &mut slots,
                &mut [0; 64]
            ),
            ERANGE
        );
        let mut out = [0u8; 64];
        assert_eq!(
            ra_semctl(0, ll(SYSVIPC_SEM_INFO), 0, &mut slots, &mut out),
            0
        );
        assert_eq!(i32_at(&out, 7 * 4), 1);
        assert_eq!(i32_at(&out, 9 * 4), 3);
    }

    #[test]
    fn msgrcv_rejects_bad_flag_combinations() {
        let mut state = [0u8; 8];
        assert_eq!(ra_msgrcv_flags(-1, 0, &mut state), EINVAL);
        assert_eq!(ra_msgrcv_flags(10, 1, &mut state), EINVAL);
        assert_eq!(
            ra_msgrcv_flags(10, ll(MSG_COPY | IPC_NOWAIT), &mut state),
            ENOMSG
        );
        assert_eq!(ra_msgrcv_flags(10, 0, &mut state), 0);
        assert_eq!(i32_at(&state, 0), WR_WAIT_QUEUE_RECV);
    }

    #[test]
    fn recvmsg_pointers_use_word_width() {
        let mut out = [0u8; 24];
        ra_shm_recvmsg_pointers(0, 0x10000, &mut out);
        assert_eq!(u64_at(&out, 0), 0x10022);
        assert_eq!(u64_at(&out, 8), 0x1004a);
        ra_shm_recvmsg_pointers(1, 0x10000, &mut out);
        assert_eq!(u64_at(&out, 0), 0x10046);
        assert_eq!(u64_at(&out, 8), 0x1005a);
    }

    #[test]
    fn wake_moves_ppoll_states() {
        let mut out = [0u8; 16];
        ra_wake(1, ll(WSTATE_ENTERED_PPOLL), -11, &mut out);
        assert_eq!(i32_at(&out, 4), WSTATE_SIGNALED_PPOLL);
        ra_wake(2, ll(WSTATE_RESTARTED_INTO_PPOLL), 0, &mut out);
        assert_eq!(i32_at(&out, 4), WSTATE_RESTARTED_INTO_PPOLL_CANCELED);
    }

    #[test]
    fn shmdt_rewrites_to_munmap() {
        let mut mappings = [0u8; 16];
        put_u64(&mut mappings, 0, 0x2000);
        put_i32(&mut mappings, 8, 4096);
        let mut out = [0u8; 16];
        assert_eq!(ra_shmdt(0x2000, &mappings, &mut out), 0);
        assert_eq!(i32_at(&out, 4), PR_MUNMAP);
        assert_eq!(u64_at(&out, 8), 4096);
        assert_eq!(ra_shmdt(0x9000, &mappings, &mut out), EINVAL);
    }

    #[test]
    fn fill_proc_keeps_the_c_format() {
        let mut record = [0u8; PROC_LEN];
        put_i32(&mut record, 0, 5);
        put_i16(&mut record, 6, 1);
        put_i32(&mut record, 8, 0o644);
        put_i32(&mut record, 12, 100);
        put_i32(&mut record, 20, 2);
        put_u64(&mut record, 24, 5000);
        let mut out = [0u8; 4096];
        let n = ra_shm_fill_proc(&record, 4096, &mut out);
        let text = std::str::from_utf8(&out[..n as usize]).unwrap_or("");
        assert!(text.starts_with("       key      shmid perms"));
        assert!(text.contains("         5          1   644                  5000"));
        assert!(text.contains("                  9095"));
    }
}
