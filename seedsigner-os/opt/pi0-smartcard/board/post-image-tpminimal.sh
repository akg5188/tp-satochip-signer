#!/bin/bash

set -e

sectors_to_blocks() {
  echo $((("$1" * 512) / 1024))
}

sectors_to_bytes() {
  echo $(( "$1" * 512 ))
}

export disk_timestamp="2023/01/01T12:15:05"

echo "*****Generating Main System Image*****"

rm -rf "${BUILD_DIR}/custom_image"
mkdir -p "${BUILD_DIR}/custom_image"
cd "${BUILD_DIR}/custom_image"

truncate -s 512M disk.img

/sbin/sfdisk disk.img <<EOF
  label: dos
  label-id: 0xba5eba11

  disk.img1 : type=c, bootable
EOF

START=$(/sbin/fdisk -l -o Start disk.img | awk 'END{gsub(/^ +| +$/, "", $1); print $1}')
SECTORS=$(/sbin/fdisk -l -o Sectors disk.img | awk 'END{gsub(/^ +| +$/, "", $1); print $1}')
/sbin/mkfs.vfat --invariant -i ba5eba11 -n TPSIGNEROS disk.img --offset "$START" "$(sectors_to_blocks "$SECTORS")"
OFFSET=$(sectors_to_bytes "$START")

mkdir -p boot overlays
cp "${BASE_DIR}/images/rpi-firmware/cmdline.txt" boot/cmdline.txt
cp "${BASE_DIR}/images/rpi-firmware/config.txt" boot/config.txt
cp "${BASE_DIR}/images/rpi-firmware/bootcode.bin" boot/bootcode.bin
cp "${BASE_DIR}/images/rpi-firmware/fixup_x.dat" boot/fixup_x.dat
cp "${BASE_DIR}/images/rpi-firmware/start_x.elf" boot/start_x.elf
cp "${BASE_DIR}/images/rpi-firmware/overlays/"* overlays/
cp "${BASE_DIR}/images/"*.dtb boot/
cp "${BASE_DIR}/images/zImage" boot/zImage

chmod 0755 boot overlays
touch -d "${disk_timestamp}" boot/* overlays/*

mcopy -bpm -i "disk.img@@$OFFSET" boot/* ::
mcopy -bpm -i "disk.img@@$OFFSET" overlays/* ::overlays

mv disk.img "${BASE_DIR}/images/seedsigner_os.img"
