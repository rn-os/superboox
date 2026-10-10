# SuperBoox: handoff / status (as of build 51)

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

## Build 51 (mockups 31–33 went out with 50)
- Rename field puts the cursor at the end of the name when it gets focus (it had started at the beginning).
- Mockups 31–33 (`clipboard.html`): clipboard hub replacing Quick Controls / Last Opened / Quick Access, Recent Files moved to the left of the Quick Access bar, option to cover the apps, held-clip actions, Settings → Clipboard. Awaiting decisions (cover apps or not; which actions).

## Next step
- Build 27 log confirmed: Auto-rotate direct toggle and hold-for-menu, and Previous app (moved to position 4). Build 28: the apps grid swipes sideways too (`SwipeRow` with `vertical = true`). Not yet seen in a log: quick repeated taps queued in one session. Build 29: holding Previous app opens recent apps (`GLOBAL_ACTION_RECENTS`). Build 29 log: the queue works (4 quick taps in one session), and so does holding Previous app. Build 30: Auto-rotate taps switch directly even mid-session (they had been queued as a tile tap, which opened the BOOX menu). Note: Airplane on turns Wi-Fi/BT off; the panel may show them on for up to 8 s (optimistic state) before system reads correct it.

## Open ideas / backlog
- Store the note id in pins when pinning, so lookup by title isn't needed.
- Last Opened Document only returns to the last reading app; titles depend on that app.
- Mockups `docs/mockups/10–14` show the approved Supernote design, `15–16` the approved bordered Settings (`settings-borders.html`), `17` the approved icons (`panel-icons.html`), `18/20/21` Quick Controls (`quick-controls.html`), `22` Previous app (`previous-app.html`), `23` proposed Settings menus (`settings-menus.html`, approved, built in 36), `24` Front Light (`front-light.html`, built in 36), `25` tap modes (built in 37), `26–28` proposed Custom sliders, own presets, backup & restore, drag to reorder (`front-light-more.html`, approved, built in 38), `29` proposed Advanced access via Shizuku (`advanced-access.html`, approved, built in 43); `01–09` show the older design.
