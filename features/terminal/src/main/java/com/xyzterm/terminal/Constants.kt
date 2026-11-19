package com.xyzterm.terminal

private const val ROOTFS_BASE = "https://github.com/vxyzview/xyzterm/releases/download/arch-rootfs"

const val ROOTFS_ARM = "$ROOTFS_BASE/archlinux-armv7.tar.gz"
const val ROOTFS_ARM64 = "$ROOTFS_BASE/archlinux-aarch64.tar.gz"
const val ROOTFS_X64 = "$ROOTFS_BASE/archlinux-x86_64.tar.gz"

// SHA-256 of each release asset (from the GitHub release API). When the URL
// constants are bumped to a new rootfs release, update these alongside them.
const val ROOTFS_ARM_SHA256 = "603fa393159e313552839eb858a1c9092a7f31e2173df255186694e975f1907c"
const val ROOTFS_ARM64_SHA256 = "67e0ace557c7b8e108b57498c491b9ce41eb9d99ac1158f2db2aca356c83fccf"
const val ROOTFS_X64_SHA256 = "ffd226e6b36e52e2aeb1fb8bf29203eecbf690b29548d0dc5d0fbd8baf0c6aff"
