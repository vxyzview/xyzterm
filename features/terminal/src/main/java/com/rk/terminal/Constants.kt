package com.rk.terminal

private const val ROOTFS_BASE = "https://github.com/Xed-Editor/Karbon-PackagesX/releases/download/ubuntu"

const val ROOTFS_ARM = "$ROOTFS_BASE/ubuntu-base-24.04.3-base-armhf.tar.gz"
const val ROOTFS_ARM64 = "$ROOTFS_BASE/ubuntu-base-24.04.3-base-arm64.tar.gz"
const val ROOTFS_X64 = "$ROOTFS_BASE/ubuntu-base-24.04.3-base-amd64.tar.gz"

// SHA-256 of each release asset (from the GitHub release API). When the URL
// constants are bumped to a new rootfs release, update these alongside them.
const val ROOTFS_ARM_SHA256 = "747909a2f81d816fc6252f076757fcf6bd75a55f848a1c049ee79c0e88c0b9a0"
const val ROOTFS_ARM64_SHA256 = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
const val ROOTFS_X64_SHA256 = "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"
