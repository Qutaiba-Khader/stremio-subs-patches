# Changelog

## 0.2.1
- Leaving a video ends its questions at once: an open request is closed and nothing queued is sent any more, so quick
  leave/reopen never makes a new play wait behind an old one.
- One scheduled question per subtitle at a time (switching subtitles back and forth no longer starts extra ones).
- An addon is only ever asked about its own subtitles, and the play's report only goes to the addon that was asked.
- A finished subtitle that happens to contain the word "processing" is no longer mistaken for the processing file; an
  unusable file is retried with a growing pause instead of at the addon's pace; an unheld "wait" is never asked faster
  than every 3 s.
- The addon's "stop", an unknown job (404) and the 45-minute give-up now hold for the rest of the play.
- The drawing survives the device sleeping while paused; after a hand-back the downloaded file is drawn again (no new
  download, no second toast); no toast while Stremio is in the background.
- The report carries no addresses or paths in its error text, and its ready-to-screen time is measured from the
  addon's "ready". It only carries what concerns the addon that receives it.
- A 404 stops the questions only when it is the protocol's own `{"subsense":1, "op":"stop"}`; any other 404 (an addon
  without the question route) keeps the file fallback.
- Emptying an unrelated PlayerView no longer ends the play's session; an `<i>` opened at the end of a line carries on.
- Tested: a JVM harness (106 checks) and the A50: 20 quick leave/reopen plays no longer delay the next play (232 s
  before, 6 ms now), a held question closes ~1 s after leaving, nothing is left in memory after a play, and one real
  play swapped a real subtitle with no buffering.

## 0.2.0
- The addon drives the swap through one versioned question ("anything to draw instead?"): its processing file names
  the question in an invisible tag, so the patch needs no knowledge of the addon's addresses or wording.
- The "ready?" question is held open by the addon until the file is ready (no polling every few seconds).
- A generic question for any other selected subtitle lets the addon offer a better copy later, without a new patch.
- One small report per play goes back to the addon (versions, hooks seen, swaps, failures), so a broken release is noticed.
- Sturdier drawing: one owner of the screen per play, a watchdog, and on any problem Stremio's own lines come back at once.
- SRT italics kept. Any Stremio for Android TV release is accepted; a release whose hooks no longer match fails to patch.

## 0.1.0
- First version: the finished subtitle replaces a selected "processing" entry during playback, with no reload, and a toast.
