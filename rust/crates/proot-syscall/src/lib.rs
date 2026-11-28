//! Syscall number ↔ name lookup for the guest architectures supported by
//! XYZTerm. Tables are generated from the PRoot headers shipped with the app.

mod tables;

pub use tables::{ARM, ARM64};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Abi {
    Arm,
    Arm64,
}

impl Abi {
    /// ABI of the host running this code, from `std::env::consts::ARCH`.
    pub fn host() -> Option<Self> {
        match std::env::consts::ARCH {
            "arm" => Some(Self::Arm),
            "aarch64" => Some(Self::Arm64),
            _ => None,
        }
    }

    pub fn table(self) -> &'static [(u32, &'static str)] {
        match self {
            Self::Arm => ARM,
            Self::Arm64 => ARM64,
        }
    }
}

pub fn name(abi: Abi, number: u32) -> Option<&'static str> {
    abi.table()
        .binary_search_by_key(&number, |entry| entry.0)
        .ok()
        .map(|index| abi.table()[index].1)
}

pub fn number(abi: Abi, name: &str) -> Option<u32> {
    abi.table()
        .iter()
        .find(|entry| entry.1 == name)
        .map(|entry| entry.0)
}

/// Syscalls PRoot must never pass through unmodified because they change
/// identity, filesystem namespace or process topology.
pub fn is_privileged(abi: Abi, number: u32) -> bool {
    let Some(name) = name(abi, number) else {
        return false;
    };
    matches!(
        name,
        "setuid"
            | "setgid"
            | "seteuid"
            | "setegid"
            | "setreuid"
            | "setregid"
            | "setresuid"
            | "setresgid"
            | "chroot"
            | "mount"
            | "umount2"
            | "ptrace"
            | "clone"
            | "fork"
            | "vfork"
            | "execve"
            | "execveat"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tables_are_sorted_and_unique() {
        for table in [ARM, ARM64] {
            for pair in table.windows(2) {
                assert!(pair[0].0 < pair[1].0, "unsorted syscall table");
            }
        }
    }

    #[test]
    fn known_arm_numbers() {
        assert_eq!(name(Abi::Arm, 3), Some("read"));
        assert_eq!(name(Abi::Arm, 4), Some("write"));
        assert_eq!(name(Abi::Arm, 11), Some("execve"));
        assert_eq!(number(Abi::Arm, "execve"), Some(11));
    }

    #[test]
    fn known_arm64_numbers() {
        assert_eq!(name(Abi::Arm64, 63), Some("read"));
        assert_eq!(name(Abi::Arm64, 64), Some("write"));
        assert_eq!(name(Abi::Arm64, 221), Some("execve"));
        assert_eq!(number(Abi::Arm64, "execve"), Some(221));
    }

    #[test]
    fn privileged_detection() {
        assert!(is_privileged(Abi::Arm, 11));
        assert!(is_privileged(Abi::Arm64, 221));
        assert!(!is_privileged(Abi::Arm64, 63));
        assert!(!is_privileged(Abi::Arm64, 9999));
    }

    #[test]
    fn host_abi_is_one_of_the_targets_on_android() {
        assert!(Abi::host().is_some(), "test host must be arm or aarch64");
    }
}
