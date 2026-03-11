#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

SIGNER_IMPL="${SIGNER_IMPL:-python}"

source_dir=""
if [[ "$SIGNER_IMPL" == "java" ]]; then
  source_dir="$ROOT_DIR/pi-signer/build/release/tp-pi-signer"
  if [[ ! -d "$source_dir" || "${FORCE_BUILD:-0}" == "1" ]]; then
    ./gradlew :pi-signer:assembleReleaseBundle
  fi
elif [[ "$SIGNER_IMPL" == "python" ]]; then
  source_dir="$ROOT_DIR/pi-signer-py"
  if [[ ! -x "$source_dir/bin/pi-signer" ]]; then
    echo "Python signer not found: $source_dir/bin/pi-signer" >&2
    exit 1
  fi
else
  echo "Unknown SIGNER_IMPL: $SIGNER_IMPL (supported: python, java)" >&2
  exit 1
fi

stamp="$(date +%Y%m%d-%H%M%S)"
release_name="tp-pi-signer-${stamp}"
release_dir="$ROOT_DIR/dist/${release_name}"

rm -rf "$release_dir"
mkdir -p "$ROOT_DIR/dist"
mkdir -p "$release_dir/pi-signer"
cp -a "$source_dir"/. "$release_dir/pi-signer/"

mkdir -p "$release_dir/pi-appliance"
cp -a "$ROOT_DIR/pi-appliance/app" "$release_dir/pi-appliance/"
cp -a "$ROOT_DIR/pi-appliance/setup" "$release_dir/pi-appliance/"
cp -a "$ROOT_DIR/pi-appliance/systemd" "$release_dir/pi-appliance/"
cp "$ROOT_DIR/pi-appliance/README.zh-CN.md" "$release_dir/pi-appliance/"
find "$release_dir" -type d -name "__pycache__" -prune -exec rm -rf {} +

mkdir -p "$release_dir/image-builder"
cp "$ROOT_DIR/image-builder/build_flashable_image.sh" "$release_dir/image-builder/"
cp "$ROOT_DIR/image-builder/README.zh-CN.md" "$release_dir/image-builder/"

tarball="$ROOT_DIR/dist/${release_name}.tar.gz"
rm -f "$tarball"
tar -C "$ROOT_DIR/dist" -czf "$tarball" "$release_name"

echo "Release directory: $release_dir"
echo "Release tarball:   $tarball"
