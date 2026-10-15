# Clipboard panel checks derived from /mnt/project-files/checklists/clipboard.md (items a script can do).
# Clips are made by the debug hooks (source "superboox.test") and removed afterwards; the real clipboard history is untouched.
# Pen/handwriting copies made in BOOX Notes, Mark up in BOOX's editor and the Share sheet stay manual.

_open_clipboard() {
  hook open_panel || return 1
  sleep 1.5
  tap descprefix "Clipboard" || return 1
  sleep 1
}

check_tap_row_does_not_open_actions_hold_does() {
  hook add_text text "SBTESTtextclip" || return
  _open_clipboard || return
  expect "text clip row listed" textprefix "SBTESTtextclip" || return
  tap textprefix "SBTESTtextclip" hold
  expect "hold opens actions (Delete)" desc "Delete"
  expect "hold opens actions (Pin)" desc "Pin"
  expect_absent "text clip has no Name action" desc "Name"
  tap textprefix "SBTESTtextclip" hold
  expect_absent "hold again closes actions" desc "Delete"
}

check_image_clip_listed() {
  hook add_image text "SBTESTimage" || return
  _open_clipboard || return
  hook state && note_ok "image clip added: $HOOK_OUT"
  shot "image-row"
  manual "image clip: hold the picture row, then confirm 7 actions as 3+2+2 with 'Share…' in full at font scales 0.85 and 1.3 (see $CHECK-image-row.png)"
}

check_handwriting_clip_actions_and_naming() {
  hook add_handwriting text "SBTESThw" || return
  _open_clipboard || return
  expect "handwriting row shows a '+ Name' chip" descprefix "Name this clip" || return
  tap descprefix "Name this clip"
  expect "Name box open" text "Name this clip" || return
  type_text "SBTESTname"
  tap text "Save"
  expect "name saved and shown" textprefix "SBTESTname"
  hook state && note_ok "state after naming: $HOOK_OUT"
  manual "tap-to-paste a handwriting clip into BOOX Notes with the pen"
}

check_pin_and_delete() {
  hook add_text text "SBTESTpinme" || return
  _open_clipboard || return
  tap textprefix "SBTESTpinme" hold
  tap desc "Pin"
  tap textprefix "SBTESTpinme" hold
  expect "now offers Unpin" desc "Unpin"
  tap desc "Delete"
  expect_absent "deleted clip gone" textprefix "SBTESTpinme"
}

check_copy_hook_puts_clip_on_clipboard() {
  hook add_text text "SBTESTcopyme" || return
  hook copy_clip n 0 && note_ok "copy_clip: $HOOK_OUT" || note_fail "copy_clip failed"
}
