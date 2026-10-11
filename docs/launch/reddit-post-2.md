**Title:** SuperBoox update: a clipboard that keeps your handwriting, Front Light presets, and a setup tour (free, open source)

---

A while back I posted SuperBoox, a Supernote-style Quick Access sidebar for BOOX. Thanks to everyone who tried it and sent feedback. It's grown a lot since then, so here's what's new.

Quick recap: swipe along the edge of the screen on any page, and a panel opens. It has your pinned notes and books, the last note and document you had open, quick switches, and your apps. Tap a pinned note and it opens straight in BOOX Notes, even from inside another note.

**What's new**

- **Clipboard history.** Tap Clipboard at the top of the panel to see everything you've copied: text, links, images, and **handwriting you lasso-copy in BOOX Notes, shown as a picture of what you wrote**. You can search it, filter it, and pin the things you keep reusing. Tap or hold a clip to copy it again, paste it into the app you're in, share it, or delete it. I wanted it to be a catch-all for carrying stuff between apps on the tablet.
- **Front Light presets.** Hold Front Light to switch between Bright, Soft, Custom and Off without opening the Control Center. You can also make your own named presets (Reading, Evening, whatever you like) that don't overwrite BOOX's Custom.
- **Hold New Note** to go straight to BOOX's create-note screen, opened on your recent templates, so you can pick the name, template and layout. Backing out takes you back to where you were, not BOOX's home screen (handy if you use another launcher).
- **Quick Controls** in the panel: Wi‑Fi, Bluetooth, Do Not Disturb, Auto-rotate, Full Refresh and more. Tap to switch, hold for settings.
- **A first-run tour.** It walks you through turning it on, shows you where to swipe, explains each part of the real panel, and asks for the optional permissions with a short reason for each.
- **Live preview in Settings.** When you change the panel's width, what the clipboard covers and so on, the real panel shows beside the settings so you can see what you're changing. It works in portrait and landscape.
- **Drag to reorder** in the panel, rename apps, and Backup & restore for all your settings.

**Still true**

- No adb or computer needed. Install with Obtainium and turn on the accessibility service.
- No internet permission. Everything, including the clipboard history, stays on the device.
- Black and white with no animations, so it looks clean on e-ink.
- The extras are optional:
  - Your own Front Light presets and catching every copy as it happens use Shizuku (on-device, no computer).
  - The handwriting pictures need All files access.
  - Everything else works without either.

I've tested it on a Note Air 6C (Android 16, firmware 4.3). Other models should work, but I haven't been able to test them, so if you try it on something else let me know how it goes. If something doesn't work, there's a "Copy debug report" button in Settings → Troubleshooting you can paste into a GitHub issue. It only includes counts for the clipboard, never what you copied.

**Install**

1. In Obtainium, add `https://github.com/rn-os/superboox` (or grab the APK from Releases).
2. Open SuperBoox and follow the tour.
3. In BOOX App Management, allow SuperBoox to run in the background so it doesn't get frozen.

GitHub: https://github.com/rn-os/superboox

Built with Claude Code. Feedback and feature requests welcome!
