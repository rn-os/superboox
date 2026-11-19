# SuperBoox 2 local handoff

## Working copy

Start with the current working tree in the local SuperBoox repository under GitHub Repos. The launcher changes, including new source files, were copied here from the checkout used for tablet builds. The two checkouts had the same base commit, `8a82d60`. The copied work is uncommitted. Preserve it when switching branches. Keep builds local until the owner approves publication.

## What is on the tablet

- BOOX Note Air6 C, Android 16, BOOX firmware 4.3.1. SuperBoox is its default Home app. The latest installed build is `1.1.3-home.10.local` with the optional BOOX Pen SDK enabled.
- Home, Quick Access, Settings, and Digests share the Paper/Dark setting. Paper is the default. Home uses the existing Look & size scale.
- The latest Home puts the dot grid immediately under the date. It expands downward once when a stroke ends near the bottom. Existing strokes stay in place. Undo clears the draft and returns the grid to its compact size when the last stroke is removed. Keep saves a PNG to Capture Inbox. An unsaved draft can be lost if Home is recreated, so preserve it before any install or restart.
- Quick Capture opens native BOOX Quick Note directly. Long-press opens BOOX's template setup. SuperBoox has no verified path to insert editable scratchpad strokes into a native note.
- Capture Inbox accepts existing saved Digests passages and shared links, as well as handwriting from Home. It supports Act, Markdown or PNG export, Archive, edit or rename, and confirmed Delete. Archive keeps the saved copy. Handwriting images are included in new SuperBoox backup files. A restore with handwriting has not been tested on the tablet.

## Next design pass

The owner found the current empty grid too tall and too faint on the physical screen. Prototype a short surface under the date, roughly four lines tall, with darker dots, a soft paper edge, and a small Scratchpad label next to the quiet nib. Leave Inbox and Quick Capture visible in the compact state. Keep the top edge and previous ink fixed when adding paper. Expand only after pen lift near the lower edge, in one redraw. Show Keep and Undo once there is ink. Consider local draft autosave after a pause and an explicit full-page control. Neither is in the installed prototype.

The [two-state mock-up](mockups/top-scratchpad-concept.png) illustrates the downward expansion. The revised four-line height and stronger grid contrast supersede the proportions shown in that image.

Use Home to capture a thought, Capture Inbox to work through it, and Quick Capture to start in native BOOX Notes. For workflow research, time Home to first ink, saved highlight to follow-up, and calendar event to follow-up. Capture the time spent finding the control separately from the app handoff and review.

## Pen issues to check

The owner reported lag and visual glitches while writing. Device logs showed raw BOOX input was active. The older build reopened the renderer on each stroke, and the following build still toggled hardware rendering for each committed stroke. The installed prototype keeps the raw reader open and batches Android redraws after a short pen pause. Synthetic stylus tests verified layout expansion, multi-stroke capture and undo. Physical latency, ghosting, and rapid pen lifts still need a hands-on comparison against native Notes. Keep the next layout change easy to compare with the current renderer.

[OpenInkBridge's BOOX adapter](https://github.com/GoVed/OpenInkBridge/blob/0583a2a9c367b36f7a20009f58531c23cbbc1b3f/android/openinkbridge-sdk/src/main/java/org/openinkbridge/sdk/Adapters.kt) is a reference for the next renderer test. It keeps its hardware session open, disables the SDK's automatic pen-up refresh, and requests a fast e-ink refresh after drawing the committed stroke. That alternative has been researched and is still to be implemented or tested in SuperBoox.

## Entry points and local checks

- `HomeActivity.kt` handles the grid, expansion, capture screens, appearance selector and Android export.
- `HomeHardwareInk.kt` lives under `app/src/booxPen/`, with a fallback under `app/src/standardPen/`.
- `Digests.kt` stores captures and review state. `Backup.kt` includes saved handwriting images.
- `BooxNotes.kt`, `NoteOpener.kt` and `QuickAccessService.kt` handle native Notes handoffs.

Use JDK 17 and Android SDK 34. The installed prototype built and passed all six existing unit tests. The APK is signed with the repository debug key. This command reproduces the tablet variant without publishing.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
GITHUB_RUN_NUMBER=296 SB_VERSION=1.1.3-home.10.local \
  ./gradlew --offline --no-daemon -PbooxPenSdk=true -Pandroid.enableJetifier=true \
  assembleDebug testDebugUnitTest
```

Before installing on the connected tablet, check that any visible Home draft has been kept. Use an in-place install to preserve settings. Compare the compact and expanded grids with a real pen after installation.

## Changes since this handoff (superboox-2-wip)

Two separate commits on top of `WIP`, so each can be tried on its own:

1. **Compact Scratchpad (layout only).**
   - The pad is about four 26dp lines under the date (`PAD_LINES`, `PAD_LINE_DP`). It sits on a soft paper card (`AppPalette.pad`, a 1dp `rule` border, 10dp corners), with full-strength `secondary` dots and a nib plus a "Scratchpad" label at the top left.
   - Inbox (with its count) and Quick Capture sit side by side under the pad, even when it's compact. The old "Review N captures" block is gone, because the Inbox button carries the count.
   - Undo and Keep appear only once there's ink.
   - Lifting the pen within one line of the bottom adds eight lines (`PAD_MORE_LINES`), once. The top edge and existing ink stay fixed.
   - Draft autosave: strokes keep their points, are written to `filesDir/home-draft.json` 1.5 s after the pen stops and on pause, and are restored in `onCreate`. Keep, or undoing every stroke, deletes the file.
   - Not done: an explicit full-page control.
2. **Fast-refresh pen renderer (test).**
   - Home → More → **Pen refresh**: Batched (default, unchanged) or Fast refresh.
   - Fast refresh keeps raw drawing and rendering on, calls `setPenUpRefreshEnabled(false)`, and after each committed stroke invalidates the view and calls `EpdController.invalidate(view, UpdateMode.DU)`, coalescing strokes lifted within 40 ms.
   - Logged as `home pen`.
   - BOOX pen builds only.

To test the Scratchpad alone, build its commit. The renderer commit adds only the Pen refresh choice; left on Batched, it behaves like the Scratchpad commit.

Both commits were compile-checked offline: the standard variant against Android 14, and the BOOX pen variant against onyxsdk-pen 1.5.9, -base 1.8.10, -baselite 1.1.2 and -device 1.3.7. Neither has been built with Gradle or run on the tablet yet.
