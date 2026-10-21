# Changelog

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
