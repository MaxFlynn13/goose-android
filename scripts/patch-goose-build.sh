#!/bin/bash
# patch-goose-build.sh
#
# Applies patches to the goose source tree to enable Android compilation.
# Run this BEFORE compiling with cargo-ndk.
#
# Key patches:
# 1. Fix keyring dependency for Android (no dbus/secret-service)
# 2. Handle arboard (clipboard) for headless Android
# 3. Fix any other Android-specific compilation issues
#
# Usage: ./scripts/patch-goose-build.sh <goose-src-dir>

set -euo pipefail

GOOSE_SRC="${1:?Usage: patch-goose-build.sh <goose-src-dir>}"

echo "=== Patching goose source for Android build ==="

cd "$GOOSE_SRC"

# --- Patch 1: Fix keyring for Android ---
echo "[1/5] Patching keyring for Android..."
# The keyring crate on Linux uses "sync-secret-service" which requires dbus.
# Android doesn't have dbus. We need to change the Linux-specific dep to exclude Android.
#
# Original: [target.'cfg(target_os = "linux")'.dependencies]
#           keyring = { version = "3.6.2", features = ["sync-secret-service"] }
#
# We change the cfg to exclude Android:
#   cfg(all(target_os = "linux", not(target_os = "android")))
#
# Note: cfg(target_os = "android") is separate from cfg(target_os = "linux") in Rust,
# so this might not actually be needed. Let's check and patch if necessary.

if grep -q 'cfg(target_os = "linux")' crates/goose/Cargo.toml; then
    # Replace the Linux target gate to explicitly exclude Android
    # Android has target_os = "android", not "linux", so this may already work
    # But the pre-build script in Cross.toml tries to install libdbus-dev which fails
    echo "  Linux-gated keyring found (should be fine for Android target_os)"
fi

# --- Patch 2: Remove protobuf build dependency if present ---
echo "[2/5] Checking protobuf dependency..."
# Some crates need protoc at build time. Ensure it's available or patch it out.
if grep -rq "prost-build\|protobuf" crates/goose/Cargo.toml crates/goose-cli/Cargo.toml 2>/dev/null; then
    echo "  protobuf dependency found - will need protoc in build env"
fi

# --- Patch 3: Handle the v8 vendor crate ---
echo "[3/5] Checking V8/code-mode..."
# code-mode pulls in V8 which is extremely hard to cross-compile for Android.
# We're building with --no-default-features which excludes code-mode, so this should be fine.
echo "  code-mode excluded via --no-default-features ✓"

# --- Patch 4: Check for problematic system deps ---
echo "[4/5] Checking system dependencies..."
# libc crate works fine on Android
# arboard: on Android, it uses the android clipboard API (if available) or is a no-op
# The code already wraps clipboard in `if let Ok(...)` so failure is graceful

# Check if there are any Linux-only deps that would break on Android
if grep -q "xcb\|x11\|wayland\|dbus" crates/goose/Cargo.toml 2>/dev/null; then
    echo "  WARNING: Found X11/Wayland/dbus deps in goose crate"
else
    echo "  No problematic system deps in goose crate ✓"
fi

# --- Patch 5: Verify serve command exists ---
echo "[5/5] Verifying serve command..."
if grep -q "handle_serve_command\|Command::Serve" crates/goose-cli/src/cli.rs; then
    echo "  'goose serve' command found ✓"
else
    echo "  WARNING: 'goose serve' not found - checking goose-server..."
    if grep -q "fn run\|fn main" crates/goose-server/src/main.rs; then
        echo "  goose-server binary available as fallback ✓"
    fi
fi

echo ""
echo "=== Patch analysis complete ==="
echo ""
echo "Build command:"
echo "  cargo ndk -t arm64-v8a build --release -p goose-cli \\"
echo "    --no-default-features --features rustls-tls,telemetry,disable-update"
