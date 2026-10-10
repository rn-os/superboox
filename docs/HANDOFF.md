# SuperBoox: handoff / status (as of build 27)

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

## Next step
- The user tests builds 26/27: quick repeated taps, the Auto-rotate permission flow, and moving Previous app.

## Open ideas / backlog
- Store the note id in pins when pinning, so lookup by title isn't needed.
- Last Opened Document only returns to the last reading app; titles depend on that app.
- Mockups `docs/mockups/10–14` show the approved Supernote design, `15–16` the approved bordered Settings (`settings-borders.html`), `17` the approved icons (`panel-icons.html`), `18/20/21` Quick Controls (`quick-controls.html`), `22` Previous app (`previous-app.html`); `01–09` show the older design.
