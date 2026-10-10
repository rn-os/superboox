# SuperBoox

A Supernote-style **Quick Access panel** for ONYX BOOX e-ink tablets (Note Air, Note Air 2/3, Tab, etc.).

Swipe in from the screen edge on any page and a panel slides in with:

- **Quick Access**: your pinned BOOX notes, documents, folders, apps and custom shortcuts
- **Recent**: the newest files from a folder you pick, such as your BOOX Notes PDF export folder
- **Apps** across the bottom, like the app row in the Supernote sidebar
- An optional row of **Back / Home / Recents / Alerts / Capture** buttons

It's black on white with no animations, so it draws cleanly on e-ink.

## Install

1. Build it with `./gradlew assembleDebug` (needs JDK 17 and the Android SDK), or download the APK from the **Build APK** GitHub Actions run (artifact `superboox-debug-apk`).
2. Sideload it: `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to the device and open it.
3. Open **SuperBoox** and tap **Accessibility settings**, then turn on **SuperBoox Quick Access**.
4. **BOOX only:** go to *Settings → Apps → App Management → SuperBoox* and allow it to run in the background (turn off freezing). Otherwise BOOX may stop the service after the device sleeps.
5. Swipe up or down along the right edge of the screen, like the Supernote side bar. The touch strip covers the middle third of the right edge, and a small black marker shows where it is. You can switch to swiping inward or double-tapping in Settings.

## Updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs SuperBoox from this repo's releases and notifies you when a new build is out.

1. Install Obtainium on the BOOX.
2. In Obtainium, tap **Add App** and paste `https://github.com/rn-os/superboox`. Or open `obtainium://add/https://github.com/rn-os/superboox` on the device to jump straight there.
3. Tap **Add**, then **Install**.

Each release is tagged with the APK's own version (`0.1.0-build.N`), so Obtainium's version check works without extra settings. Builds 1 to 6 were pre-releases with `build-N` tags, and Obtainium ignores them.

## Ways to open the panel

| Method | Notes |
|---|---|
| Edge gesture | Swipe inward, swipe along the edge, or double-tap. You choose the edge, the strip size and its position in Settings. |
| **Quick Access** launcher icon | An invisible activity that opens the panel. You can bind it to a BOOX system gesture or the Navigation Ball's "open app" action if you don't want a reserved strip on the screen edge. |
| Quick Settings tile | Add **Quick Access** to the notification shade. |
| adb / automation | `adb shell am start -a com.superboox.quickaccess.OPEN_PANEL` |

## Using the panel

The panel follows the Supernote sidebar:

- **Recent Files**: notes and documents you opened recently, newest first. Files from the folder set in Settings → Recent files are included.
- **New Note**: opens BOOX's quick-note screen. If the firmware doesn't allow that, it opens Notes instead.
- **⚙**: SuperBoox settings.
- **Last Opened Note**: reopens the last note you had open. SuperBoox reads its name from the editor's title bar and keeps the list on the device.
- **Last Opened Document**: returns to the reading app you last used (NeoReader, KOReader, Kindle and so on), where the book is still open. It's grayed out until you've read something.
- **Quick Access**: your pinned items. Tap **Add** to pin something:
  - inside a note, Add pins that note;
  - in the Notes library, Add opens a picker where you tick several notes or folders, then tap **Add (n)**;
  - anywhere else, Add pins the current app.
- **Long-press** an item to enter edit mode, with ↑ ↓ to reorder, 🗑 to remove, and **Done** to finish.
- **Apps** show 4 per page (2 × 2). Use ‹ › to page through them.
- **Menu size** (Settings → Gesture) scales text, icons and touch targets. Large is the default. The Back / Home / Recents / Capture row is off by default and can be turned on in Settings.

## BOOX Notes integration

BOOX Notes keeps its notes in a private database and has no documented "open note X" intent. SuperBoox uses three approaches instead:

1. **Notes in the dock.** The BOOX Notes app (`com.onyx.android.note`) is placed in the dock by default.
2. **Pinned notes, opened directly when possible.** On firmware that exposes BOOX's note database (for example, Note Air 6C with firmware 4.3), SuperBoox looks up the note's ID by its name. It then opens the note through BOOX's own "open note by ID" screen, skipping the library. SuperBoox reads only note names and IDs, never their contents. If that doesn't work, it falls back to the method below.
   **Pinned notes, opened by name.** Tapping a pinned note brings BOOX Notes to the front. If a different note is open, SuperBoox presses Back until it reaches the library. It then scrolls the library to the top and taps the note, paging down if it has to. If the note isn't in the current folder, it goes up a level and looks again. Notes inside folders are written as a path: `Work / Meetings / Standup`.
   - The easiest way to pin a note: open BOOX Notes, open the panel, tap **Pin a note from this screen** and choose the note from what's shown.
   - This depends on the note names being on screen in the Notes library. If a firmware update changes that screen, it may need adjusting.
3. **Files and custom intents.**
   - Pin exported note PDFs or any document or folder (Storage Access Framework), or set the export folder as **Recent files**.
   - If you find a firmware-specific deep link, add it as a **Custom intent**. To find one, open the note and run `adb shell dumpsys activity activities | grep -i onyx`.

If your firmware uses a different Notes package, change it under **Advanced** in Settings.

## Reporting bugs

Open **SuperBoox → Troubleshooting → Copy debug report** (or **Share…**) and paste the result into a bug report. The report contains:

- Device and firmware info, the BOOX apps installed, and your SuperBoox settings and pins.
- A log of recent events: gestures, which app was in front, launches, panel errors and crashes.
- Each step of opening a BOOX note. When a note can't be found, the log includes an outline of what BOOX Notes had on screen.

The log stays on the device unless you copy or share it. **Clear log** empties it.

With a computer you can also watch the log live: `adb logcat -s SuperBoox`.

## How it works

```
QuickAccessService (AccessibilityService)
 ├─ EdgeHandleView      thin TYPE_ACCESSIBILITY_OVERLAY strip, detects swipe / double-tap
 ├─ QuickAccessPanel    full-height sheet: header, system actions, pins, recent, app dock
 ├─ NoteOpener          relaunches BOOX Notes and taps through "Folder / Note"
 └─ tracks the foreground app (for "Pin this app" / "Pin a note from this screen")
SettingsActivity        setup, gesture, pins, dock, recent folder, advanced
OpenPanelActivity       invisible trampoline (launcher icon, BOOX gestures, adb)
PanelTileService        Quick Settings tile
Prefs / Pin             SharedPreferences + JSON
```

It uses an accessibility overlay instead of `SYSTEM_ALERT_WINDOW` for these reasons:

- The overlay draws above everything, including the BOOX launcher and system screens.
- BOOX leaves accessibility services running.
- It can run global actions such as Back and Screenshot.
- It can read the Notes screen, which pinning and opening notes by name need.

SuperBoox has no internet permission and stores everything locally.

There are no dependencies beyond the Android framework and the Kotlin stdlib: no AndroidX and no Compose. The UI is built in code (`Ui.kt` helpers).

## Ideas / next steps

- Force a full e-ink refresh when the panel closes, using the ONYX SDK (`onyxsdk-device`, `EpdController`).
- Drag-to-reorder pins inside the panel.
- Limit pins to 8 to match Supernote, or show them as a grid.
- Hide the edge strip in selected apps (for example, while drawing in full screen).
