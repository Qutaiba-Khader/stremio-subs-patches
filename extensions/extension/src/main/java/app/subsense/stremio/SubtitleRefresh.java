package app.subsense.stremio;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.Cue;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitle auto-refresh for the SubSense addon.
 *
 * Stremio's ExoPlayer keeps the first file it loaded for each subtitle entry for the whole play, so an entry that was
 * still "processing" when the video opened would show the processing line until the user picks it again. This class
 * watches the selected subtitle; when it is one of ours and the processing line is on screen, it asks the addon every
 * few seconds whether the entry is ready. Once it is, it downloads the finished file once and draws its lines itself on
 * Stremio's own SubtitleView, following the player's clock and Stremio's subtitle delay. The video, the track list and
 * the selection are never touched, so nothing reloads; a toast says the subtitle was replaced.
 *
 * Every entry point is wrapped: on any error the patch steps aside and Stremio behaves as unpatched.
 * All state lives on the main thread; only the network work runs on a background thread.
 */
public final class SubtitleRefresh {
    private static final String TAG = "SubsRefresh";

    /** Our subtitle addresses: …/sub/<jobId>/<k>.srt (the addon's own route). */
    private static final Pattern OUR_PATH = Pattern.compile("/sub/[^/]+/\\d+\\.srt$");
    /** The processing file's line (results.js placeholderText), English and Arabic. */
    private static final String MARK_EN = "processing";
    private static final String MARK_AR = "قيد المعالجة";
    private static final String TOAST = "الترجمة جاهزة"; // الترجمة جاهزة

    private static final long POLL_MS = 5000;
    /** Without the addon's light "ready?" answer the patch has to ask for the file itself: less often. */
    private static final long POLL_FILE_MS = 15000;
    private static final long GIVE_UP_MS = 30 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SubsRefresh");
        t.setDaemon(true);
        return t;
    });

    private static WeakReference<Player> playerRef = new WeakReference<>(null);
    private static WeakReference<View> playerViewRef = new WeakReference<>(null);
    private static WeakReference<View> subtitleViewRef = new WeakReference<>(null);
    private static Listener listener;

    /** The address of the selected subtitle when it is one of ours, else null. */
    private static String selectedUrl;
    private static String lastSelectedTrack;
    /** Entries whose processing line was seen on screen. */
    private static final Set<String> placeholderSeen = new HashSet<>();
    /** Finished files already downloaded, by address. */
    private static final Map<String, Line[]> ready = new HashMap<>();

    private static String polling;
    private static long pollStartedAt;
    private static boolean noStateRoute;

    /** The lines being drawn instead of Stremio's (null = Stremio draws). */
    private static Line[] active;
    private static List<Cue> shown = Collections.emptyList();
    private static boolean drawing;
    private static long swappedAt;

    private static WeakReference<Object> textRendererRef = new WeakReference<>(null);
    private static Method offsetMethod;

    private SubtitleRefresh() {
    }

    // ---------------------------------------------------------------- hooks

    /** Start of PlayerView.setPlayer(player). */
    public static void onPlayerSet(View playerView, Player player) {
        try {
            Player old = playerRef.get();
            if (player == old) return;
            if (old != null && listener != null) {
                try {
                    old.removeListener(listener);
                } catch (Throwable ignored) {
                    // the old player may be released already
                }
            }
            reset();
            if (player == null) {
                playerRef = new WeakReference<>(null);
                log("player detached");
                return;
            }
            playerRef = new WeakReference<>(player);
            playerViewRef = new WeakReference<>(playerView);
            listener = new Listener();
            player.addListener(listener);
            log("player attached: " + player.getClass().getName());
            evaluateSelection();
        } catch (Throwable t) {
            fail("onPlayerSet", t);
        }
    }

    /** Start of SubtitleView.setCues(cues): returns the cues to draw. */
    public static List<Cue> onSetCues(View subtitleView, List<Cue> cues) {
        if (drawing) return cues;
        try {
            subtitleViewRef = new WeakReference<>(subtitleView);
            if (active != null) return shown; // ours are drawn: Stremio's copy (the processing line) stays hidden
            if (selectedUrl == null) evaluateSelection();
            if (selectedUrl != null && !placeholderSeen.contains(selectedUrl) && isPlaceholder(cues)) {
                placeholderSeen.add(selectedUrl);
                log("processing line on screen for " + selectedUrl);
                startPolling(selectedUrl);
            }
        } catch (Throwable t) {
            fail("onSetCues", t);
        }
        return cues;
    }

    // ---------------------------------------------------------------- selection

    private static final class Listener implements Player.Listener {
        @Override
        public void onTracksChanged(Tracks tracks) {
            try {
                evaluateSelection();
            } catch (Throwable t) {
                fail("onTracksChanged", t);
            }
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            tickSoon();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            tickSoon();
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters parameters) {
            tickSoon();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            // the proof that a swap never reloads: no BUFFERING around "swapped"
            Player p = playerRef.get();
            log("playback state " + stateName(state) + " at " + (p != null ? p.getCurrentPosition() : -1) + " ms"
                    + (swappedAt > 0 ? " (" + (SystemClock.elapsedRealtime() - swappedAt) + " ms after the swap)" : ""));
        }
    }

    private static void evaluateSelection() {
        Player p = playerRef.get();
        if (p == null) return;
        String url = selectedOurUrl(p);
        if (url == null ? selectedUrl == null : url.equals(selectedUrl)) return;
        selectedUrl = url;
        log("selected subtitle: " + (url != null ? url : "not one of ours"));
        if (url == null) {
            stopDrawing();
            return;
        }
        Line[] lines = ready.get(url);
        if (lines != null) {
            startDrawing(lines, false); // finished earlier in this play: back to it, no toast
        } else {
            stopDrawing();
            if (placeholderSeen.contains(url)) startPolling(url);
        }
    }

    private static String selectedOurUrl(Player p) {
        MediaItem item = p.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null) return null;
        List<MediaItem.SubtitleConfiguration> configs = item.localConfiguration.subtitleConfigurations;
        if (configs == null || configs.isEmpty()) return null;
        for (Tracks.Group group : p.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT || !group.isSelected()) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) continue;
                Format f = group.getTrackFormat(i);
                if (f.id == null) continue;
                // side-loaded tracks get "<period>:" in front of the configuration's id
                String bare = f.id.matches("^\\d+:.*") ? f.id.substring(f.id.indexOf(':') + 1) : f.id;
                for (MediaItem.SubtitleConfiguration c : configs) {
                    if (c.id == null || !(c.id.equals(bare) || c.id.equals(f.id))) continue;
                    String seen = String.valueOf(c.uri);
                    if (!seen.equals(lastSelectedTrack)) {
                        lastSelectedTrack = seen;
                        log("selected track " + f.id + " → " + seen);
                    }
                    return ourAddress(c.uri);
                }
            }
        }
        return null;
    }

    /**
     * Our addon's address for a subtitle track, or null. Stremio loads addon subtitles through its local streaming
     * server (http://127.0.0.1:11470/…?…=<the addon's address>), so the address may be the track's own or one of its
     * query values.
     */
    private static String ourAddress(Uri uri) {
        if (uri == null) return null;
        if (isOurs(uri)) return uri.toString();
        try {
            for (String name : uri.getQueryParameterNames()) {
                String v = uri.getQueryParameter(name);
                if (v == null || !v.startsWith("http")) continue;
                Uri inner = Uri.parse(v);
                if (isOurs(inner)) return inner.toString();
            }
        } catch (Throwable ignored) {
            // an opaque address: not ours
        }
        return null;
    }

    private static boolean isOurs(Uri uri) {
        String path = uri.getPath();
        return path != null && OUR_PATH.matcher(path).find();
    }

    private static boolean isPlaceholder(List<Cue> cues) {
        if (cues == null) return false;
        for (Cue c : cues) {
            if (c == null || c.text == null) continue;
            String s = c.text.toString();
            if (s.contains(MARK_AR) || s.contains(MARK_EN)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- asking the addon

    private static void startPolling(String url) {
        if (url.equals(polling)) return;
        polling = url;
        pollStartedAt = SystemClock.elapsedRealtime();
        log("waiting for " + url);
        MAIN.post(() -> poll(url));
    }

    private static void poll(String url) {
        if (!url.equals(polling)) return;
        if (!url.equals(selectedUrl)) {
            polling = null;
            log("stopped waiting: another subtitle is selected");
            return;
        }
        if (SystemClock.elapsedRealtime() - pollStartedAt > GIVE_UP_MS) {
            polling = null;
            log("stopped waiting after " + GIVE_UP_MS / 60000 + " min");
            return;
        }
        NET.execute(() -> {
            Check c;
            try {
                c = check(url);
            } catch (Throwable t) {
                c = new Check();
                c.error = String.valueOf(t);
            }
            final Check result = c;
            MAIN.post(() -> onChecked(url, result));
        });
    }

    private static void onChecked(String url, Check c) {
        try {
            if (!url.equals(polling)) return;
            if (c.disabled) {
                polling = null;
                log("the addon has the auto-refresh switched off");
                return;
            }
            if (c.lines != null) {
                polling = null;
                ready.put(url, c.lines);
                log("ready: " + c.lines.length + " lines");
                if (url.equals(selectedUrl)) startDrawing(c.lines, true);
                return;
            }
            if (c.error != null) log("check failed: " + c.error);
            MAIN.postDelayed(() -> poll(url), noStateRoute ? POLL_FILE_MS : POLL_MS);
        } catch (Throwable t) {
            fail("onChecked", t);
        }
    }

    private static final class Check {
        boolean disabled;
        Line[] lines;
        String error;
    }

    /** Background thread: the addon's "ready?" answer, then the file once it is ready. */
    private static Check check(String url) throws Exception {
        Check c = new Check();
        if (!noStateRoute) {
            Response st = get(withPath(url, ".srt", ".state"));
            if (st.code == 404) {
                noStateRoute = true; // an addon without the light route: ask for the file itself
            } else if (st.code != 200) {
                c.error = "state HTTP " + st.code;
                return c;
            } else {
                String body = new String(st.body, StandardCharsets.UTF_8);
                if (body.contains("\"enabled\":false")) {
                    c.disabled = true;
                    return c;
                }
                if (!body.contains("\"ready\":true")) return c;
            }
        }
        Response f = get(withQuery(url, "via=refresh"));
        if (f.code != 200) {
            c.error = "file HTTP " + f.code;
            return c;
        }
        if (f.processing) return c;
        Line[] lines = parseSrt(new String(f.body, StandardCharsets.UTF_8));
        if (lines.length == 0) {
            c.error = "the file has no lines";
            return c;
        }
        for (Line l : lines) {
            String s = l.text.toString();
            if (s.contains(MARK_AR) || s.contains(MARK_EN)) return c; // still the processing file
        }
        c.lines = lines;
        return c;
    }

    private static final class Response {
        int code;
        byte[] body = new byte[0];
        boolean processing;
    }

    private static Response get(String address) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", "StremioSubsRefresh/0.1");
        conn.setRequestProperty("Cache-Control", "no-cache");
        try {
            Response r = new Response();
            r.code = conn.getResponseCode();
            r.processing = conn.getHeaderField("X-Addon-Processing") != null;
            if (r.code == 200) {
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 8 * 1024 * 1024) throw new IllegalStateException("file over 8 MB");
                    }
                    r.body = out.toByteArray();
                }
            }
            return r;
        } finally {
            conn.disconnect();
        }
    }

    private static String withPath(String url, String from, String to) {
        Uri u = Uri.parse(url);
        String path = u.getPath();
        if (path == null || !path.endsWith(from)) return url;
        return u.buildUpon().path(path.substring(0, path.length() - from.length()) + to).build().toString();
    }

    private static String withQuery(String url, String q) {
        return url + (url.contains("?") ? "&" : "?") + q;
    }

    // ---------------------------------------------------------------- the file

    static final class Line {
        final long start;
        final long end;
        final CharSequence text;

        Line(long start, long end, CharSequence text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final Pattern TIME = Pattern.compile(
            "(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");
    private static final Pattern TAG_HTML = Pattern.compile("<[^>]*>");
    private static final Pattern TAG_ASS = Pattern.compile("\\{\\\\[^}]*\\}");

    static Line[] parseSrt(String s) {
        if (s.startsWith("﻿")) s = s.substring(1);
        String[] rows = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Matcher m = TIME.matcher(rows[i]);
            if (!m.find()) continue;
            long a = ms(m, 1);
            long b = ms(m, 5);
            StringBuilder text = new StringBuilder();
            int j = i + 1;
            for (; j < rows.length && !rows[j].trim().isEmpty(); j++) {
                if (TIME.matcher(rows[j]).find()) break;
                String t = TAG_ASS.matcher(TAG_HTML.matcher(rows[j]).replaceAll("")).replaceAll("").trim();
                if (t.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                // as Stremio's own onCues does to every line (ExoPlayer NEW_LINE_REGEX "(^)" → "‎$1")
                text.append('‎').append(t);
            }
            i = j - 1;
            if (b > a && text.length() > 0) out.add(new Line(a, b, text.toString()));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.start, y.start));
        return out.toArray(new Line[0]);
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        while (frac.length() < 3) frac = frac + "0";
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60
                + Long.parseLong(m.group(g + 2))) * 1000 + Long.parseLong(frac);
    }

    // ---------------------------------------------------------------- drawing

    private static final Runnable TICK = SubtitleRefresh::tick;

    private static void startDrawing(Line[] lines, boolean toast) {
        active = lines;
        shown = Collections.emptyList();
        Player p = playerRef.get();
        if (toast) {
            swappedAt = SystemClock.elapsedRealtime();
            log("swapped at " + (p != null ? p.getCurrentPosition() : -1) + " ms, text delay " + textDelayMs(p) + " ms");
            View v = playerViewRef.get();
            if (v != null) Toast.makeText(v.getContext(), TOAST, Toast.LENGTH_LONG).show();
        }
        tick();
    }

    private static void stopDrawing() {
        MAIN.removeCallbacks(TICK);
        if (active == null) return;
        active = null;
        draw(Collections.emptyList());
        log("stopped drawing: Stremio draws again");
    }

    private static void tickSoon() {
        if (active == null) return;
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    private static void tick() {
        MAIN.removeCallbacks(TICK);
        try {
            Line[] lines = active;
            Player p = playerRef.get();
            if (lines == null) return;
            if (p == null) {
                stopDrawing();
                return;
            }
            long t = p.getCurrentPosition() - textDelayMs(p); // Stremio shows the line at T when position − delay = T
            List<Cue> now = new ArrayList<>(2);
            long next = Long.MAX_VALUE;
            int i = lastStartingBy(lines, t);
            for (int k = Math.max(0, i - 8); k <= i; k++) {
                if (lines[k].end > t) {
                    now.add(new Cue.Builder().setText(lines[k].text).build());
                    next = Math.min(next, lines[k].end);
                }
            }
            if (i + 1 < lines.length) next = Math.min(next, lines[i + 1].start);
            if (!sameText(now, shown)) draw(now);
            long wait;
            if (p.isPlaying()) {
                float speed = p.getPlaybackParameters().speed;
                wait = next == Long.MAX_VALUE ? 1000 : (long) ((next - t) / Math.max(0.25f, speed));
                wait = Math.max(10, Math.min(1000, wait));
            } else {
                wait = 500;
            }
            MAIN.postDelayed(TICK, wait);
        } catch (Throwable t) {
            fail("tick", t);
            active = null;
        }
    }

    private static int lastStartingBy(Line[] lines, long t) {
        int lo = 0;
        int hi = lines.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines[mid].start <= t) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static boolean sameText(List<Cue> a, List<Cue> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i).text).equals(String.valueOf(b.get(i).text))) return false;
        }
        return true;
    }

    private static void draw(List<Cue> cues) {
        shown = cues;
        View v = subtitleViewRef.get();
        if (v == null) {
            View pv = playerViewRef.get();
            if (pv instanceof PlayerView) v = ((PlayerView) pv).getSubtitleView();
        }
        if (!(v instanceof SubtitleView)) return;
        drawing = true;
        try {
            ((SubtitleView) v).setCues(cues);
        } finally {
            drawing = false;
        }
    }

    // ---------------------------------------------------------------- Stremio's subtitle delay (read only)

    /** Stremio keeps the subtitle delay in its own text renderer (getRenderOffsetMs); 0 when it cannot be read. */
    private static long textDelayMs(Player p) {
        if (p == null) return 0;
        try {
            Object r = textRendererRef.get();
            if (r == null) {
                Object exo = p instanceof ForwardingPlayer ? ((ForwardingPlayer) p).getWrappedPlayer() : p;
                Method count = exo.getClass().getMethod("getRendererCount");
                Method get = exo.getClass().getMethod("getRenderer", int.class);
                int n = (Integer) count.invoke(exo);
                for (int i = 0; i < n && r == null; i++) {
                    Object x = get.invoke(exo, i);
                    if (x != null && x.getClass().getName().endsWith("CustomTextRenderer")) r = x;
                }
                if (r == null) return 0;
                offsetMethod = r.getClass().getMethod("getRenderOffsetMs");
                textRendererRef = new WeakReference<>(r);
            }
            return (Long) offsetMethod.invoke(r);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- misc

    private static void reset() {
        MAIN.removeCallbacks(TICK);
        active = null;
        shown = Collections.emptyList();
        selectedUrl = null;
        polling = null;
        swappedAt = 0;
        placeholderSeen.clear();
        textRendererRef = new WeakReference<>(null);
        subtitleViewRef = new WeakReference<>(null);
    }

    private static String stateName(int s) {
        switch (s) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return String.valueOf(s);
        }
    }

    private static void log(String s) {
        Log.i(TAG, s);
    }

    private static void fail(String where, Throwable t) {
        Log.w(TAG, where + " failed, stepping aside", t);
    }
}
