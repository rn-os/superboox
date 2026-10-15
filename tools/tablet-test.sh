#!/usr/bin/env bash
# Scripted tablet checks for SuperBoox over adb. Run from a Mac with the tablet connected.
#
#   tools/tablet-test.sh [options] run <feature>...     run the check files tools/tablet-checks/<feature>.sh
#   tools/tablet-test.sh list                           list features and their checks
#
#   --apk FILE      install this build first (a branch/PR test build: they carry the test hooks)
#   --serial ID     adb serial (default: the only device)
#   --out DIR       results folder (default tools/tablet-results/<timestamp>)
#
# Safety rules, enforced here so a check can't break them:
#   - Only SuperBoox's own windows are ever tapped, always by finding a node (never by coordinates),
#     after a screenshot and a uiautomator dump, and only if the app in front is not the BOOX Notes editor.
#   - Nothing is typed or tapped inside a note. Notes, files and the real clipboard history are never
#     touched: tests make "SBTEST" clips/recents through the debug hooks and remove them afterwards.
#   - SuperBoox is never force-stopped (that switches its accessibility service off).
#   - SuperBoox settings are snapshotted first and put back at the end (also on Ctrl-C), and the
#     snapshot is copied to the results folder too.
# Result: <out>/summary.md (short pass/fail table, log excerpts for failures), results.tsv, per-check screenshots.
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd)
PKG=com.superboox.quickaccess
NOTES_PKG=com.onyx.android.note
APK=""; SERIAL=""; OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    *) break ;;
  esac
done
CMD="${1:-}"; [ $# -gt 0 ] && shift

adb_() { if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi; }

list_features() {
  for f in tools/tablet-checks/*.sh; do
    echo "$(basename "$f" .sh): $(grep -E '^check_[a-z0-9_]+\(\)' "$f" | sed -E 's/\(\).*//' | tr '\n' ' ')"
  done
}

if [ "$CMD" = "list" ]; then list_features; exit 0; fi
if [ "$CMD" != "run" ] || [ $# -eq 0 ]; then sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 2; fi
FEATURES=("$@")
for f in "${FEATURES[@]}"; do [ -f "tools/tablet-checks/$f.sh" ] || { echo "no such feature: $f (try: list)"; exit 2; }; done
command -v adb >/dev/null || { echo "adb not found"; exit 2; }
command -v python3 >/dev/null || { echo "python3 not found"; exit 2; }

OUT="${OUT:-$ROOT/tools/tablet-results/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
RESULTS="$OUT/results.tsv"; : > "$RESULTS"
SUMMARY="$OUT/summary.md"
HOOK_ID=0
CHECK=""; CHECK_FAILED=0; CHECK_NOTES=""
ABORTED=""

# ---- helpers available to check files ---------------------------------------------------------

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

# Abort the whole run (and restore) when something unsafe or unexpected is in front.
abort() { ABORTED="$*"; log "ABORT: $*"; exit 3; }

shot() { adb_ exec-out screencap -p > "$OUT/$CHECK-$1.png" 2>/dev/null; }

# uiautomator dump to $OUT/<check>-<name>.xml; also the file path in $DUMP.
dump() {
  DUMP="$OUT/$CHECK-${1:-ui}.xml"
  adb_ shell uiautomator dump /sdcard/sbtest-dump.xml >/dev/null 2>&1
  adb_ pull /sdcard/sbtest-dump.xml "$DUMP" >/dev/null 2>&1
  adb_ shell rm -f /sdcard/sbtest-dump.xml
  [ -s "$DUMP" ] || { DUMP=""; return 1; }
}

top_package() {
  adb_ shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -1 \
    | sed -E 's/.* ([A-Za-z0-9_.]+)\/[^ ]*.*/\1/'
}

# Refuses to go on when the BOOX Notes editor is in front: no tap or key is ever sent there.
guard() {
  local top; top=$(top_package)
  [ "$top" = "$NOTES_PKG" ] && abort "BOOX Notes editor is in front during '$CHECK': not touching a note"
  return 0
}

pyfind() { python3 -I "$ROOT/tools/tablet-find.py" "$DUMP" "$@"; }

# expect <label> <selector kind> <value>: is there such a node in SuperBoox's windows now?
# Takes a fresh dump. kind: text|desc|rid|textprefix|descprefix
expect() {
  local label="$1"; shift
  dump "expect" || { note_fail "$label: no UI dump"; return 1; }
  if pyfind has "$@" ; then note_ok "$label"; else note_fail "$label (no $1 '$2' on screen)"; shot "fail-$(echo "$label" | tr -c 'A-Za-z0-9' _ | cut -c1-30)"; return 1; fi
}

# expect_absent <label> <kind> <value>
expect_absent() {
  local label="$1"; shift
  dump "absent" || { note_fail "$label: no UI dump"; return 1; }
  if pyfind has "$@" ; then note_fail "$label ('$2' is on screen)"; shot "fail-absent"; return 1; else note_ok "$label"; fi
}

# tap <kind> <value> [hold]: screenshot + dump first, guard, then tap (or hold ~900 ms) the one matching node
# in SuperBoox's windows. There is deliberately no way to tap coordinates.
tap() {
  local kind="$1" value="$2" hold="${3:-}"
  guard
  shot "before-tap"
  dump "before-tap" || { note_fail "tap $value: no UI dump"; return 1; }
  local pos; pos=$(pyfind find "$kind" "$value") || { note_fail "tap: $kind '$value' not found (or ambiguous)"; shot "fail-tap"; return 1; }
  set -- $pos
  [ -n "$hold" ] && adb_ shell input swipe "$1" "$2" "$1" "$2" 900 || adb_ shell input tap "$1" "$2"
  sleep 1
}

# type_text <text without spaces>: into the SuperBoox text box that has focus (the panel's Name box).
type_text() { guard; adb_ shell input text "$1"; sleep 1; }

# hook <cmd> [key value]...: a debug test-hook command; prints the JSON answer, returns non-zero on failure.
hook() {
  local cmd="$1"; shift
  HOOK_ID=$((HOOK_ID + 1))
  local args=() k v
  while [ $# -gt 0 ]; do k="$1"; v="$2"; shift 2; args+=(--es "$k" "$v"); done
  adb_ shell am broadcast -a com.superboox.quickaccess.TEST -p $PKG --es cmd "$cmd" --es id "$HOOK_ID" ${args[@]+"${args[@]}"} >/dev/null 2>&1
  local i line
  for i in 1 2 3 4 5 6 7 8 9 10; do
    line=$(adb_ logcat -d -v brief -s SBTEST:I 2>/dev/null | grep -E ": $HOOK_ID (ok|fail) $cmd " | tail -1)
    [ -n "$line" ] && break
    sleep 0.5
  done
  HOOK_OUT="${line#*"$cmd "}"
  case "$line" in *": $HOOK_ID ok "*) return 0 ;; *) log "hook $cmd failed: ${line:-no answer}"; return 1 ;; esac
}

# check_ok / fail notes collected for the current check
note_ok() { CHECK_NOTES="$CHECK_NOTES
  ok   $1"; }
note_fail() { CHECK_FAILED=1; CHECK_NOTES="$CHECK_NOTES
  FAIL $1"; }
manual() { CHECK_NOTES="$CHECK_NOTES
  manual (needs Ryan/pen) $1"; }

# ---- run ------------------------------------------------------------------------------------

restore_all() {
  [ -n "${HOOKS_READY:-}" ] || return 0
  log "cleaning up"
  hook close_panel >/dev/null
  hook remove_test_clips >/dev/null
  hook clear_test_recents >/dev/null
  hook restore >/dev/null && log "settings restored" || log "WARNING: settings restore failed; the snapshot is in $OUT/snapshot-backup.json"
  [ -n "${SAVED_ROTATION:-}" ] && adb_ shell settings put system user_rotation "$SAVED_ROTATION" >/dev/null 2>&1
  [ -n "${SAVED_AUTOROT:-}" ] && adb_ shell settings put system accelerometer_rotation "$SAVED_AUTOROT" >/dev/null 2>&1
  adb_ shell run-as $PKG rm -f files/testhooks.on >/dev/null 2>&1
}
finish() {
  local code=$?
  restore_all
  write_summary
  cat "$SUMMARY"
  exit $code
}

write_summary() {
  local pass fail
  pass=$(grep -c "^PASS" "$RESULTS" 2>/dev/null); fail=$(grep -c "^FAIL" "$RESULTS" 2>/dev/null)
  {
    echo "# Tablet test: $(date '+%Y-%m-%d %H:%M')"
    echo "Build: ${BUILD_VERSION:-unknown} on ${DEVICE:-unknown}. Passed: ${pass:-0}, failed: ${fail:-0}.${ABORTED:+ **ABORTED: $ABORTED**}"
    echo
    echo "| Check | Result |"
    echo "|---|---|"
    while IFS=$'\t' read -r r name; do echo "| $name | $r |"; done < "$RESULTS"
    if [ -s "$OUT/details.txt" ]; then echo; echo '## Details'; cat "$OUT/details.txt"; fi
    echo
    echo "Screenshots, UI dumps and log excerpts: $OUT"
  } > "$SUMMARY"
}
trap finish EXIT
trap 'ABORTED="interrupted"; exit 130' INT TERM

adb_ get-state >/dev/null 2>&1 || { ABORTED="no device"; echo "No adb device found" >&2; exit 2; }
DEVICE="$(adb_ shell getprop ro.product.model | tr -d '\r') (Android $(adb_ shell getprop ro.build.version.release | tr -d '\r'))"
if [ -n "$APK" ]; then
  log "installing $APK"
  adb_ install -r -d "$APK" >/dev/null || { ABORTED="install failed"; exit 2; }
fi
BUILD_VERSION=$(adb_ shell dumpsys package $PKG | tr -d '\r' | grep -m1 versionName | sed 's/.*=//')
adb_ shell dumpsys accessibility 2>/dev/null | grep -q "$PKG" \
  || adb_ shell settings get secure enabled_accessibility_services | grep -q "$PKG" \
  || { ABORTED="SuperBoox's accessibility service is off; only Ryan can turn it on"; exit 2; }
guard
SAVED_ROTATION=$(adb_ shell settings get system user_rotation | tr -d '\r')
SAVED_AUTOROT=$(adb_ shell settings get system accelerometer_rotation | tr -d '\r')
adb_ shell run-as $PKG touch files/testhooks.on >/dev/null 2>&1 || { ABORTED="run-as failed (not a debug build?)"; exit 2; }
hook ping || { ABORTED="test hooks don't answer: install a branch/PR test build (published builds don't have them)"; exit 2; }
hook snapshot || { ABORTED="snapshot failed"; exit 2; }
HOOKS_READY=1
adb_ exec-out run-as $PKG cat files/testhooks-snapshot.json > "$OUT/snapshot-backup.json" 2>/dev/null
log "build $BUILD_VERSION on $DEVICE; snapshot saved"

for feature in "${FEATURES[@]}"; do
  # shellcheck disable=SC1090
  source "tools/tablet-checks/$feature.sh"
  for fn in $(grep -E '^check_[a-z0-9_]+\(\)' "tools/tablet-checks/$feature.sh" | sed -E 's/\(\).*//'); do
    CHECK="$feature.${fn#check_}"; CHECK_FAILED=0; CHECK_NOTES=""
    log "== $CHECK"
    adb_ logcat -c
    guard
    "$fn"
    if [ "$CHECK_FAILED" = 1 ]; then
      printf 'FAIL\t%s\n' "$CHECK" >> "$RESULTS"
      shot "end"
      adb_ logcat -d -v time -s SuperBoox:I SBTEST:I AndroidRuntime:E 2>/dev/null | tail -60 > "$OUT/$CHECK-log.txt"
      { echo "### $CHECK (failed)"; echo '```'; echo "$CHECK_NOTES" | sed '/^$/d'; echo '```'; echo "Log tail:"; echo '```'; tail -15 "$OUT/$CHECK-log.txt"; echo '```'; } >> "$OUT/details.txt"
    else
      printf 'PASS\t%s\n' "$CHECK" >> "$RESULTS"
      echo "$CHECK_NOTES" | grep manual | sed "s/^/  $CHECK:/" >> "$OUT/manual.txt"
    fi
    hook close_panel >/dev/null
  done
done
[ -s "$OUT/manual.txt" ] && { { echo "### Still manual"; cat "$OUT/manual.txt"; } >> "$OUT/details.txt"; }
[ "$(grep -c '^FAIL' "$RESULTS")" = 0 ] || exit 1
exit 0
