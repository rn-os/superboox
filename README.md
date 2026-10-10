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
5. Swipe left from the right edge of the screen. By default the touch strip covers the middle third of the right edge, and a small black marker shows where it is.

## Ways to open the panel

| Method | Notes |
|---|---|
| Edge gesture | Swipe inward, swipe along the edge, or double-tap. You choose the edge, the strip size and its position in Settings. |
| **Quick Access** launcher icon | An invisible activity that opens the panel. You can bind it to a BOOX system gesture or the Navigation Ball's "open app" action if you don't want a reserved strip on the screen edge. |
| Quick Settings tile | Add **Quick Access** to the notification shade. |
| adb / automation | `adb shell am start -a com.superboox.quickaccess.OPEN_PANEL` |

## BOOX Notes integration

BOOX Notes keeps its notes in a private database and has no documented "open note X" intent. SuperBoox uses three approaches instead:

1. **Notes in the dock.** The BOOX Notes app (`com.onyx.android.note`) is placed in the dock by default.
2. **Pinned notes, opened by name.** Tapping a pinned note restarts BOOX Notes at its library. The accessibility service then finds the item with that name and taps it, scrolling the list if it has to. Notes inside folders are written as a path: `Work / Meetings / Standup`.
   - The easiest way to pin a note: open BOOX Notes, open the panel, tap **Pin a note from this screen** and choose the note from what's shown.
   - This depends on the note names being on screen in the Notes library. If a firmware update changes that screen, it may need adjusting.
3. **Files and custom intents.**
   - Pin exported note PDFs or any document or folder (Storage Access Framework), or set the export folder as **Recent files**.
   - If you find a firmware-specific deep link, add it as a **Custom intent**. To find one, open the note and run `adb shell dumpsys activity activities | grep -i onyx`.

If your firmware uses a different Notes package, change it under **Advanced** in Settings.

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
