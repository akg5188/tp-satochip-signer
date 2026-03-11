#!/usr/bin/env python3
import time
from dataclasses import dataclass
from typing import Dict, List

try:
    from gpiozero import Button  # type: ignore
except Exception:
    Button = None

try:
    import RPi.GPIO as GPIO  # type: ignore
except Exception:
    GPIO = None


KEY_MAP = {
    "up": 6,
    "down": 19,
    "left": 5,
    "right": 26,
    "press": 13,
    "key1": 21,
    "key2": 20,
    "key3": 16,
}


@dataclass
class KeyEvent:
    name: str
    ts: float


class HardwareKeys:
    def __init__(self, holdoff_s: float = 0.13) -> None:
        self._using_gpio_fallback = False
        if Button is not None:
            self._buttons: Dict[str, object] = {
                name: Button(pin, pull_up=True, bounce_time=0.03)
                for name, pin in KEY_MAP.items()
            }
        elif GPIO is not None:
            self._using_gpio_fallback = True
            GPIO.setmode(GPIO.BCM)
            GPIO.setwarnings(False)
            self._buttons = {}
            for name, pin in KEY_MAP.items():
                GPIO.setup(pin, GPIO.IN, pull_up_down=GPIO.PUD_UP)
                self._buttons[name] = _GpioButton(pin)
        else:
            raise RuntimeError("gpiozero 与 RPi.GPIO 都不可用，无法读取按键")

        self._holdoff_s = holdoff_s
        self._pressed_state: Dict[str, bool] = {name: False for name in KEY_MAP.keys()}
        self._last_emit: Dict[str, float] = {name: 0.0 for name in KEY_MAP.keys()}

    def close(self) -> None:
        for button in self._buttons.values():
            try:
                button.close()
            except Exception:
                pass
        if self._using_gpio_fallback and GPIO is not None:
            try:
                GPIO.cleanup()
            except Exception:
                pass

    def poll(self) -> List[KeyEvent]:
        now = time.monotonic()
        events: List[KeyEvent] = []

        for name, button in self._buttons.items():
            is_pressed = button.is_pressed
            prev = self._pressed_state[name]

            if is_pressed and not prev:
                # Rising edge (button just pressed)
                if now - self._last_emit[name] >= self._holdoff_s:
                    events.append(KeyEvent(name=name, ts=now))
                    self._last_emit[name] = now

            self._pressed_state[name] = is_pressed

        return events


class _GpioButton:
    def __init__(self, pin: int) -> None:
        self.pin = pin

    @property
    def is_pressed(self) -> bool:
        if GPIO is None:
            return False
        return GPIO.input(self.pin) == GPIO.LOW

    def close(self) -> None:
        return
