# Stremio Subs Patches

[Morphe](https://morphe.software) patches for **Stremio for Android TV 1.11.3** (`com.stremio.one`, the manual-install
APK from [stremio.com/downloads](https://www.stremio.com/downloads)), made for a SubSense-based subtitle addon whose
entries can still be "processing" when they are picked.

## Subtitle auto-refresh

Stremio's ExoPlayer keeps the first file it loaded for each subtitle entry for the whole play. An addon entry that was
still "processing" when it was picked therefore keeps showing the processing line. With this patch, when one of our
entries is selected and its processing line is on screen:

1. the patch asks the addon every 5 s whether the entry is ready (`GET …/sub/<job>/<k>.state`, read only on the
   addon side; without that route it asks for the file itself every 15 s and reads the `X-Addon-Processing` header);
2. once ready, it downloads the finished file once and draws its lines itself on Stremio's own `SubtitleView`,
   following the player's position, pause, seek, speed and Stremio's subtitle delay;
3. a toast says the subtitle is ready. The video, the track list and the selection are never touched: nothing reloads.

Picking another subtitle hands drawing back to Stremio; picking our entry again in the same play uses the file already
downloaded. Any error makes the patch step aside. The addon can turn the behaviour off: its `.state` answer carries
`enabled`.

Addon side, for any addon that wants this: entries at `…/sub/<id>/<k>.srt`; a not-ready entry served as a file whose
lines say "processing" (and, ideally, the header `X-Addon-Processing: 1`); and
`GET …/sub/<id>/<k>.state` → `{"ready": true|false, "enabled": true|false}`.

Hooks: only Media3 library classes — `PlayerView.setPlayer` (to get the player) and `SubtitleView.setCues` (every line
on its way to the screen). Stremio's own code is only *read*: the subtitle delay, through reflection on its text
renderer (`getRenderOffsetMs`); if that ever moves, the delay is ignored and everything else keeps working.

Addon subtitles reach the player through Stremio's local streaming server
(`http://127.0.0.1:11470/subtitles.srt?from=<addon url>`); the patch reads our address from the `from` value.

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
