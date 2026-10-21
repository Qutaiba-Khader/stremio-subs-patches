# Stremio Subs Patches

[Morphe](https://morphe.software) patches for **Stremio for Android TV 1.11.3** (`com.stremio.one`, the manual-install
APK from [stremio.com/downloads](https://www.stremio.com/downloads)), made for a SubSense-based subtitle addon whose
entries can still be "processing" when they are picked.

## Subtitle auto-refresh

Stremio's ExoPlayer keeps the first file it loaded for each subtitle entry for the whole play. An addon entry that was
still "processing" when it was picked therefore keeps showing its processing line. With this patch the addon can replace
the subtitle that is playing, with no reload and nothing for the viewer to do; a toast says so.

How it works (protocol 1, see the comment at the top of `SubtitleRefresh.java`):

1. The addon's processing file names its "ready?" question in an invisible tag (`ssx1 <path>`, written in Unicode tag
   characters, which are never drawn). Without a tag, an address ending in `/sub/<id>/<k>.srt` asks `/sub/<id>/<k>.state`.
2. The patch asks `GET <path>?v=1&wait=25&src=…`; the addon holds the answer until the file is ready (or 25 s) and
   answers `{"op": "wait" | "replace" | "stop", "file": "<path>", "toast": "…"}`.
3. On `replace`, the patch downloads the file once (same origin only, at most 8 MB) and draws its lines itself on
   Stremio's own `SubtitleView`, following the player's position, pause, seek, speed and Stremio's subtitle delay.
   The video, the track list and the selection are never touched.
4. For any other selected subtitle of the same addon (same origin) the patch asks it once,
   `GET <base>/patch/v1/subtitle?v=1&src=…`; an addon with nothing better answers `stop`. This is where later behaviour
   (for example a re-timed copy) can come from, with no new patch. An addon is never told another addon's addresses.
5. One small report per play goes to `POST <base>/patch/v1/report` of the addon that was asked (patch and Stremio
   versions, hooks seen, swaps, entries still waiting, failures; no paths in its error text).

Leaving the video ends its questions at once (an open request is closed, nothing queued is sent). An addon's `stop`,
an unknown job (a JSON 404) and a 45-minute limit hold for the rest of the play.

### One subtitle that improves over time (0.3.0)

An addon can offer a single entry and replace it with better versions while the viewer watches. The app never
assumes how many versions there are: the addon's answers decide.

- Every question says what the app can do and where it stands: `caps=versions,follow`, `have=<version it draws, 0 =
  none>`, `showing=own|processing` (Stremio's own copy or the processing file) and `pos=<playback position, ms>`, so an
  addon can, for example, correct the part the viewer is about to watch first.
- An answer may carry: `version` (a number that only goes up; an equal or older one is ignored), `more` (another
  version may follow — the app asks again), `nextBy` (ms: the promise expires if nothing comes by then; any answer
  with `more` renews it), `plan` (the addon's list of versions, kept for the report only), `swap: {gapMs, maxWaitMs}`
  (an upgrade is switched in at a gap between lines), `toast` (`""` = none) and, on the generic question, `ask`
  (a question to follow on the same origin). Unknown fields are ignored; `ext` is kept free for later data.
- `stop` ends the questions and keeps what is drawn — also when a promised version never came. With no new version
  for an hour, or when a promise expires, the app stops by itself. At most 100 versions per subtitle and play.
- Older apps don't send `caps`, so an addon answers them in protocol 1.

Picking another subtitle or turning subtitles off hands the screen back to Stremio at once; picking our entry again in
the same play uses the file already downloaded. A watchdog and error handling hand the screen back on any problem.

Hooks: only Media3 library classes — `PlayerView.setPlayer` and `SubtitleView.setCues`. Stremio's own code is only
*read*: the subtitle delay, through reflection on its text renderer (`getRenderOffsetMs`); if that ever moves, the delay
is ignored and everything else keeps working. Addon subtitles reach the player through Stremio's local streaming server
(`http://127.0.0.1:11470/subtitles.srt?from=<addon url>`); the patch reads the addon's address from that.

## Ready-made APKs

[morphe-builder](https://github.com/Qutaiba-Khader/morphe-builder/releases) publishes two Android TV APKs with each
release of these patches (and each new Stremio version), both armeabi-v7a (32-bit, which also runs on 64-bit devices):

- `stremio-tv-replacement-subs-…apk` replaces the original: same package `com.stremio.one`, same name "Stremio". It is
  signed with the builder's own key, not Stremio's, so uninstall the original Stremio once before installing it.
- `stremio-tv-clone-subs-…apk` installs beside the original: package `com.stremio.one.subs`, named "Stremio Subs".

## Test build

`tools/patch.sh <stremio-tv.apk>` builds the bundle and patches the APK with it plus Morphe's "Clone app" (package
`com.stremio.one.subs`, name "Stremio Subs", so it installs beside the original). Building needs a GitHub token with
`read:packages` (`GITHUB_ACTOR` / `GITHUB_TOKEN`, for the Morphe Gradle plugin) and an Android SDK; the script header lists
the rest.

Tested on 1.11.3 only. The hooks are in the Media3 library, so other versions will probably patch too, but check.
The manual-install APK also installs on phones (minSdk 24); the patch was tested that way, on an Android 11 phone.

`test/test-addon.js` is a stand-in for the addon with no real data: one generated test movie (clock burned in) and three
Arabic entries in the addon's exact shape; entries 2 and 3 serve the addon's processing file until `READY2_S` /
`READY3_S` seconds after the list call. Every finished line names its own start time, so a screenshot shows whether the
drawn line matches the video clock. The video it serves (`test/patchtest.mp4`, not in the repo) can be made with:

```sh
ffmpeg -f lavfi -i testsrc2=size=854x480:rate=25 -f lavfi -i sine=frequency=440 -t 600 \
  -vf "drawtext=text='%{pts\:hms}':fontsize=64:fontcolor=white:box=1:boxcolor=black@0.7:x=(w-tw)/2:y=40" \
  -c:v libx264 -b:v 600k -pix_fmt yuv420p -c:a aac -movflags +faststart test/patchtest.mp4
```

Run it with `node test/test-addon.js`, then `adb reverse tcp:7790 tcp:7790` and add `http://127.0.0.1:7790/manifest.json`
to the Stremio account used for the test.

Licence GPLv3 — see NOTICE.
