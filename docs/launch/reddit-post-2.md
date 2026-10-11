**Title:** SuperBoox update: clipboard history (with handwriting), Front Light presets, Quick Controls and more for BOOX (free, open source)

---

A little while ago I posted SuperBoox, a Supernote-style Quick Access sidebar for BOOX. Thanks to everyone who tried it and sent feedback. I've kept working on it, and it's changed a lot since that first post, so here's an update.

Same idea as before: swipe up or down along the edge on any page and a panel opens with your pinned notes, books and apps. Here's what's new since the first post:

**The big ones**

- **Clipboard history.** There's a Clipboard button at the top of the panel now. It keeps the text, links and images you copy. It also keeps **handwriting you lasso-copy in BOOX Notes, shown as a picture of what you wrote**. You can search it, filter it (Text, Links, Images, Handwriting, Pinned) and pin the things you reuse. Tap or hold a clip to copy it again, paste it into the app you're in, share it or delete it. I wanted it to be a catch-all for moving stuff between apps on the tablet.
- **Quick Controls.** A row of round toggles like the BOOX Control Center: Wi‑Fi, Bluetooth, Do Not Disturb, Auto-rotate, Full Refresh, Back/Home and more. Tap to switch, hold for its settings.
- **Front Light presets.** Hold Front Light to switch between Bright, Soft, Custom and Off without opening the Control Center. You can also make your own named presets (Reading, Evening, whatever you like) that don't overwrite BOOX's Custom.
- **Hold New Note** to open BOOX's create-note screen on your recent templates, so you can pick a name, template and layout. If you back out, it takes you back to where you were instead of BOOX's home screen. That's handy if you use another launcher.

**Smaller changes**

- A **first-run tour** walks you through setup. It shows you where to swipe, explains each part of the panel, and asks for the optional permissions with a short reason for each.
- **Live preview in Settings.** While you change things like the panel width, the real panel shows beside the settings, in portrait or landscape.
- Recent Files moved to the Quick Access bar to make room for the Clipboard.
- You can drag to reorder Quick Access, rename apps, add a Previous app button, and back up and restore all your settings to a file.

A few things I wanted to keep the same:

- No adb or computer needed. Install with Obtainium and turn on the accessibility service.
- No internet permission. Everything, including your clipboard history, stays on the device.
- Black and white with no animations, so it looks clean on e-ink.
- The extras are optional:
  - Your own Front Light presets and catching every copy in the background use Shizuku. It runs on the tablet, so you still don't need a computer.
  - Handwriting pictures need All files access.
  - Everything else works without either.

I've tested it on a Note Air 6C (Android 16, firmware 4.3). Other models should work but I haven't been able to test them, so if you try it on something else let me know how it goes. If something doesn't work, there's a "Copy debug report" button in Settings → Troubleshooting you can paste into a GitHub issue. It only counts your clips, it never includes what you copied.

**Install**

1. In Obtainium, add `https://github.com/rn-os/superboox` (or grab the APK from Releases)
2. Open SuperBoox and follow the tour
3. In BOOX App Management, allow SuperBoox to run in the background so it doesn't get frozen

Already have it? Obtainium will pick up the update.

GitHub: https://github.com/rn-os/superboox

Built with Claude Code. Feedback and feature requests welcome.

---

**Images** (in `docs/launch/reddit/`, upload as a gallery in this order; captions to paste):

1. `1-hero.png`: The panel, swiped open over a book.
2. `2-clipboard.png`: Clipboard history. Handwriting lasso-copied in BOOX Notes shows as a picture.
3. `3-light.png`: Hold Front Light for presets, including your own.
4. `4-tour.png`: The first-run tour explains the real panel beside it.
5. `5-live.png`: Settings show the real panel while you change it.
6. `6-settings.png`: Settings home.

All names and content in the images are made up, and the book is *Walden* (public domain).
