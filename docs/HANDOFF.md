# SuperBoox: handoff / status (as of build 62)

## What it is
An Android overlay app for ONYX BOOX e-ink tablets (user's device: **Note Air 6C, Android 16 / SDK 36, firmware 4.3 `2026-09-16_13-47_4.3-rel_0916`**). It recreates the **Supernote "Quick Access" sidebar**. You swipe up or down along the right screen edge on any page, and a panel opens with:
- Recent Files, New Note and ⚙ settings;
- Last Opened Note and Last Opened Document;
- a Quick Access list of pinned BOOX notes, files, folders, apps and intents, with Add;
- apps in a 2 × 2 grid with paging.

## Repo / build / release
- Repo `rn-os/superboox` (public). Work branch: **`ccr-f74c5be1-yuuytt`**. Kotlin with Android platform APIs only: no AndroidX, no Compose. The UI is built in code (`Ui.kt` helpers).
- `app/build.gradle.kts`: AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 34, minSdk 29, versionCode = `GITHUB_RUN_NUMBER`, versionName `0.1.0-build.N`.
- Signing: a shared test key is checked in at `app/debug.keystore` (password `android`), so builds install over each other.
- CI: `.github/workflows/build.yml`.
  - Every push to the branch builds and publishes a **GitHub release tagged `0.1.0-build.N`**, a regular release with `superboox-build-N.apk` attached.
  - Pushes that only change `docs/**` or `**.md` don't trigger a build; `[skip ci]` in a commit message also skips it.
- The user installs through **Obtainium** using `https://github.com/rn-os/superboox`.
- **Local sandbox can't reach dl.google.com**, so there is no Android SDK and no on-device testing. Compile-check instead with a scratch Gradle Kotlin/JVM project against `android.jar` (from `raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar`) plus a generated `R.java`. Verify a release with `curl --retry … releases/download/0.1.0-build.N/superboox-build-N.apk`, then check the signature cert bytes and the versionName in the manifest.
- GitHub API responses seen from the sandbox are often stale or contradictory, and Monitor events can arrive "ahead" of the shell clock. Trust a real APK download, not API status.

## Working agreements with the user
- **Send mockups for review before any UI change is built** (`docs/mockups/mockup.html` + `render.mjs` → PNGs, rendered with Playwright/Chromium at 2x). Bug fixes with no visual change can ship directly.
- **Never ask the user to run adb.** Diagnose through the in-app **Settings → Troubleshooting → Copy debug report**, which the user pastes back.
- Don't create PRs unless asked. Releases are fine (the user approved them).

## Code map (`app/src/main/java/com/superboox/quickaccess/`)
- `QuickAccessService`: the AccessibilityService. Draws the edge handle and the panel as `TYPE_ACCESSIBILITY_OVERLAY` windows, tracks the foreground app and activity, records recent notes and documents (`trackRecent`), runs global actions, `openBooxNote()`, `notesState()`.
- `EdgeHandleView`: touch strip. Triggers are SWIPE_ALONG (default), SWIPE_IN and DOUBLE_TAP.
- `QuickAccessPanel`: Supernote-style UI.
  - Modes: MAIN, RECENT, EDIT (long-press a pin) and PICK (Add from the Notes library: multi-select, black Cancel/Add(n) bar, 62% wide).
  - Add behaviour: inside a note it pins that note; in the library it opens the picker; anywhere else it pins the current app.
  - Last Opened Note skips the note currently open.
  - Size scaled by Menu size (default **Large**).
- `NoteOpener`: opens a note by name with a polling state machine.
  - From outside Notes it launches the BOOX launcher (`com.onyx/.StartupActivity`), whose home screen lists the notes. It finds the card, taps it, confirms the screen changed, and falls back to a real touch.
  - Inside a different note it presses Back, then searches.
  - It also pages lists and moves between folders.
  - `DIRECT_OPEN = false` (open-by-id is wired but paused).
- `NotesScreen`: reads Notes screens. Editor title comes from view id `com.onyx.android.note:id/note_name`; library cards come from large clickable blocks plus date/count filtering; ellipsis-tolerant `matches()`.
- `NotesDb`: reads `content://com.onyx.android.sdk.note.ContentProvider/NoteModel` (exported, no permission). It has 25 rows, with columns including `uniqueId`, `title`, `parentUniqueId`, `pageCount` and `status`. Lookup by title works. `openIntent()` launches `com.onyx.android.note/.note.ui.OpenNoteByDocIdActivity` with guessed extras. **This failed in build 15: the activity accepted the launch but opened nothing.**
- `BooxNotes`: firmware facts and launch candidates.
  - The Notes library lives in `com.onyx` (`com.onyx.common.note.ui.NoteActivity`), which is **not exported**.
  - The editor is `com.onyx.android.note/.note.ui.ScribbleActivity`, which is exported.
  - `com.onyx.android.note` has **no launcher entry**.
  - New Note uses the exported `CreateQuickNoteActivity`.
- `Recents`: last and recent notes and documents, stored on-device in prefs.
- `DebugLog`, `DebugReport`: in-app log and report. The report is built off the main thread and always finishes.
- `NoteIdProbe`, `DexProbe`: run when the report is copied.
  - `NoteIdProbe` makes provider queries, recording column names and row counts only.
  - `DexProbe` is a read-only DEX reader that lists **const-string constants used by specific classes** in the BOOX APKs: OpenNoteByDocIdActivity, ScribbleActivity, CreateQuickNoteActivity, and the launcher's `com.onyx.common.common.base.MiddlewareActivity`. Verified against our own APK.
- Also: `SettingsActivity` (e-ink theme, outlined buttons, Troubleshooting), `Prefs`, `Pin`, `Launcher`, `Folders`, `OpenPanelActivity` (launcher icon and adb-free trampoline), `PanelTileService`, `SuperBooxApp` (crash logging).

## Current state
- Works on the device, as confirmed by the user and the logs:
  - the gesture, the panel and the Supernote-style layout;
  - pinning, including inside a note;
  - opening pinned notes from anywhere and switching from one note to another;
  - recents and Last Opened Note;
  - the debug report.
- Known gap: opening a note **shows the BOOX home screen for about 2 seconds**, because there's no exported way into the library.

## Build 18 (latest)
- `NotesScreen.read` checks the `note_name` view **first**. In build 17 the editor's tool column (Layer, Margin, AI, …, Sync) was read as library cards, which broke Add inside a note and switching notes.
- `Pin.noteId`: `NoteIds.capture` saves the Notes database id of every BOOX_NOTE pin. It runs on service connect and whenever the `pins` pref changes. `NoteIds.resolve` follows renames when a pin is opened.
- `OpeningCover`: takes an accessibility `takeScreenshot` into memory only (`canTakeScreenshot` was added to the service config) and shows it as a non-touchable overlay while `NoteOpener` works. It's lifted 400 ms after success, at once on failure, after 12 s at most, or when the panel opens. With no screenshot, notes open without the cover. **The user doesn't want saved screenshots, and nothing is saved.**
- Build 17 findings:
  - MiddlewareActivity, ScribbleActivity and OpenNoteByDocIdActivity are all exported.
  - OpenNoteBean fields: `documentId`, `title`, `parentUniqueId`, `pageIndex`, `jumpBackToNote`, …
  - OpenNoteByDocIdActivity reads the id via `OpenNoteIntentsKt.noteDocIdFromIntent`. Build 18's report dumps that helper's strings, under `code strings …OpenNoteIntentsKt`.

## Direct note opening: confirmed working (build 19)
- Build 19 log: "opening by id → opened by id". From outside Notes it takes about 1 s. Switching from another note takes about 1.7 s (Back, which saves, then open by id), all under the cover.
- `NotesDb.openIntent()` sends `ACTION_VIEW` + `note_document_id=<uniqueId>` to `OpenNoteByDocIdActivity`. That activity's `OpenNoteByDocIdAction` loads the NoteModel and calls `RouterUtils.startScribbleActivity`. An unknown id only shows a "note not found" toast; it never creates a note.
- When a note is open, `NoteOpener.pendingDirect` fires once the editor is gone. If the right note isn't confirmed within 3.5 s, it falls back to the library route.
- `NotesScreen.read`: when the `note_name` view is present but blank (still loading), the screen is Unknown, not Library.

## Build 20 (latest): NeoReader documents
- `ReaderLibrary` reads `content://com.onyx.content.database.ContentProvider/Metadata`. That's NeoReader (`com.onyx.kreader`), exported with no permission, 192 rows. Columns used: `title`, `name`, `nativeAbsolutePath`, `lastAccess`. The newest `lastAccess` is the document currently open.
- Add while NeoReader is in front pins `PinType.DOCUMENT` (value = file path).
- Opening uses `ACTION_VIEW` on `file://` + mime, aimed at the NeoReader package, with StrictMode `VmPolicy.LAX` for that one launch. **Confirmed on device in build 20**: an EPUB opened in NeoReader about 0.3 s after tapping its pin.
- Recents: NeoReader window events record the document 1.5 s later, with `path`. Last Opened Document and Recent Files open the path directly when they have it.
- The debug report line `NeoReader last opened: "<title>" (.ext)` checks the lookup. The build 17/18 probes (`openHelpers`, `readers`, `deep`) no longer run, but their code is kept.

## Builds 21–22
- Build 21: Last Opened Document and Recent Files skip the document that's open. That's the newest remembered DOCUMENT from the app in front (`QuickAccessPanel.currentDocument` / `isCurrent`).
- Build 22: Settings uses bordered cards (mockups 15–16, approved).
  - `SettingsActivity.card(rows)`: a 2dp black outline with 8dp corners, 1dp rules between rows, 12dp side margins.
  - `heading()` puts a bold uppercase name above each card. `note()` adds help text below a card; `cardText()` is text inside one.
  - The adb tip in Advanced is gone. The Quick Access hint now reads "open it, swipe the panel open and tap Add".

## Build 23: icons (mockup 17, approved)
- `ic_note` is now an outlined notebook and `ic_note_add` the same notebook with a plus (2dp strokes). `ic_apps` is a 2×2 grid.
- Icons by kind (`QuickAccessPanel.pinIcon`): BOOX note = notebook, NeoReader document = book, other file = page, then folder, app grid, link. They appear before each pinned item, in edit mode too.
- Last Opened Note uses the notebook, Last Opened Document the book.
- `Prefs.appOwnIcons` (Settings → Gesture card, "Use apps' own icons for pinned apps") is off by default.
- `Launcher.appLabel` drops a trailing version ("NeoReader V3.0" → "NeoReader").

## Quick Controls and Previous app (build 25, internal test)
- The build 24 report showed all 20 BOOX Control Center tiles are readable in the `com.android.systemui` window, with label, state (`stateDescription` "On"/"Off" or checked) and long-click. Labels seen: "Internet,", "Bluetooth.", EinkWise, Auto-rotate, Airplane mode, "Do Not Disturb.,", Mute Notification, NaviBall, Screenshot, Full Refresh, "Screen Recorder, Off", Location, Quick Notes, Split Screen, FreeMark, Scrolling Screenshot, Hand Touch, BOOXDrop, BOOXDrop Screen Mirroring, "EnforceDoze off".
- `QuickControls.kt`:
  - Catalog: TOGGLE and TILE entries match Control Center labels by normalized prefix; ACTION entries are global actions. Default list in `DEFAULT`; the chosen list is in pref `quick_controls`.
  - `operate()` opens Quick Settings, polls for the tile, then taps or long-clicks it. It caches every state it sees in `quick_control_states`, and after a tap (not a hold) dismisses the shade.
  - `states()` merges that cache with free system reads: `Settings.Global` airplane / wifi_on / bluetooth_on, auto-rotate, the DND interruption filter, and location.
- `QuickAccessService.quickControl()` runs behind `OpeningCover`. The still is taken with the panel up; after a toggle the panel is re-shown (`showPanel(keepCover = true)`) before the cover lifts. `quickControlsPage` survives panel rebuilds.
- Panel: `quickControlsRow()` shows one row of 5 round tiles with ‹ n/m › and swiping (`SwipeRow`), using the existing `showSystemActions` pref. Settings has a Quick Controls card (reorder, remove, add picker, reset).
- Previous app: `Prefs.previousAppInDock` (off by default). The service keeps `recentApps` (no launchers, quickstep, keyboard or system screens). `previousApp` is the newest one that isn't in front. `openPreviousApp()` reopens the last note or book for Notes and NeoReader, and otherwise uses `launchApp`. In the panel, `previousCell()` comes first in the dock.
- The debug report still runs `ControlCenterProbe` (useful for tile names on other firmware).

## Builds 26–27
- Build 25 log: Wi-Fi, Bluetooth and DND toggles worked through the Control Center. Wi-Fi reads "off" for about 1 s after switching on.
- Build 26:
  - Quick Control taps are **queued in one Control Center session** (`QuickAccessService.quickControl/startSession/nextControl/finishSession`).
  - The panel is re-added **above** the screen hold (`raisePanel`), non-focusable during the session, so it stays usable. The hold is touchable (`OpeningCover.show(touchable = true)`), so outside taps can't reach the Control Center.
  - Each tap flips the tile at once (`optimistic` states, 8 s). `QuickControls.settle()` treats an unchanged state right after a tap as "still switching".
  - Actions tapped mid-session run afterwards.
  - Previous app is a dock item (`Prefs.PREVIOUS_APP = "@previous"`) that can be moved with ↑ ↓; the switch adds or removes it.
- Build 27: BOOX's Auto-rotate tile always opens its rotation menu.
  - Tap now flips `Settings.System.ACCELEROMETER_ROTATION` directly. This needs `WRITE_SETTINGS`; the first tap opens `ACTION_MANAGE_WRITE_SETTINGS`, and no adb is needed.
  - Hold taps the tile (`Control.holdTaps`), which shows the menu, and the shade is left open.

## Build 31
- Full Refresh is `momentary`: never shown filled; a tap spins the icon (500 ms) and keeps the panel up.
- Alerts: on BOOX, `GLOBAL_ACTION_NOTIFICATIONS` opened the Control Center. `QuickControls.openNotifications()` detects that (3 or more known tiles), taps a "notif…" switch if there is one, else closes the shade and swipes down from the top-left. It logs the shade tree (`[alerts] shade:`). **Read that in the next report** to see how BOOX splits notifications from the Control Center.

- Build 31 log: Full Refresh works, and Alerts reached notifications through the top-left pull-down. BOOX keeps notifications on the left (`#onyx_notification_manager_view`, `#notification_stack_scroller`) and the Control Center on the right (`#control_center_ll`, `#tablet_control_title`).
- But after Alerts, `GLOBAL_ACTION_QUICK_SETTINGS` opened the notification list, and the probe read its buttons as "tiles".

## Build 32
- `QuickControls.open()` now waits for `controlCenterShowing()`: 3 or more known tiles, or the Control Center ids above. If it hasn't shown within 1 s, it logs the shade, closes it and swipes down from the top-right (`pullDown(…, 0.8f)`), then waits again.
- `ControlCenterProbe` opens through `QuickControls.open`, so the debug report shows "not confirmed" instead of reading notifications.
- To test: tap Alerts, close it, then tap a Quick Control. Look for `pulling down from the top-right` in the log.
- Build 32 log: the fallback works (15:44:09: notifications came up, top-right pull-down, then the queued taps all landed). BOOX's shade reopens on whichever side was used last.

## Build 33
- Quick Controls switch to the top-right pull-down as soon as the notification ids (`NOTIFICATION_IDS`) are visible, without waiting the full second.
- Alerts pulls down from the top-left straight away (closing the Control Center first if it's up), so the Control Center no longer flashes. If notifications still aren't visible after about 1.2 s, it falls back to `GLOBAL_ACTION_NOTIFICATIONS` and logs the shade.
- The cover log line now says "screen held" (it's used for Quick Controls too, not only notes).

## Build 34
- Quick Controls and apps start on page 1 each time the panel opens (`showPanel(fresh = true)`); a rebuild (pinning, rotation) keeps the page. Both pages now live on the service (`quickControlsPage`, `dockPage`).
- `Prefs.rememberPages` (default off) keeps the last page instead. It has no Settings UI yet: it's in the proposed Settings menus mockup (`docs/mockups/settings-menus.html`, `23-settings-menus.png`), awaiting approval.

## Build 35
- The debug report's Control Center section now lists BOOX's Front Light area (`front light area:`, with selected/checked states). Read it to build the proposed Front Light Quick Control (`docs/mockups/front-light.html`, `24-front-light.png`: tap = off / back to the last preset; hold = Bright · Soft · Custom · Off inside the panel). Approved; built in 36.

## Build 36 (approved mockups 23 and 24)
- Settings is split into pages (`SettingsActivity.Page`). Home has the status card and menu rows with summaries, in the groups Opening the panel / In the panel / Help. Each page has a ‹ title bar; Back returns to Home. Gesture keeps only the edge settings. **Look & size** has panel width, menu size, app icons and **Remember last page** (`Prefs.rememberPages`).
- **Front Light** Quick Control (`QuickControls.FRONT_LIGHT`, BOOX group, not in the default set). Tap goes to BOOX's Off preset, or back to the last preset that wasn't Off (`K_FL_LAST_ON`, Bright if never seen). Hold toggles a Bright · Soft · Custom · Off box under the Quick Controls row (`QuickAccessPanel.lightMenu`); tapping a preset calls `service.frontLight(preset)`.
- Presses go through the session queue (`QcTask.preset`). `QuickControls.pressPreset` finds the row from the exact "Soft" label up to the ancestor holding all four labels. The selected preset is any selected/checked node in a button. If the row isn't found, it scrolls `expanded_qs_scroll_view` once and retries. **Not yet confirmed on device**: check the build 35/36 report's `front light area:` and the `front light: tap` log lines.
- Quick Controls descriptions: Full Refresh now says "Tap: full refresh".
- Build 36 log: Front Light works on device. BOOX's rows are `#brightness_style_area` › FrameLayout [click, selected] › `#brightness_radio_text_view`. Presses and selected-state reads all landed. Under them are `#brightness_bright_panel` (`#brightness_bright_slider` SeekBar 0–32, `_down`/`_up`, `#brightness_bright_text`) and `#warm_panel` (`#brightness_temp_*`), hidden unless Custom is selected.

## Build 37 (approved mockup 25)
- `Prefs.frontLightCycle`: tap steps Bright → Soft → Custom → Off. The tile is labelled with the current preset. The Front Light card on the Quick Controls page (only when it's added) has a radio for the two modes.

## Build 38 (approved mockups 26–28)
- The stepping mode from 37 is removed at the user's request: Front Light tap is on/off only; hold opens the presets.
- Front Light box (`QuickAccessPanel.frontLightMenu`): page 1 is BOOX's four; further pages hold your presets (`QuickControls.myPresets`, `K_FL_MINE`) and **+ New** (named "Preset N" from the current Custom levels; rename in Settings). Hold Custom, or one of yours, for the Light/Warm rows (`levelsEditor`, `LevelBar`). Changes are debounced 700 ms and go to `service.frontLightLevels`.
- Custom levels: `QuickControls.readLevels` (only valid while Custom is selected) and `setLevels`. The latter uses `ACTION_SET_PROGRESS` on the SeekBars inside `#brightness_bright_panel` / `#warm_panel`; the cache is `K_FL_LEVELS`. The session `QcTask` gained `levels`/`read`. **Unverified on device**: whether BOOX's SeekBar honours `ACTION_SET_PROGRESS`. If not, fall back to clicking `#brightness_bright_up/_down` / `#brightness_temp_up/_down`.
- Your presets work through Custom (they overwrite BOOX's Custom levels); the active one is `K_FL_MINE_ACTIVE`.
- Settings: Front Light · your presets card on the Quick Controls page (edit dialog with live preview), ≡ drag handles on every reorderable list (`dragHandle`), and a **Backup & restore** page (`Backup.kt`: JSON of all prefs except live state, via `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`).
- Build 38 log: setting the sliders works (`ACTION_SET_PROGRESS` → true, read back matches). Two bugs. (1) + New with unknown levels applied 16/0 over the user's BOOX Custom. (2) Tapping Custom while it was already selected opened BOOX's own Custom editor and broke later steps. In NeoReader (full screen), the top-right pull-down failed.

## Build 39
- **Your BOOX Custom is kept separate** (`K_FL_BOOX_CUSTOM`, `QuickControls.booxCustom`): levels are saved there only while no SuperBoox preset is active. SuperBoox presets borrow Custom (`QcTask.mine`, set in `nextPreset`). `service.frontLight("Custom")` restores your BOOX Custom if a SuperBoox preset was borrowing it. The first time one borrows it, your Custom is read first.
- `pressPreset` never taps the preset that's already selected.
- `QuickControls.open`: a second top-right pull-down after 1.4 s, for full-screen apps.
- Settings edit-preset preview borrows Custom under the preset's id; Cancel restores the previous preset.
- The debug report has a "Front light: system settings" section listing settings keys like bright/warm/fl. If BOOX exposes the light there, SuperBoox presets could set it without touching Custom at all.

## Build 40
- Front Light − / + buttons get their outlined circle back (`Ui.ovalButton`, set after `pressable()`, which had replaced it). The Settings edit-preset dialog has outlined − / + beside each slider.
- Build 40 report: `system/screen_ctm_brightness` and `screen_ctm_temperature` (0–32) mirror the light (Bright = 32/0); also `ctm_brightness_state_key`, `ctm_temperature_state_key`, `screen_brightness` = 135. ACTION_SET_PROGRESS returns false when the value is unchanged.

## Build 41
- Settings Home "In the panel" follows the panel order: Recent files, Quick Controls, Quick Access items, Apps.
- `LevelBar` draws a solid pill at max; the ends snap.
- `setLevels` is judged by reading the sliders back.
- **Troubleshooting → Front light test**: writes `screen_ctm_brightness` (needs WRITE_SETTINGS), waits 3 s, restores, and asks whether the light changed. The answer is logged as `[light-test]`. If writing works and BOOX follows it, SuperBoox presets can set the light directly and stop borrowing BOOX's Custom.
- Build 41 light test: writing `screen_ctm_brightness` is refused ("You cannot keep your settings in the secure settings"), so the light can't be set directly without adb. SuperBoox presets must borrow BOOX's Custom (build 39 restores your Custom). The test was removed in 42.

## Build 42
- `setLevels` moves each slider one step short, then taps BOOX's own `#brightness_bright_up/_down` / `#brightness_temp_up/_down`, in case BOOX applies the light only from its buttons (the user saw the light differ at max). Log lines show `BOOX light b/t` from `screen_ctm_*` to compare.

## Build 43–44 (approved mockup 29, plus gating; 43 failed CI until `android.useAndroidX=true`, 44 is the first good build)
- **Advanced access** (`AdvancedAccess.kt`): on-device ADB through **Shizuku** (`dev.rikka.shizuku:api`/`provider` 13.1.5, the app's only library; `rikka.shizuku.ShizukuProvider` is in the manifest). Commands run through the private `Shizuku.newProcess`, via reflection.
- The Settings page `Page.ADVANCED_ACCESS` (Help row) has the status card, three setup steps (Get Shizuku → GitHub releases, Open Shizuku, Connect = `requestPermission`), the **Front light test** (`settings put system screen_ctm_brightness` via Shizuku, 3 s, restore, asks Yes/No → `Prefs.lightTestPassed`), and the **Independent Front Light presets** checkbox (enabled only once the test has passed).
- **Your presets are gated**: page 2 of the panel's Front Light box shows `advancedPrompt()` unless `AdvancedAccess.independentPresets(prefs)` is true. If access worked during an earlier boot (`K_ADV_BOOT` vs the current boot time) and Shizuku isn't running, it says the tablet restarted. The button opens Settings on that page (`SettingsActivity.EXTRA_PAGE`).
- When on, `service.frontLightLevels(levels, mine)` writes `screen_ctm_*` directly, keeps the light it replaced (`K_FL_DIRECT_BEFORE`), and puts that back if the user picks the BOOX preset already showing.
- **Unknown until tested on device**: whether BOOX's light follows `screen_ctm_brightness`.
- Build 44 report: Shizuku works (permission granted; `settings put system screen_ctm_brightness 4` → exit 0, reads back 4), but **the light did not change**. `screen_ctm_*` is only BOOX's record of the light, not a control. Independent presets stay locked (test failed).

## Build 45
- The debug report has an "Advanced access" section: Shizuku state, light test result, and a read-only probe via Shizuku (`AdvancedAccess.lightProbe`). It runs `id`, lists `/sys/class/backlight`, shows each device's brightness/max and permissions, `service list` filtered for light/onyx, and getprop filtered. Next: if a backlight file is writable by shell, try it as the light test's direct method.
- Build 45 probe: the light hardware is `/sys/class/backlight/onyx_bl_br/brightness` (brightness) and `onyx_bl_ct/brightness` (warmth), 0–32, mode **-rw-rw-rw-**. `panel0-backlight` is not readable. Services: `onyx_device_service`, `lights`.

## Build 46
- `AdvancedAccess.setLight(levels, method)` writes those two files: as the app first (`METHOD_APP`, no Shizuku; SELinux may refuse), else through Shizuku `echo` (`METHOD_SHELL`). The light test records which method worked (`K_ADV_METHOD`) when the user says the light changed. `independentPresets()` needs Shizuku only for `METHOD_SHELL`, and the restart prompt only applies then. The test can run without Shizuku.
- Build 46 log: the app writing the backlight files directly is refused by SELinux (EACCES). Through Shizuku (`METHOD_SHELL`) it works: the user saw the light dim, so the test passed. Independent presets are on. Not yet seen in a log: a SuperBoox preset applied from the panel, going back to a BOOX preset afterwards, and behaviour after sleep/wake (BOOX may reapply its own level).
- Build 46 log: SuperBoox presets and level changes all went through Shizuku `echo` (17:39–17:40). After sleep/wake, BOOX put its own Bright level back.

## Build 47
- `QuickAccessService.wakeReceiver` (SCREEN_ON / USER_PRESENT): if a SuperBoox preset is active and independent presets are available, it reapplies the preset 0.9 s and 2.5 s after waking.
- Mockup `30-preset-actions.png` (`preset-actions.html`) proposes Rename (opens the Settings edit dialog) and Remove (confirmed in place) under a held preset in the panel. Approved; built in 48.
- Build 47 log: wake reapply works (17:58:30 SCREEN_ON, then USER_PRESENT, re-sets Preset 1). The user saw the panel stay over BOOX's screensaver.

## Build 48
- `wakeReceiver` also takes SCREEN_OFF and closes the panel.
- Panel: holding one of your presets shows Rename and Remove under its levels (`presetActions`). Rename opens Settings with `EXTRA_EDIT_PRESET` → `editLightPreset(p, backToPanel = true)`, which closes Settings and reopens the panel when the dialog is dismissed. Remove asks in place (`confirmRemove`); removing the preset in use puts back the BOOX preset it replaced.

## Build 49
- The Front Light box opens on the page holding the preset in use (`activeLightPage`): page 1 for BOOX's presets, or the page with your active preset.
- Rename happens in the panel: a name field with Cancel / Save in place of the Rename/Remove row (`renaming`, `renameField`, `endRename`). Done on the keyboard saves; Back cancels. Focusable panel windows use `SOFT_INPUT_ADJUST_PAN`. The Settings `EXTRA_EDIT_PRESET` path remains but the panel no longer uses it.

## Build 50
- Rename field puts the cursor at the end of the name when it gets focus (it had started at the beginning).
- Mockups 31–33 (`clipboard.html`): clipboard hub replacing Quick Controls / Last Opened / Quick Access, Recent Files moved to the left of the Quick Access bar, option to cover the apps, held-clip actions, Settings → Clipboard. Awaiting decisions (cover apps or not; which actions).

## Build 51: Clipboard hub
- Approved from mockups 31–33 with "covers the apps" as the default (setting: Clipboard covers the apps too). The user liked tap/hold for a clip's actions. "Note it" left out (BOOX Notes has no text box to paste into); links get Open instead.
- `ClipHistory.kt`: history in its own prefs file `clipboard` (not in backups), images copied to `filesDir/clips` and served by `ClipProvider` (authority `com.superboox.quickaccess.clips`). Dedupe by text (moves to top), limit applies to unpinned clips, password managers ignored by default (`DEFAULT_IGNORED`), clips marked `IS_SENSITIVE` skipped.
- Capture: `checkInForeground` when the panel window gains focus (Android only lets the focused app read), skipping unchanged clips by `ClipDescription.timestamp`. `checkInBackground` (mode `every`, Shizuku allowed): on clicks, toasts and app switches (debounced 700 ms) calls IClipboard as `com.android.shell` through `ShizukuBinderWrapper`, with `HiddenApiBypass` (dependency `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`). Failures are recorded in the debug report ("Clipboard" section, counts only, never clip contents). Accessibility config now also takes `typeViewClicked|typeNotificationStateChanged`.
- Panel: `Mode.CLIPBOARD`; top bar Clipboard (lit while open) | New Note | ⚙, or Recent Files when the button is off; "Recent" at the left of the Quick Access bar. Hub: search field kept across redraws (`clipSearch`), chips, `CLIPS_PER_PAGE` 4 / `CLIPS_PER_PAGE_FULL` 7, swipe or ‹ › to page. Paste in app: `QuickAccessService.pasteIntoApp` (ACTION_PASTE on the focused input). Focusable panel windows use `SOFT_INPUT_STATE_HIDDEN`.
- Settings → Clipboard (`clipboardSection`): button, covers apps, keep last N, images, keep after restart, ignored apps, catching mode, clear history.

## Build 52
- User findings after 51 (handwritten note): all suggestions approved; New Note hold = BOOX's own new-note screen.
- Hold Clipboard → Settings › Clipboard (`openSettingsPage`). "Recent" label on the Quick Access bar: single line, min width.
- Hold New Note → `NoteOpener.openNewNoteSetup`: backs out of an open note or opens the Notes library (taps the launcher's Notes tab if needed), then taps the library's new-note button found by id/label (`newNoteButton`). BOOX's new-note screen isn't exported, hence the tap. On failure it logs the library tree: use it to pin down the button.
- Recent Files: notes are only recorded from BOOX's `note_name` view (`NotesScreen.trustedTitle`); tool pop-ups like "Pen 0.30mm" are skipped and filtered out of saved recents (`Recents.isToolName`).
- Clipboard: BOOX Notes lasso copies are a `content://com.onyx.android.note.onyx.fileprovider/…/CopyPoints/…` link, not a picture. Kept as `Kind.HANDWRITING` (label "Handwriting from BOOX Notes", original label/mime types stored to put it back). If the linked file decodes as a picture it's kept as an image. The log line "file clip: …" records type, size, first 32 bytes, picture or not: check it to see if a preview is possible.
- Mockups 34–35 built: Settings › Clipboard "What to keep" (Text, Links, Images, Handwriting, Codes off by default; passwords/private never) with how each is caught, and "While the clipboard is open, still show" (Quick Controls, Last Opened, Apps; all off by default; replaces covers-apps, migrated) with `MiniPanel` drawings. Clips per page: 7 minus 2/1/3 for what still shows, at least 2.
- Mockups pending: rename Quick Access apps (4), drag in the panel's edit view (5), visual previews for confusing settings (8).

## Build 53
- User after 52: rename only the apps at the bottom (renaming SuperBoox's label for documents isn't worth it unless it renamed the real file, which BOOX notes don't allow). Done in Settings › Apps at the bottom (✎, dialog with Save / Reset / Cancel; `renameDockApp`).
- "Rece" cut off: the label sat in a column as wide as the clock icon, so it wrapped (MATCH_PARENT children don't widen a WRAP LinearLayout). Fixed in 52 with `lp(WRAP, WRAP)` + one line; "Add" given the same.
- Drag handles (≡) in the panel's Edit Quick Access view (`QuickAccessPanel.dragHandle`), arrows kept.
- Handwriting: `ClipHistory.describeFile` logs the linked file's first bytes (hex + readable), and inside gzip/zip, to work out BOOX's stroke format for a preview.
- Settings drawings from mockup 38: Quick Controls "Show me" per control (`controlGuide`), Advanced access "What changes?" (`independentGuide`).
- Hold Clipboard stays as is (opens Settings › Clipboard). User: deep settings stay in Settings.
- Gesture / Look & size drawings (37) on hold: user would rather see the real panel/strip while changing a setting. Mockup 39 proposes it.

## Build 54: live preview (mockups 39–40, approved)
- `QuickAccessService.showPreview(clipboard)` / `hidePreview()`: a real `QuickAccessPanel` (with `previewOfClipboard`, `previewTag`) in its own overlay window, exactly the panel's width (`QuickAccessPanel.sheetWidth`, shared with the panel), on its edge, `FLAG_NOT_TOUCHABLE` + not focusable so taps reach Settings. Re-shown at the new size on rotation; hidden when the real panel opens.
- `stripPreview`: while Settings › Gesture is open the edge strip is drawn solid black (`EdgeHandleView(solid = true)`) at its real place and size.
- Settings: `previewOn` (saved across rotation), turned on by changing Panel width / Menu size (Look & size) or the "still show" ticks (Clipboard), or by "Preview the panel"; off when leaving the page or on pause. `insetForPreview` measures Settings' window on screen against the panel's edge and width and pads the covered side (keeps at least 280dp for Settings), so it adapts in portrait, landscape and split screen. The small MiniPanel drawings now only show when the accessibility service isn't running.
- Look & size's drawings from mockup 37 were replaced by this, as the user preferred.

## Build 55: handwriting preview, step 1
- Build 54 report: BOOX's lasso copy is `content://com.onyx.android.note.onyx.fileprovider/external/.noteCache/CopyPoints/<uuid>`, label `NOTE_SHAPES`, mime `text/uri-list`, and the link is a **folder** (read gives EISDIR). Putting it back on the clipboard fails: "UID … does not have permission" (Android won't let SuperBoox re-share BOOX's link).
- `ClipHistory.handwritingPreview`: through Shizuku (`AdvancedAccess.shellBytes`, raw output) lists the folder (`/storage/emulated/0/.noteCache/CopyPoints/<uuid>`), copies its files into `filesDir/clips/h<time>/`, logs each file's size, first 64 bytes (hex) and readable text, and uses the first file that decodes as a picture as the preview (`h<time>.png`). The clip's `image` is the png or, without one, the folder, so deleting the clip removes the kept files (`discardFiles`).
- `putBack` for handwriting falls back to the link as text (`text/uri-list`, label kept) when Android refuses the link itself; whether BOOX pastes that is untested.
- Next: from the logged file format, draw the strokes into a preview when BOOX's folder has no picture.

## Build 56
- Optional `MANAGE_EXTERNAL_STORAGE` ("All files access", user switch in Android settings; `tools:ignore="ScopedStorage"`). Settings › Clipboard › Handwriting previews explains it and opens the switch (`openAllFilesAccess`). `ClipHistory.handwritingPreview` reads BOOX's copy folder directly when `hasFileAccess()`, else through Shizuku, else logs that it needs one of them. Only that folder is read. Debug report shows both.
- Stroke drawing still waits on a report with the logged file format.

## Build 57
- Build 56 report: BOOX's copy folder holds `shape.pb` (protocol buffers: per shape an id, times, colour as a signed varint, bounds as JSON `{"bottom","left","right","top"}`, matrix JSON, style JSON, and the points) and `extra` (JSON: copyTimestamp, pageRect 1860x2480, pastePosition, viewPortRect…). No picture. `BooxShapes.render` parses the protobuf generically and finds each shape's points as a float array (stride 2–8, absolute / relative / normalised) or repeated {x,y} messages that land inside its bounds; falls back to non-shape messages within all bounds. Unrecognised: `BooxShapes.describe` logs the first shape's field tree. `ClipHistory.ensurePreview` draws clips kept before (once; marks `drawn-failed`).
- Hold New Note tapped `create_text_new` and made a text note ("Text-1"). Now only a button whose id/label says "note" (not text/folder/search) is tapped, the candidates are logged, and the screen is held (`cover`) until BOOX's screen shows, like opening a pinned note.
- Edit Quick Access drag crashed (NPE in ViewGroup.dispatchDraw): the list was rebuilt inside the touch while the row had translationZ. Now no translationZ in the panel and the rebuild is posted after the touch.

## Build 58
- Build 57 report: drag in the edit view fixed (user confirmed). Handwriting still not drawn: a shape is fields 1 id, 2/3 times, 4 colour, 5 fixed32 (≈5.9, pen width?), 7 bounds JSON, 8 matrix JSON, 11 pen JSON (dpi 320, maxPressure 4095, displayScale…), 12 varint 5 (shape type?), 17 line style JSON, 21 "[]", **24 bytes 2112 (points, not LE float pairs)**, 26 "{"repo":{}}". 22 shapes per copy. `BooxShapes.bruteForce` now tries float/int/double × LE/BE × header 0–32 × record size × x/y position × absolute/relative/normalised and keeps a reading whose points spread inside the bounds; `describe` logs non-printable fields as hex. Earlier failed clips retry (marker `drawn-failed-2`).
- Hold New Note: BOOX home's Notes widget has `create_text_new` and `create_new` (no "note" in the id), so nothing was tapped and the hold timed out. Search shortened to 6 s while asking the user which BOOX action opens the new-note template screen.

## Build 59
- Build 58 report: handwriting drawn ("handwriting drawn from shape.pb (916x323)"); user confirmed it shows in the clipboard. Bytes layout found by `bruteForce` (not yet pinned down exactly).
- Clipboard filter chips: Handwriting added (Images is pictures only); the chip row scrolls sideways when it doesn't fit.
- Hold New Note: the user's manual run showed BOOX home's Notes widget `create_new` ("+") opens a new notebook directly ("Notebook-2", via com.onyx MiddlewareActivity → ScribbleActivity); no separate settings screen. Assumed target: the editor's Template panel ("Tem…" in its tool column). Now taps `create_new`, waits for the new note, taps its Template button (`templateTick`), logs the editor screen if it can't find it. Awaiting user confirmation that this is the screen they mean.
- User prefers no further dependence on Shizuku (handwriting can use All files access instead).
- Tour mockups 41–43 (`tour.html`) awaiting approval.

## Build 60
- Hold New Note fixed from the build 59 report: BOOX's `create_new` opens its **create-note screen** inside ScribbleActivity (note_name EditText, page_layout Single Page/Scroll, template tabs Recent/Regular/Work/…, `btn_ok_vertical` "Create" / `btn_cancel_vertical` "Cancel", `tv_selected_bg`). That is the screen the user wants. `NotesScreen.isCreateNoteScreen` detects it and the held screen is revealed at once (`createScreenTick`). The setup now always goes through BOOX's home (Back from a note landed on the user's own launcher). That screen's name field isn't recorded as a recent note.
- First-run tour (mockups 41–43, plus a permissions step at the user's request): `tourStep` in SettingsActivity, shown on first launch (`Prefs.tourDone`, not backed up), 8 steps: welcome, accessibility (required), permissions (background running via REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Modify system settings, All files access), try the swipe (live black strip, ticks when `panelOpens` grows), around the panel (live panel preview + inset), clipboard, Advanced access (optional; Back returns to the tour), done. Skip / Previous / Next / dots. Settings › Help › Show the tour again.

## Build 61
- Build 60 report: hold New Note works (create-note screen up ~0.4 s after the tap). User confirmed the tour is helpful and the live panel in it intuitive.
- BOOX's create-note screen opens on the last template's category (Work). Build 61 tapped Recent; the user then preferred BOOX's last-used category, so build 62 leaves it.

## Build 62
- Tour: the clipboard step shows the real panel open on the clipboard; the Advanced access step shows it open on Front Light's own presets (`previewLightPresets`, applied after construction since those fields are declared after `init`); the try-it step mentions hold New Note / hold Clipboard once the panel has been opened.

## Next step
- Build 27 log confirmed: Auto-rotate direct toggle and hold-for-menu, and Previous app (moved to position 4). Build 28: the apps grid swipes sideways too (`SwipeRow` with `vertical = true`). Not yet seen in a log: quick repeated taps queued in one session. Build 29: holding Previous app opens recent apps (`GLOBAL_ACTION_RECENTS`). Build 29 log: the queue works (4 quick taps in one session), and so does holding Previous app. Build 30: Auto-rotate taps switch directly even mid-session (they had been queued as a tile tap, which opened the BOOX menu). Note: Airplane on turns Wi-Fi/BT off; the panel may show them on for up to 8 s (optimistic state) before system reads correct it.

## Open ideas / backlog
- Store the note id in pins when pinning, so lookup by title isn't needed.
- Last Opened Document only returns to the last reading app; titles depend on that app.
- Mockups `docs/mockups/10–14` show the approved Supernote design, `15–16` the approved bordered Settings (`settings-borders.html`), `17` the approved icons (`panel-icons.html`), `18/20/21` Quick Controls (`quick-controls.html`), `22` Previous app (`previous-app.html`), `23` proposed Settings menus (`settings-menus.html`, approved, built in 36), `24` Front Light (`front-light.html`, built in 36), `25` tap modes (built in 37), `26–28` proposed Custom sliders, own presets, backup & restore, drag to reorder (`front-light-more.html`, approved, built in 38), `29` proposed Advanced access via Shizuku (`advanced-access.html`, approved, built in 43); `01–09` show the older design.

## Docs refresh (after build 62)

- README rewritten for new users: getting started with the tour and permissions, a tap/hold table for the panel, Clipboard, Front Light presets, Settings sections, Advanced access, privacy (permissions list) and an updated "How it works".
- Launch images regenerated from `docs/launch/launch.html` (built from the `docs/mockups/clipboard.html` helpers, made-up names, Walden). The images are `1-panel`, `2-clipboard`, `3-light` and `4-settings`.
- Render script (scratchpad `render-launch.mjs`): Playwright at 2x, screenshots `.stage` for `#panel`, `#clipboard`, `#light` and `#settings`.
- `docs/launch/reddit-post-2.md` is the update post draft.

## Build 63: hold New Note, Recent tab and return

- The create-note screen taps BOOX's **Recent** template tab again; the user changed their mind after build 62. Recent shows the template used last.
- **Return to the starting point.** `QuickAccessService.openNewNoteSetup` records `NewNoteOrigin`: the app in front, plus the open note's title if it was a note. BOOX's home is never recorded as an origin.
  - `NoteOpener` calls `newNoteScreenShown()` once the create screen is up, which arms the watch.
  - When a window event brings `com.onyx` (BOOX home) to the front, `newNoteBackCheck` runs 300 ms later. If the active root is BOOX home and not the create-note screen, it calls `reopen(origin)`.
  - `reopen` handles each kind of origin: Notes reopens the note, NeoReader its book, a launcher package gets a HOME intent, and anything else is launched as an app. `openPreviousApp` now uses `reopen` too.
  - The same check also catches Back out of the newly created note. Going to any other app, or 3 hours passing, cancels the watch.
- Reddit stock images are in `docs/launch/reddit/` (hero with callouts, clipboard, light, tour, live, settings). They come from the same `launch.html` (`#hero`, `#tour`, `#live`) and are rendered by the scratchpad script `render-reddit.mjs`.

## Build 64: service stopping from Clipboard settings

- **User report.** Toggling Clipboard → "While the clipboard is open, still show" (which rebuilds the live preview each time) or opening the panel stopped the service. Settings still said "running", because it only checked the secure setting. Android doesn't rebind a crashed accessibility service until it's toggled.
- **Fixes**
  - Clip pictures are decoded scaled on **both** sides, so a wide picture is never full size. They're kept in an `LruCache` of up to 16 MB, guarded against OOM.
  - Drawing handwriting from `shape.pb` (brute force can be slow) moved off the main thread. `ClipHistory.needsDrawing`/`draw` run on the panel's `io` executor and refill afterwards. `ensurePreview` is cheap now, and capture no longer draws.
  - Building the panel or preview is guarded (`panelFailed`: logs and shows a toast). Each clip row is guarded too.
  - The uncaught-exception handler stores `K_LAST_CRASH`.
  - The Settings status card now has three states:
    - **running:** Open panel / Restart service. `QuickAccessService.restart()` clears its overlays and re-adds the strip.
    - **on but stopped:** shows the crash time. Restart service uses Shizuku if allowed (`settings put secure enabled_accessibility_services` without the service, then with it). Otherwise it opens `ACCESSIBILITY_DETAILS_SETTINGS` for the component.
    - **off.**
- **Still unknown.** The exact exception: the next debug report's `[crash]` entry has the stack.

## Build 65: the actual crash

- Report stack: an NPE on `ClipFilter.ordinal()` in `clipMatches`, reached from `QuickAccessPanel.<init>` → `show(CLIPBOARD)` from `showPreview`.
  - Cause: the clipboard state fields (`clipFilter`…) were declared below the `init` block, so they were still null when a clipboard preview drew the hub from `init`. Every tick under "still show" and every "Preview the panel" tap rebuilt the preview, and each rebuild crashed the service.
  - Fix: those fields now sit above `init`.
  - Keep any state that `show()` reads in clipboard mode above `init`.
- Keyboards are no longer counted as the app in front (`keyboardPackages` from `InputMethodManager`). Leaving the create-note screen once tried to return to `com.onyx.latinime`.

## Build 66: Settings layout crash

- **Report.** An NPE in `ViewGroup.dispatchCollectViewAttributes` (a null child during layout), five levels deep, so it was the Settings page `list`. It happened within seconds of using Settings, twice. No preview was shown.
- **Cause (likely).** `render()` emptied and rebuilt `list` synchronously from inside tap or check handlers of views in `list`. On a device with several accessibility services, that can leave a hole in the children array.
- **Fix.**
  - `render()` now posts a coalesced `rebuild()`. `onResume` rebuilds directly.
  - `open()` and `tourGo()` scroll to the top before rendering. The posted rebuild captures the scroll position, so the old `scroll.post` order would have restored the previous page's position.
  - Page changes are logged as `[settings] page X`.
- **Still to watch.** The panel calls `show()` inside its own click handlers in the same way. It hasn't crashed, but if a similar trace shows the panel's depth, post it there too.

## Build 67: settings can't stop the service

- **Report.** Dragging Bluetooth in Settings → Quick Controls crashed the process, and the service with it. The Settings `dragHandle` used `translationZ`, the same pattern that crashed the panel's drag in build 55. It was removed; rows lift with a border only.
- **Review of Settings menus.**
  - Every tap, check, picker and drop handler rebuilds through the posted `render()` (build 66).
  - Sliders save only when released. Dialogs are separate windows.
  - No other `translationZ`, `bringToFront` or off-thread view changes were found.
- **`MainGuard` safety net** (installed in `SuperBooxApp`). It runs `Looper.loop()` inside try/catch on the main thread. On an error it:
  - logs `E [recovered]`
  - calls `QuickAccessService.restart()` (clears the panel, preview and cover; re-adds the strip)
  - calls `SettingsActivity.recoverIfOpen()`, which finishes the broken screen and, 500 ms later, starts a fresh one on the same page (Settings is singleTask)
  - shows a toast

  More than 6 errors within 10 s are rethrown, so a persistent fault still shows up as a crash in the report.

## Build 68: auto-rotate off kept the screen's orientation

- Writing only `ACCELEROMETER_ROTATION=0` locked the screen to the stored `USER_ROTATION`, which flipped it 180° on the user's tablet.
- `QuickControls.toggleRotation` now writes `USER_ROTATION` = the current display rotation first (as Android's `RotationPolicy` does), and logs "locked at N°".

## Build 69: Wi‑Fi section in the debug report

- `WifiProbe` collects two levels of detail:
  - **Basic (any app):** band, RSSI, link speeds, Wi‑Fi standard, validation.
  - **With Advanced access:**
    - `cmd wifi status`
    - `/proc/net/wireless`
    - power-save grep
    - the `WifiScoreReport` history
    - disconnect/roam events
    - uptime
    - ping to the router (from `ip route get`) and to 1.1.1.1
    - a 2 MB curl download from speed.cloudflare.com, if curl exists
- These commands run as shell; the app itself still has no INTERNET permission.
- SSIDs are hidden. MACs and BSSIDs become `ap-xxxxxx` (a SHA-256 prefix, the same in every report).
- **Purpose:** separate RF or hardware causes from software ones. The user takes report A while slow, then report B right after toggling Wi‑Fi off and on, in the same place.

## Build 70: Front Light stays in sync with BOOX's panel

- **Report.** SuperBoox had set Bright, the user then picked Soft in BOOX's Control Center, and the panel still showed Bright. `K_FL_PRESET` was only updated by SuperBoox's own actions.
- **Fix: reading BOOX's panel.**
  - A tap event or window-state event from systemui schedules `lightSync` (600 ms).
  - `QuickControls.syncFromScreen` reads the selected preset with `presetButtons`/`selectedPreset`, and saves it if it changed. It also clears your own active preset and learns that preset's levels.
- **Fix: light-settings changes.**
  - A `ContentObserver` watches `screen_ctm_brightness/temperature` and `ctm_*_state_key`.
  - After 800 ms, `syncFromLevels` matches the current signature against the learned per-preset signatures (`K_FL_SIGNATURES`). It is skipped while a SuperBoox action is in progress, still optimistic, or while your own preset is active.
- **Learning.** Signatures are also learned 1.5 s after SuperBoox switches to Bright, Soft or Off.
- **Backups.** `K_FL_SIGNATURES` is device-specific, so backups skip it.
- **Build 71:** the Wi‑Fi report no longer dumps the multi-kilobyte `timestamp_ms=` link-stat lines. `linkStats` summarises the newest 6 samples:
  - signal and tx/rx link speed
  - channel busy %
  - retries per sent packet in each interval
  - data stalls and score

  The power-save grep excludes those lines, and long lines are capped at 300 characters.
- **Build 72:** holding Front Light calls `QuickAccessService.checkFrontLight()`. It does one of four things:
  - **Your own preset active** (with independent presets): skipped.
  - **Levels match a learned preset:** that preset is taken (`syncFromLevels`).
  - **Levels match the saved preset's learned signature:** nothing to do.
  - **Otherwise:** a `QcTask(check = true)` runs a Control Center session behind the cover. `checkPreset` scrolls if needed, then `syncFromScreen` saves the preset and learns its levels.

  `panel.refreshLight()` redraws on the page holding the active preset.

## Releases (from 1.0.0)

- **Stable releases.** Run the "Build APK" workflow manually with `release=X.Y.Z`, or push a `vX.Y.Z` tag (this session can't push tags; it got a 403). Either way the workflow builds versionName X.Y.Z, tags `vX.Y.Z` and publishes it as Latest, with notes from `docs/releases/vX.Y.Z.md`.
- **Pre-releases.** Every other push builds `NEXT_VERSION-dev.<run>` (NEXT_VERSION is set in the workflow, currently 1.1.0) and publishes it as a GitHub pre-release. Obtainium only offers those with "Include prereleases" on.
- **versionCode** is still the run number, so builds always install as upgrades.
- **v1.0.0** was published from commit 24a5c02, the build 72 code. Its release check: signed with the repo key, versionName 1.0.0.

## 1.1.0-dev.77 device checks (Note Air 6C, over adb) and the handwriting fixes

- **Checks.** dev.77 passed: pinned note from another app, Add inside a note, Add in the Notes library, Last opened, hold New Note, Add on BOOX's Library. The handwriting check failed three ways; fixed as below.
- **Testing over adb.** `input stylus motionevent DOWN/MOVE/UP` drives BOOX's lasso and `input stylus tap` pastes, but injected input can't draw strokes (BOOX reads the pen device, and adb can't write to it). `uiautomator dump` unbinds the accessibility service for about 2 s: don't use it mid-action. The app is debuggable, so `run-as com.superboox.quickaccess` reads `files/clips`.
- **Previews.** `shape.pb` field 24 is one 24-byte record per point: big-endian floats, x and y first (then pressure, size, time). `BooxShapes.recordPoints` reads that first. `bruteForce` had settled on a wrong reading with huge outliers (pictures 916x16); such pictures are redrawn once (`flatPicture`, marker `redrawn`).
- **Every lasso copy.** BOOX empties `CopyPoints` at each copy, so only the last copy before the panel opened was kept. `ClipHistory.checkNotesCopies` (a `FileObserver` on `CopyPoints` and the folder above it, plus taps and toasts from Notes) keeps each new folder as it appears; needs All files access. `recordUri` then only moves that clip to the top.
- **Putting an older copy back** (works with Notes running; confirmed by pasting the oldest of three copies):
  - BOOX's `ShapeClipboardUtils` takes any clip link whose path contains `.noteCache/CopyPoints`, and `FileUtils.getRealFilePathFromUri` turns it into a folder. If the authority contains "fileprovider" it uses the path alone (`/external/…` → shared storage) and never asks the provider; any other authority is asked for `_data`, which is why the old mirror link on `…quickaccess.clips` was ignored.
  - So `ClipProvider` has a second authority, `com.superboox.quickaccess.fileprovider` (`ClipHistory.NOTES_LINK_AUTHORITY`). `putBack` writes the clip's kept `shape.pb` and `extra` back into `CopyPoints/<uuid>` (`restoreHandwriting`, All files access) and puts `content://…quickaccess.fileprovider/external/.noteCache/CopyPoints/<uuid>` on the clipboard. SuperBoox owns that link, so Android allows it.
  - A new clip on the clipboard makes Notes load it. Changing the files behind a link already on the clipboard doesn't: Notes keeps what it loaded until the clipboard changes or it restarts.
  - Found by reading BOOX Notes' code with `dexdump` (`/system/app/knote2-release`); other BOOX firmware may differ.

## 1.1 proposal: Tasks (mockups 44–48, `docs/mockups/tasks.html`)

- **Scope.** Ideas taken from Agile Ink (a closed-source, commercial app; none of its code or branding is used). Everything stays offline, with no handwriting recognition; the user chose this.
- **Step 1:**
  - "Make task" on clipboard clips. Handwriting becomes the task; a typed title is optional.
  - A new-task card in the panel, recording the source note and a due date.
  - A Tasks view behind a new top-bar cell (Clipboard | Tasks | New Note | ⚙), with open/today/done.
- **Step 2:** Collect, a tab listing BOOX notes changed since they were last marked reviewed (from NoteModel `updatedAt`).
- **Settings → Tasks:** coverage like Clipboard, auto-clear of done tasks, Collect scope. Tasks are included in backups.

## This app: BOOX Optimize and EinkWise

- Mockup `docs/mockups/51-this-app.png` (approved by the user). A strip just above the apps names the app in front of the panel and has two launch buttons: **Optimize** and **EinkWise**. Hidden on BOOX home, the default home app, SystemUI, inside SuperBoox, on non-ONYX devices, and when Settings › Apps at the bottom › "Show This app" is off.
- How BOOX opens them (Note Air 6C capture plus reading BOOX's launcher/SystemUI code): no activity. SystemUI's `EACActionReceiver` (exported, no permission, no sender check) shows the `EInkCenterUI` overlay when it receives a broadcast:
  - Optimize: `com.onyx.SHOW_EAC_SETTINGS_VIEW_REQUEST`, String extra `args_component` = the app's launcher component flattened ("pkg/activity"), the same as the BOOX launcher sends from hold app → Optimize.
  - EinkWise: `action.open.eink.center.request` (device-wide E-Ink Center, no extras).
- `EinkMenus` sends them; `QuickAccessService.openEinkMenu` hides the panel first and refuses while a Quick Controls session has the Control Center open. Changes in BOOX's panel apply live (SystemUI never stops the app; the user confirmed DPI changes live).
