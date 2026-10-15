# Tablet test tools

Scripted checks for the Boox tablet, run over adb from a Mac. Replaces driving the tablet tap by tap.

```
tools/tablet-test.sh list
tools/tablet-test.sh --apk superboox-1.1.0-branch.N.apk run apps-grid clipboard recents
```

Output is `tools/tablet-results/<time>/summary.md` (pass/fail table, log tail for each failure), plus a screenshot
and panel dump before every tap and for every failure. The run prints the summary at the end.

## What it will and won't do
- Taps only SuperBoox's own windows, by finding a view in the panel dump (test hook `dump_ui`; never uiautomator, whose dump unbinds the accessibility service) (no coordinates), after a screenshot and dump,
  and never while the BOOX Notes editor is in front (the run aborts instead).
- Never force-stops SuperBoox. Refuses to run if its accessibility service is off.
- Makes its own `SBTEST` clips and recents through the test hooks and removes them; snapshots every SuperBoox
  setting first and restores it at the end (also on Ctrl-C). A copy of the snapshot is saved in the results folder.
- Rotation settings are put back; the front light and other system settings aren't touched by the shipped checks.

## Test hooks
Only **unpublished branch/PR builds** contain them (CI sets `SB_TEST_HOOKS=1` for those; default-branch pre-releases and
stable releases do not), and each command is refused unless `files/testhooks.on` exists, which only `adb shell run-as`
can create. The script creates it at the start and deletes it at the end. See `TestHooks.kt`.

```
adb shell am broadcast -a com.superboox.quickaccess.TEST -p com.superboox.quickaccess --es cmd state --es id 1
adb logcat -d -s SBTEST      # "<id> ok|fail <cmd> {json}"
```
Commands: `ping snapshot restore add_text add_image add_handwriting remove_test_clips copy_clip paste_clip seed_recents
clear_test_recents set_previous set_dock set_previous_in_dock set_dock_layout open_panel close_panel state`.

## Adding checks for a feature
Create `tools/tablet-checks/<feature>.sh` with functions named `check_<what>`. Helpers: `hook`, `tap kind value [hold]`,
`expect`, `expect_absent`, `type_text`, `shot`, `manual "<what only a person/pen can do>"`. Selector kinds: `text`, `desc`,
`rid`, `textprefix`, `descprefix`. If new logic needs a state the hooks can't make, add a command to `TestHooks.kt`.
Put a `DebugLog.i(...)` line in new logic so a failed check's log tail explains itself.

## Unit tests (CI)
`./gradlew :app:testDebugUnitTest` (runs in CI before the build). Panel logic that doesn't need a device is split into
plain Kotlin objects so it can be tested, e.g. `DockGrid` (apps paging, Previous app always bottom right).
