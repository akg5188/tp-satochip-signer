#!/usr/bin/env python3
import time
from typing import List, Optional, Tuple

from PIL import Image

try:
    import numpy as np
except Exception:  # pragma: no cover
    np = None

try:
    from picamera2 import Picamera2
except Exception as error:  # pragma: no cover
    Picamera2 = None
    _PICAMERA_IMPORT_ERROR = error
else:
    _PICAMERA_IMPORT_ERROR = None

try:
    from picamera import PiCamera
    from picamera.array import PiRGBArray
except Exception as error:  # pragma: no cover
    PiCamera = None
    PiRGBArray = None
    _LEGACY_PICAMERA_IMPORT_ERROR = error
else:
    _LEGACY_PICAMERA_IMPORT_ERROR = None

try:
    from pyzbar.pyzbar import decode as zbar_decode
except Exception as error:  # pragma: no cover
    zbar_decode = None
    _PYZBAR_IMPORT_ERROR = error
else:
    _PYZBAR_IMPORT_ERROR = None


class CameraScanner:
    def __init__(
        self,
        preview_size: Tuple[int, int] = (640, 480),
        rotate: int = 0,
    ) -> None:
        self.preview_size = preview_size
        self.rotate = rotate % 360
        self._camera: Optional[Picamera2] = None
        self._legacy_camera: Optional[PiCamera] = None
        self._legacy_raw = None

    def start(self) -> None:
        if zbar_decode is None:
            raise RuntimeError(f"pyzbar 不可用: {_PYZBAR_IMPORT_ERROR}")

        if Picamera2 is not None:
            self._camera = Picamera2()
            cfg = self._camera.create_preview_configuration(
                main={"size": self.preview_size, "format": "RGB888"}
            )
            self._camera.configure(cfg)
            self._camera.start()
            return

        if PiCamera is not None and PiRGBArray is not None:
            self._legacy_camera = PiCamera()
            self._legacy_camera.resolution = self.preview_size
            self._legacy_camera.framerate = 24
            self._legacy_raw = PiRGBArray(self._legacy_camera, size=self.preview_size)
            time.sleep(0.25)
            return

        raise RuntimeError(
            "picamera2/picamera 都不可用: "
            f"picamera2={_PICAMERA_IMPORT_ERROR}; picamera={_LEGACY_PICAMERA_IMPORT_ERROR}"
        )

    def stop(self) -> None:
        if self._camera is not None:
            try:
                self._camera.stop()
            except Exception:
                pass
            try:
                self._camera.close()
            except Exception:
                pass
            self._camera = None
        if self._legacy_camera is not None:
            try:
                self._legacy_camera.close()
            except Exception:
                pass
            self._legacy_camera = None
            self._legacy_raw = None

    def capture(self) -> Tuple[Image.Image, List[str]]:
        frame = None
        if self._camera is not None:
            frame = self._camera.capture_array("main")
        elif self._legacy_camera is not None and self._legacy_raw is not None:
            self._legacy_raw.truncate(0)
            self._legacy_camera.capture(self._legacy_raw, format="rgb", use_video_port=True)
            frame = self._legacy_raw.array
        else:
            raise RuntimeError("camera not started")

        if frame is None:
            raise RuntimeError("无法从相机采集画面")

        img = Image.fromarray(frame.astype("uint8"), mode="RGB")
        if self.rotate:
            if np is not None:
                frame = np.rot90(frame, k=self.rotate // 90)
                img = Image.fromarray(frame.astype("uint8"), mode="RGB")
            else:
                img = img.rotate(self.rotate, expand=True)
        qr_values: List[str] = []

        for item in zbar_decode(img):
            try:
                qr_values.append(item.data.decode("utf-8", errors="strict"))
            except Exception:
                continue

        return img, qr_values
