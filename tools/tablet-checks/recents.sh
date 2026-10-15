# Last Opened / Recent Files, seeded with "SBTEST ..." notes through the hooks. Nothing of Ryan's is shown or touched.

check_seeded_notes_listed() {
  hook seed_recents names "Alpha,Beta,Gamma" || return
  hook open_panel || return
  sleep 1.5
  # The newest seeded note is "Last Opened Note" (unless that row is switched off in the snapshot's settings).
  expect "Last Opened Note shows the newest seeded note" textprefix "SBTEST Alpha"
  tap descprefix "Recent Files" && { expect "Recent Files lists Beta" textprefix "SBTEST Beta"; expect "Recent Files lists Gamma" textprefix "SBTEST Gamma"; }
}

check_clear_leaves_no_test_rows() {
  hook seed_recents names "Alpha" || return
  hook clear_test_recents || return
  hook state || return
  case "$HOOK_OUT" in *'"testRecents":0'*) note_ok "no SBTEST recents after cleanup" ;; *) note_fail "testRecents not 0: $HOOK_OUT" ;; esac
}
