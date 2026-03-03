# Prebuilt CAP Firmware

This folder contains a prebuilt CAP file for direct installation when you do not modify applet source.

- File: `SatoChip-3.0.4.cap`
- SHA256: `ded5a2efbb8a417109a629dd0e0e09711e1ea2c90da4bab79e719cef9f933ec7`

## Install example

```bash
java -jar gp.jar -install SatoChip-3.0.4.cap
```

## Verify example

```bash
sha256sum -c SHA256SUMS.txt
opensc-tool -s 00A40400085361746F43686970
```

If `SELECT AID` returns `SW1=0x90, SW2=0x00`, applet is selectable.
