#!/usr/bin/env bash
# Does this change need a tablet pass?  tools/needs-device.sh [base-ref]   (default: origin's default branch)
# Prints "CI-ONLY" when every changed file is on the allowlist below (tests, docs, tools, CI, pure-logic
# Kotlin that unit tests cover), otherwise "DEVICE" plus the files that triggered it. Anything unknown is DEVICE.
set -eu
cd "$(dirname "$0")/.."
BASE="${1:-origin/$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|origin/||' || echo main)}"
SAFE='^(app/src/test/|docs/|tools/|\.github/|TESTING\.md$|README\.md$|\.gitignore$)|\.md$|^app/src/main/java/com/superboox/quickaccess/(Recents|Pin|DockGrid|Folders)\.kt$'
DEV=$(git diff --name-only "$(git merge-base "$BASE" HEAD)" HEAD | grep -vE "$SAFE" || true)
if [ -z "$DEV" ]; then echo "CI-ONLY"; else echo "DEVICE"; echo "$DEV" | sed 's/^/  /'; fi
