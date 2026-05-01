#!/bin/bash
# patch-frontend.sh
#
# Patches the goose2 frontend build for Android WebView:
# 1. Injects platform-shim.js before other scripts
# 2. Removes any Block/Cash-specific references
# 3. Ensures the build works in a file:// context
#
# Usage: ./scripts/patch-frontend.sh <goose-src-dir> <frontend-dir>

set -euo pipefail

GOOSE_SRC="${1:?Usage: patch-frontend.sh <goose-src-dir> <frontend-dir>}"
FRONTEND_DIR="${2:?Usage: patch-frontend.sh <goose-src-dir> <frontend-dir>}"

echo "=== Patching goose2 frontend for Android ==="

GOOSE2_DIR="$GOOSE_SRC/ui/goose2"
DIST_DIR="$GOOSE2_DIR/dist"

# --- Step 1: Copy platform shim into dist ---
echo "[1/4] Copying platform shim..."
cp "$FRONTEND_DIR/platform-shim.js" "$DIST_DIR/platform-shim.js"

# --- Step 2: Inject shim script tag into index.html ---
echo "[2/4] Injecting platform shim into index.html..."
# Insert the shim script as the FIRST script in <head>
sed -i 's|<head>|<head><script src="platform-shim.js"></script>|' "$DIST_DIR/index.html"

# --- Step 3: Fix base path for file:// loading ---
echo "[3/4] Fixing asset paths for file:// context..."
# Replace absolute paths with relative paths
sed -i 's|href="/|href="./|g' "$DIST_DIR/index.html"
sed -i 's|src="/|src="./|g' "$DIST_DIR/index.html"

# Also fix any JS/CSS files that reference absolute paths
find "$DIST_DIR" -name "*.js" -exec sed -i 's|"/assets/|"./assets/|g' {} \;
find "$DIST_DIR" -name "*.css" -exec sed -i 's|url(/|url(./|g' {} \;

# --- Step 4: Remove any internal/corporate references ---
echo "[4/4] Cleaning corporate references..."
# Remove Snowflake-specific code if present
find "$DIST_DIR" -name "*.js" -exec sed -i \
    -e 's|snowflake[^"]*||gi' \
    -e 's|block\.xyz[^"]*||gi' \
    {} \; 2>/dev/null || true

echo "=== Frontend patching complete ==="
echo "Output: $DIST_DIR"
ls -la "$DIST_DIR/index.html"
