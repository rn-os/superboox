# Apps grid: Previous app is ALWAYS the bottom-right tile of every page (non-negotiable), on every page count.
# Uses real installed apps as dock tiles and a pretend "previous app"; the real dock is restored at the end.

# n packages that are installed on the tablet (candidates first, then whatever else is there).
_installed_apps() {
  adb_ shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/package://' \
    | grep -E '^(com\.android\.(settings|calculator2|deskclock|gallery3d|calendar|contacts|documentsui|music|camera2)|com\.onyx\.[a-z.]+|com\.google\.android\.(calculator|deskclock))$' \
    | grep -v "^$PKG$" | head -"$1"
}

_previous_is_bottom_right() {   # <label> after the panel is open
  dump "grid" || { note_fail "$1: no UI dump"; return 1; }
  local out; out=$(pyfind bottomright "Previous app:" ${LABELS[@]+"${LABELS[@]}"}) \
    && note_ok "$1: $out" || { note_fail "$1: $out"; shot "fail-grid"; }
}

_grid_case() {   # <napps> <layout 0 auto|1 one row|2 two rows> <name>
  local n="$1" layout="$2" name="$3" pkgs
  pkgs=$(_installed_apps "$n" | paste -sd, -)
  if [ "$(echo "$pkgs" | tr ',' '\n' | grep -c .)" -lt "$n" ]; then note_ok "$name: only $(echo "$pkgs" | tr ',' '\n' | grep -c .) suitable apps installed, case reduced"; fi
  hook set_dock pkgs "$pkgs" || return
  LABELS=(); while IFS= read -r l; do [ -n "$l" ] && LABELS+=("$l"); done < <(echo "$HOOK_OUT" | python3 -I -c 'import json,sys; [print(x) for x in json.loads(sys.stdin.read())["labels"]]')
  hook set_previous_in_dock on 1 || return
  hook set_dock_layout layout "$layout" || return
  # Previous app = some app that isn't in the dock and isn't in front.
  hook set_previous pkg com.android.settings || return
  hook open_panel || return
  sleep 1.5
  _previous_is_bottom_right "$name page 1"
  # Every further page too.
  local page=1
  while dump "more" && pyfind has desc "More apps" && [ $page -lt 12 ]; do
    tap desc "More apps" || break
    page=$((page + 1))
    sleep 1
    _previous_is_bottom_right "$name page $page"
  done
  hook close_panel >/dev/null
}

check_one_row_few_apps()  { _grid_case 2 0 "2 apps, auto layout"; }
check_two_rows_auto()     { _grid_case 4 0 "4 apps, auto layout"; }
check_forced_one_row()    { _grid_case 6 1 "6 apps, one row (paged)"; }
check_forced_two_rows()   { _grid_case 9 2 "9 apps, two rows (paged)"; }
check_no_apps_only_previous() { _grid_case 0 0 "no pinned apps"; }
