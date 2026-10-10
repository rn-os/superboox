# SuperBoox: handoff / status (as of build 16)

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

## Next step (in progress)
1. The user installs **build 16** and sends a debug report. Read the lines starting with **`code strings …`**:
   - the extra keys and actions used by `OpenNoteByDocIdActivity`;
   - what `MiddlewareActivity` passes to `ScribbleActivity`.
2. Set `NotesDb.openIntent()` to the right extra key, value and action. Alternatively, launch the exported `ScribbleActivity` directly with the extras that `MiddlewareActivity` uses.
3. Flip `NoteOpener.DIRECT_OPEN = true`. Keep the 3.5-second confirmation and library fallback. Then ship and confirm through a report that the log shows `opened by id`.

## Open ideas / backlog
- Store the note id in pins when pinning, so lookup by title isn't needed.
- Last Opened Document only returns to the last reading app; titles depend on that app.
- Mockups `docs/mockups/10–14` show the approved Supernote design; `01–09` show the older design.
