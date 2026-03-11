#!/usr/bin/env python3
import array
import errno
import time

from PIL import Image

from periphery import GPIO, SPI


class ST7789Display:
    """240x240 ST7789 driver aligned with SeedSigner's known-good path."""

    def __init__(
        self,
        width: int = 240,
        height: int = 240,
        spi_bus: int = 0,
        spi_dev: int = 0,
        dc_pin: int = 25,
        rst_pin: int = 27,
        bl_pin: int = 24,
        rotation: int = 0,
        spi_hz: int = 40_000_000,
    ) -> None:
        self.width = width
        self.height = height
        self.rotation = rotation % 360
        self._chunk_size = 4096

        chip = "/dev/gpiochip0"
        self._dc = GPIO(chip, dc_pin, "out")
        self._rst = GPIO(chip, rst_pin, "out")
        self._bl = GPIO(chip, bl_pin, "out")
        self._spi = SPI(f"/dev/spidev{spi_bus}.{spi_dev}", 0, spi_hz)

        self._display_initialized = False
        self._bl.write(True)

    def close(self) -> None:
        for attr_name in ("_dc", "_rst", "_bl", "_spi"):
            resource = getattr(self, attr_name, None)
            if resource is None:
                continue
            try:
                resource.close()
            except Exception:
                pass
            setattr(self, attr_name, None)

    def set_backlight(self, on: bool) -> None:
        if self._bl is not None:
            self._bl.write(bool(on))

    def _chunked_transfer(self, data: bytes) -> None:
        i = 0
        chunk_size = self._chunk_size
        while i < len(data):
            chunk = data[i : i + chunk_size]
            try:
                self._spi.transfer(chunk)
                i += len(chunk)
            except Exception as error:
                if getattr(error, "errno", None) == errno.EMSGSIZE and chunk_size > 256:
                    chunk_size = max(256, chunk_size // 2)
                    self._chunk_size = chunk_size
                    continue
                raise

    def command(self, cmd: int) -> None:
        self._dc.write(False)
        self._spi.transfer([cmd & 0xFF])

    def data(self, val: int) -> None:
        self._dc.write(True)
        self._spi.transfer([val & 0xFF])

    def reset(self) -> None:
        self._rst.write(True)
        time.sleep(0.01)
        self._rst.write(False)
        time.sleep(0.01)
        self._rst.write(True)
        time.sleep(0.01)

    def init(self) -> None:
        self.reset()

        self.command(0x36)
        self.data(0x70)

        self.command(0x3A)
        self.data(0x05)

        self.command(0xB2)
        for val in (0x0C, 0x0C, 0x00, 0x33, 0x33):
            self.data(val)

        self.command(0xB7)
        self.data(0x35)

        self.command(0xBB)
        self.data(0x19)

        self.command(0xC0)
        self.data(0x2C)

        self.command(0xC2)
        self.data(0x01)

        self.command(0xC3)
        self.data(0x12)

        self.command(0xC4)
        self.data(0x20)

        self.command(0xC6)
        self.data(0x0F)

        self.command(0xD0)
        self.data(0xA4)
        self.data(0xA1)

        self.command(0xE0)
        for val in (0xD0, 0x04, 0x0D, 0x11, 0x13, 0x2B, 0x3F, 0x54, 0x4C, 0x18, 0x0D, 0x0B, 0x1F, 0x23):
            self.data(val)

        self.command(0xE1)
        for val in (0xD0, 0x04, 0x0C, 0x11, 0x13, 0x2C, 0x3F, 0x44, 0x51, 0x2F, 0x1F, 0x1F, 0x20, 0x23):
            self.data(val)

        self.command(0x21)
        self.command(0x11)
        time.sleep(0.15)
        self.command(0x29)

    def _ensure_initialized(self) -> None:
        if not self._display_initialized:
            self.init()
            self._display_initialized = True

    def _set_window(self, x_start: int, y_start: int, x_end: int, y_end: int) -> None:
        self.command(0x2A)
        self.data(0x00)
        self.data(x_start & 0xFF)
        self.data(0x00)
        self.data((x_end - 1) & 0xFF)

        self.command(0x2B)
        self.data(0x00)
        self.data(y_start & 0xFF)
        self.data(0x00)
        self.data((y_end - 1) & 0xFF)

        self.command(0x2C)

    def show_image(self, image: Image.Image) -> None:
        self._ensure_initialized()
        if image.mode != "RGB":
            image = image.convert("RGB")
        if image.size != (self.width, self.height):
            image = image.resize((self.width, self.height), Image.Resampling.BILINEAR)

        arr = array.array("H", image.convert("BGR;16").tobytes())
        arr.byteswap()
        pix = arr.tobytes()

        self._set_window(0, 0, self.width, self.height)
        self._dc.write(True)
        self._chunked_transfer(pix)

    def clear(self) -> None:
        self._ensure_initialized()
        self._set_window(0, 0, self.width, self.height)
        self._dc.write(True)
        self._chunked_transfer(bytes([0xFF]) * (self.width * self.height * 2))
