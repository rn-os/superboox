**Title:** I built a Supernote-style Quick Access panel for BOOX (free, open source)

---

I really like the Quick Access sidebar on the Supernote and wanted the same thing on my BOOX, so I built it.

SuperBoox is a small overlay app. Swipe up or down along the right edge on any page and a panel opens with:

- New Note and Recent Files at the top
- Last Opened Note and Last Opened Document
- Quick Access for your pinned notes, books, folders and apps
- Your apps along the bottom

A few things I wanted to get right:

- Tapping a pinned note opens it directly in BOOX Notes. It works from inside another note too. It closes the current one first so it saves.
- To pin something, open the note or book, swipe the panel open and tap Add. In the Notes library you can tick several at once.
- Books pinned from NeoReader open straight to the book.
- Black and white with no animations, so it looks clean on e-ink. Menu size is adjustable.
- No adb or computer needed. Install with Obtainium and turn on the accessibility service.
- No internet permission. Everything stays on the device.

I've tested it on a Note Air 6C (Android 16, firmware 4.3). Other models should work but I haven't been able to test them, so if you try it on something else let me know how it goes. If something doesn't work there's a "Copy debug report" button in settings you can paste into a GitHub issue.

**Install**

1. In Obtainium, add `https://github.com/rn-os/superboox` (or grab the APK from Releases)
2. Open SuperBoox and turn on the accessibility service
3. In BOOX App Management, allow SuperBoox to run in the background so it doesn't get frozen

GitHub: https://github.com/rn-os/superboox

Built with Claude Code. Feedback and feature requests welcome.
