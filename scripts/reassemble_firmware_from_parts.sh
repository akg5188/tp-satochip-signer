#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"

cd "$DIST_DIR"

cat system-update-latest.img.xz.part-* > system-update-latest.img.xz
sha256sum -c system-update-latest.img.xz.sha256
echo "$DIST_DIR/system-update-latest.img.xz"
