#!/usr/bin/env bash
set -u

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACKAGE="com.pure.jingping"

if [ ! -x "$ADB" ] && ! command -v "$ADB" >/dev/null 2>&1; then
    printf 'adb not found: %s\n' "$ADB" >&2
    printf 'Set ADB=/path/to/adb or install Android platform-tools.\n' >&2
    exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    printf 'No authorized adb device.\n' >&2
    exit 1
fi

printf '== device ==\n'
"$ADB" shell getprop ro.product.model
"$ADB" shell getprop ro.build.version.release
"$ADB" shell getprop ro.build.display.id

printf '\n== package ==\n'
"$ADB" shell dumpsys package "$PACKAGE" 2>/dev/null \
    | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime' \
    | head -10 || true

printf '\n== process ==\n'
"$ADB" shell pidof "$PACKAGE" || true

printf '\n== accessibility ==\n'
ACCESSIBILITY_SETTING="$("$ADB" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
if printf '%s' "$ACCESSIBILITY_SETTING" | tr ':' '\n' | grep -F "$PACKAGE" >/dev/null; then
    printf 'secure setting: enabled\n'
else
    printf 'secure setting: empty (vivo may keep vendor state; check logs below)\n'
fi

printf '\n== tun ==\n'
"$ADB" shell ip addr show tun0 2>/dev/null | head -20 || printf 'tun0 not present\n'

printf '\n== vpn capability ==\n'
"$ADB" shell dumpsys connectivity 2>/dev/null \
    | grep -i -E 'VpnNetworkProvider|tun0' | head -20 || true

printf '\n== recent app logs ==\n'
PID="$("$ADB" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')"
if [ -n "${PID:-}" ]; then
    if "$ADB" logcat -v time --pid="$PID" -d 2>/dev/null \
        | grep -qE 'accessibility service connected|detected '; then
        printf 'recent log: accessibility scanning active\n'
    else
        printf 'recent log: no accessibility activity found\n'
    fi
    "$ADB" logcat -v time --pid="$PID" -d 2>/dev/null \
        | grep -E 'JingPing|JingPingDns|JingPingProxy|JingPingLearn' \
        | tail -40 || true
else
    printf 'app process not running\n'
fi

printf '\n== built-in rules ==\n'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RULES="$ROOT/app/src/main/assets/ad_domains.txt"
if [ -f "$RULES" ]; then
    awk 'NF && $1 !~ /^#/' "$RULES" | wc -l
else
    printf 'rules file not found\n'
fi

printf '\n== dist hash ==\n'
DIST="$ROOT/dist/jingping-v1.1.8-debug.apk"
if [ -f "$DIST" ]; then
    sha256sum "$DIST"
else
    printf 'dist apk not found\n'
fi
