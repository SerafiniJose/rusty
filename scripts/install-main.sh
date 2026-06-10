#!/usr/bin/env bash
#
# Build the current (main) debug APK and install it to every connected adb device.
#
# Usage:
#   scripts/install-main.sh            # build, then install to all devices
#   scripts/install-main.sh --no-build # install the existing APK without rebuilding
#
set -euo pipefail

cd "$(dirname "$0")/.."

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="dev.librespotconnect.receiver"
BUILD=1

for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    -h|--help)
      sed -n '2,8{s/^# \{0,1\}//;p}' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# --- locate adb ---------------------------------------------------------------
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  for c in "$ANDROID_HOME/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
command -v "$ADB" >/dev/null 2>&1 || { echo "error: adb not found (set ADB or ANDROID_HOME)" >&2; exit 1; }

# --- build --------------------------------------------------------------------
if [ "$BUILD" -eq 1 ]; then
  echo ">> Building debug APK from $(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD)"
  ./gradlew :app:assembleDebug
fi

[ -f "$APK" ] || { echo "error: APK not found at $APK (run without --no-build)" >&2; exit 1; }

# --- collect connected devices ------------------------------------------------
mapfile -t DEVICES < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "error: no connected adb devices (check 'adb devices')" >&2
  exit 1
fi

echo ">> Installing $APK ($(du -h "$APK" | cut -f1)) to ${#DEVICES[@]} device(s)"

# --- install to each ----------------------------------------------------------
fail=0
for dev in "${DEVICES[@]}"; do
  model=$("$ADB" -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  printf '   - %s (%s) ... ' "$dev" "${model:-unknown}"
  if "$ADB" -s "$dev" install -r -d "$APK" >/tmp/install-main.$$.log 2>&1; then
    echo "ok"
  else
    echo "FAILED"
    sed 's/^/       /' /tmp/install-main.$$.log
    fail=1
  fi
done
rm -f /tmp/install-main.$$.log

exit "$fail"
