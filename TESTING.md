# Testing contract

Short on purpose. "CI green" and "verified on BOOX" are different claims; say which one you mean.

## Three layers
| Layer | Runs | Proves | Does not prove |
|---|---|---|---|
| CI: build + `:app:testDebugUnitTest` | every push, no tablet | it compiles; pure decisions are right (apps paging, Previous app last, Recents/reader filtering) | anything Android or BOOX does |
| Scripted tablet run: `tools/tablet-test.sh run <feature>` | once per device-facing PR, by the Tablet test runner | SuperBoox's own screens and settings behave on the 6C, with screenshots and a log excerpt per failure | pen, BOOX Notes, real gestures |
| Manual (Ryan) | only the items marked `manual` in a checklist | pen input, Notes rendering, edge swipes | - |

## Does this PR need the tablet?
`tools/needs-device.sh` (also printed on every PR run page) says `CI-ONLY` when every changed file is tests, docs, tools,
CI, or a pure-logic file with unit tests (`Recents`, `Pin`, `DockGrid`, `Folders`). Anything else, including any file it
doesn't know, says `DEVICE`. CI-ONLY changes ship on CI green. To make more code CI-only, move its decisions into a plain
Kotlin object, add a test, then add the file to the allowlist in the script.

## What can never be offline
Pen input and handwriting capture; BOOX Notes/NeoReader rendering and opening notes by name (accessibility on their windows);
real edge gestures and overlay behavior on e-ink (refresh, color vs mono); front light and firmware settings; Shizuku/Advanced
access. An emulator can't stand in for these; fixtures can only replay the *decisions* made from them (see below).

## Checklists
`/mnt/project-files/checklists/<feature>.md`. Tag every step `[offline]` (a unit test covers it), `[device]` (scripted
on the tablet) or `[manual]` (Ryan, pen). Only `[device]` and `[manual]` steps go to the tablet; for an `[offline]` step,
name the test. Unsure? `[?]`: decide before building, not after.

## Failures
Unit tests: the `unit-test-report` artifact on the run. Tablet: `tools/tablet-results/<time>/summary.md`, screenshots and
`adb logcat -s SBTEST SuperBoox`. New logic gets a `DebugLog.i(...)` line so one log excerpt explains a failure.

## Next steps (not built yet)
- Fixtures: one capture from the tablet (Notes DB rows, foreground-window/accessibility event log, a Settings snapshot) would let
  `NoteOpener`/`QuickAccess` matching run as JVM tests. Needs one run through the Tablet test runner; ask the coordinator.
- Extract more decisions from `ClipHistory`, `Prefs` and `NoteOpener` into plain objects, one per bug that cost a device session.
