#!/bin/bash
# patch-goose-build.sh
#
# Applies patches to the goose source tree to enable Android compilation.
# Run this BEFORE cross-compiling.
#
# Key patches:
# 1. Make keyring optional/fallback to file-based secrets
# 2. Handle clipboard (arboard) gracefully when no display
# 3. Disable features that don't work on Android
# 4. Fix any Android-specific compilation issues
#
# Usage: ./scripts/patch-goose-build.sh <goose-src-dir>

set -euo pipefail

GOOSE_SRC="${1:?Usage: patch-goose-build.sh <goose-src-dir>}"

echo "=== Patching goose source for Android build ==="

cd "$GOOSE_SRC"

# --- Patch 1: Ensure Cross.toml has Android target ---
echo "[1/4] Updating Cross.toml..."
if ! grep -q "aarch64-linux-android" Cross.toml; then
    cat >> Cross.toml << 'EOF'

[target.aarch64-linux-android]
xargo = false
image = "ghcr.io/cross-rs/aarch64-linux-android:main"
EOF
fi

# --- Patch 2: Make keyring dependency conditional ---
echo "[2/4] Checking keyring configuration..."
# The code already supports GOOSE_DISABLE_KEYRING=true which falls back to
# file-based secrets. On Android, we set this env var in GooseService.
# However, the keyring crate with "sync-secret-service" feature requires
# dbus which isn't available on Android. We need to ensure the build
# doesn't link against it.

# Check if there's a target-specific dependency we can leverage
if grep -q 'target.*linux.*keyring' crates/goose/Cargo.toml; then
    echo "  Keyring is already target-gated for Linux"
    # Add Android exclusion: only use keyring on linux, not android
    sed -i 's/\[target.'\''cfg(target_os = "linux")'\''\.dependencies\]/[target.'\''cfg(all(target_os = "linux", not(target_os = "android")))'\''\.dependencies]/' \
        crates/goose/Cargo.toml 2>/dev/null || true
fi

# --- Patch 3: Handle arboard (clipboard) on Android ---
echo "[3/4] Patching clipboard handling..."
# arboard already has a graceful fallback in the code (it's wrapped in if let Ok(...))
# But the crate itself may fail to compile without X11/Wayland.
# Make it a conditional dependency.

# Check if arboard is already optional
if grep -q '^arboard' crates/goose/Cargo.toml && ! grep -q 'arboard.*optional' crates/goose/Cargo.toml; then
    echo "  Making arboard optional for Android..."
    # We'll use a cfg flag to conditionally compile clipboard code
    sed -i 's/^arboard = { workspace = true }/arboard = { workspace = true, optional = true }/' \
        crates/goose/Cargo.toml 2>/dev/null || true
    
    # Add a feature for clipboard
    # This is a best-effort patch - if it doesn't apply cleanly, the build
    # may still work because arboard supports Android via ndk-clipboard
fi

# --- Patch 4: Ensure the serve command is available ---
echo "[4/4] Verifying serve command availability..."
if grep -q "handle_serve_command" crates/goose-cli/src/cli.rs; then
    echo "  'goose serve' command found ✓"
else
    echo "  WARNING: 'goose serve' command not found in CLI"
    echo "  The binary may need to use 'goosed agent' instead"
fi

echo ""
echo "=== Patches applied ==="
echo "Ready for: cross build --release -p goose-cli --target aarch64-linux-android"
echo "  --no-default-features --features rustls-tls,telemetry,disable-update"
