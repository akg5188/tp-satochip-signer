#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OS_DIR="$ROOT_DIR/seedsigner-os"
LOG_FILE="$OS_DIR/output/build/build-time.log"
OUTPUT_IMAGES_DIR="$OS_DIR/output/images"
FINAL_IMAGES_DIR="$OS_DIR/images"
INTERVAL="${INTERVAL:-10}"
STALE_SECONDS="${STALE_SECONDS:-180}"
WINDOW_LINES="${WINDOW_LINES:-2000}"
ONCE=0

usage() {
  cat <<'EOF'
Usage: monitor_seedsigner_build.sh [--once] [--interval N] [--stale N] [--os-dir DIR]

Options:
  --once         Print one snapshot and exit.
  --interval N   Refresh interval in seconds. Default: 10
  --stale N      Mark build as stalled if log is older than N seconds. Default: 180
  --os-dir DIR   Override seedsigner-os directory.
  WINDOW_LINES   Env var. Recent build-time.log lines to analyze. Default: 2000
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --once)
      ONCE=1
      shift
      ;;
    --interval)
      INTERVAL="$2"
      shift 2
      ;;
    --stale)
      STALE_SECONDS="$2"
      shift 2
      ;;
    --os-dir)
      OS_DIR="$2"
      LOG_FILE="$OS_DIR/output/build/build-time.log"
      OUTPUT_IMAGES_DIR="$OS_DIR/output/images"
      FINAL_IMAGES_DIR="$OS_DIR/images"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "$LOG_FILE" ]]; then
  echo "build-time.log not found: $LOG_FILE" >&2
  exit 1
fi

snapshot() {
  local epoch log_mtime log_age active_count build_proc_count status
  local active_lines recent_lines out_images final_images

  epoch="$(date +%s)"
  log_mtime="$(stat -c %Y "$LOG_FILE")"
  log_age="$(( epoch - log_mtime ))"

  build_proc_count="$(
    ps -eo args= | awk -v os="$OS_DIR" '
      index($0, os "/opt/build.sh") ||
      index($0, os "/opt/buildroot") ||
      index($0, os "/output/host/bin/meson") ||
      index($0, os "/output/host/bin/ninja") ||
      index($0, os "/output/build/") {
        count++
      }
      END {
        print count + 0
      }
    '
  )"

  mapfile -t active_lines < <(
    tail -n "$WINDOW_LINES" "$LOG_FILE" | tac | awk -F':' '
      /^[0-9.]+:(start|end):/ {
        when=$2
        phase=$3
        pkg=$4
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", phase)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", pkg)
        key=phase "|" pkg
        if (when == "end") {
          seen[key]=1
        } else if (!seen[key]) {
          seen[key]=1
          print phase "\t" pkg
          count++
          if (count >= 5) {
            exit
          }
        }
      }
    '
  )

  active_count="${#active_lines[@]}"

  mapfile -t recent_lines < <(tail -n 8 "$LOG_FILE")
  mapfile -t out_images < <(find "$OUTPUT_IMAGES_DIR" -maxdepth 2 -type f 2>/dev/null | sort)
  mapfile -t final_images < <(find "$FINAL_IMAGES_DIR" -maxdepth 2 -type f ! -name '.git*' 2>/dev/null | sort)

  if (( ${#out_images[@]} > 0 || ${#final_images[@]} > 1 )); then
    status="image_ready"
  elif (( build_proc_count > 0 && log_age <= STALE_SECONDS )); then
    status="running"
  elif (( build_proc_count > 0 && log_age > STALE_SECONDS )); then
    status="stalled"
  elif (( build_proc_count == 0 && active_count > 0 )); then
    status="stopped_with_active_work"
  else
    status="idle"
  fi

  printf 'Time: %s\n' "$(date '+%F %T')"
  printf 'Status: %s\n' "$status"
  printf 'Build processes: %s\n' "$build_proc_count"
  printf 'Active steps: %s\n' "$active_count"
  printf 'Log age: %ss\n' "$log_age"
  printf '\nCurrent active entries:\n'
  if (( ${#active_lines[@]} == 0 )); then
    printf '  none\n'
  else
    local item
    for item in "${active_lines[@]}"; do
      printf '  %s\n' "$item"
    done
  fi

  printf '\nRecent build log lines:\n'
  local line
  for line in "${recent_lines[@]}"; do
    printf '  %s\n' "$line"
  done

  printf '\nImage outputs:\n'
  if (( ${#out_images[@]} == 0 && ${#final_images[@]} == 0 )); then
    printf '  none yet\n'
  else
    for line in "${out_images[@]}"; do
      printf '  %s\n' "$line"
    done
    for line in "${final_images[@]}"; do
      printf '  %s\n' "$line"
    done
  fi
}

if (( ONCE == 1 )); then
  snapshot
  exit 0
fi

while true; do
  if [[ -t 1 && -n "${TERM:-}" ]]; then
    clear
  fi
  snapshot
  sleep "$INTERVAL"
done
