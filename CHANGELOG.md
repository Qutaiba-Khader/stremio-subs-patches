# Changelog

## 0.5.0
- The questions about the addon's own entries carry the address of the video that is playing (header
  `X-Subsense-Stream`, caps `link`): the addon reads the exact stream the viewer plays. Sent only to the addon whose
  subtitle asks, never in an address, never logged; read on the main thread from Media3's current media item.
- New Media3 hook, `MediaItem.SubtitleConfiguration.Builder(Uri)`: the one improving entry (`/1.srt?u=1`) is loaded
  with `&ssp=1`, so the addon answers Stremio's own fetch at once with its processing file and the app swaps it later.
  Every other subtitle address is left as it is.
- The processing line `تتم معالجة الترجمة الآن` is recognised.
- Tested on the A50 with Stremio TV 1.11.3 (temporary account, stand-in video, the addon's own code offline, nothing
  spent): the marked fetch got the processing file in 3 ms and the line showed; every question carried the exact video
  address played; version 1 replaced the line when it was ready and its Arabic lines showed.

## 0.4.1
- Stremio's phone app (2.4.0, same package `com.stremio.one`, same Media3 player as Android TV) is supported. The phone
  app adds addon subtitles with no id, so "Subtitle auto-refresh" could not tell which addon subtitle was picked and
  never asked the addon. A picked track is now also matched by its place: Media3 puts each side-loaded subtitle's source
  number in front of its track id ("2:" = the second subtitle). The TV app (which names its subtitles) is matched as
  before. A text track that matches nothing is logged.
- morphe-builder publishes a third APK, a phone copy that installs beside the original Stremio
  (`com.stremio.one.subs.phone`, "Stremio Subs"). The patches work in the ExoPlayer player only (the phone app's default).
- Tested on an Android 11 phone (Stremio 2.4.0 phone app, temporary account, stand-in addon, nothing spent): one entry
  picked while processing → versions 1, 2 and 3 drawn on time with no buffering; a normal Arabic file shows its dash on
  the right and its "?" on the left.

## 0.4.0
- New patch "Right-to-left subtitle lines": Arabic and Hebrew subtitle lines are laid out right to left, so their
  punctuation shows on the right side. At the start of `SubtitleView.setCues` (Media3, the same hook as before)
  Stremio's left-to-right mark is dropped and every right-to-left line is wrapped in a right-to-left embedding, which
  Media3's own left-to-right wrapping at draw time keeps. Nothing of Stremio's own code is read.
- Tested on an Android 11 phone with Stremio TV 1.11.3: a normal Arabic file showed its dash and question mark on the
  left before and on the right after; files that already carry the embedding look the same with and without the patch.
- `tools/patch.sh` applies the new patch.

## 0.3.1
- Stand-in addon (test only, no app change): a question asked before Stremio's own fetch is answered waits for that
  fetch, like the addon (D77 fix), so Stremio's copy is never swapped for itself.
- The addon's `"toast": ""` now always means no notice, also for the first version drawn over the processing file (0.3.0
  still showed its own notice there). An addon that names no toast keeps the notice for that first swap.
- Tested on the A50 with the stand-in addon: a processing file with no visible text (only its tag), versions 1 → 2 → 3
  swapped with no notice; a subtitle already finished when played: one question, answered "nothing new", no swap.

## 0.3.0
- One subtitle that improves over time: an addon can offer one entry and replace it with each better version while the
  viewer watches — as many versions as the addon's answers say (`version` only goes up, `more`, `nextBy`, `plan`).
- Each upgrade is switched in at a gap between lines (`swap.gapMs`, or after `swap.maxWaitMs`), silently unless the
  addon names a notice; the first version over Stremio's own copy also waits for a gap.
- A promised version that never comes ends cleanly: the addon's `stop`, an expired promise, or an hour with no new
  version — what is drawn stays.
- Questions carry `caps`, `have`, `showing` and `pos` (where the viewer is), and the generic answer can name a
  question to follow (`ask`); older addons and older apps keep protocol 1.
- After a drawing failure, the newest version in memory is drawn again (a few times at most) instead of leaving
  Stremio's older copy; a passing failure of the generic question on one of the addon's own entries is retried.
- Reports: each swap with its version, counted when it is drawn; entries still upgrading; the addon's plan.
- Tested: a JVM harness for the versions protocol (N = 1, 2, 3, 5, 8 versions, stop, silent addon, follow, gaps,
  leave/reopen) and on the A50: 3 and 5 versions, a promised version that never came, and an addon gone silent.

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
