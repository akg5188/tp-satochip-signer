#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="/opt/tp-pi-kiosk"
SIGNER_ROOT="/opt/tp-pi-signer"
DEFAULT_PATH="m/44'/60'/0'/0/0"

export PYTHONUNBUFFERED=1
export TP_PI_SIGNER_BIN="${TP_PI_SIGNER_BIN:-$SIGNER_ROOT/bin/pi-signer}"
export TP_READER_HINT="${TP_READER_HINT:-ACR39}"
export TP_DERIVATION_PATH="${TP_DERIVATION_PATH:-$DEFAULT_PATH}"

cd "$APP_ROOT"
exec /usr/bin/python3 "$APP_ROOT/kiosk.py"
