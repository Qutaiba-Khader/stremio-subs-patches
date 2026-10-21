package app.subsense.stremio;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
 * Subtitle auto-refresh: lets a subtitle addon replace the subtitle that is playing, with no reload.
 *
 * Stremio's ExoPlayer keeps the first file it loaded for each subtitle entry for the whole play, so an entry that was
 * still "processing" when it was picked keeps showing its processing line. This class lets the addon answer, for the
 * selected subtitle, one versioned question — "anything to draw instead?" — and draws the answer itself on Stremio's
 * own SubtitleView, following the player's clock and Stremio's subtitle delay. The video, the track list and the
 * selection are never touched.
 *
 * Contract (protocol 1). The addon's base is its address up to "/sub/" (origin + any path prefix, e.g. a secret):
 * - a placeholder file marks itself with an invisible tag (Unicode tag characters) "ssx1 <path>" in a cue; <path> is
 *   the question to ask, on the origin of the subtitle's own address. Without a tag, an address ending in
 *   /sub/<id>/<k>.srt asks /sub/<id>/<k>.state.
 * - GET <origin><path>?v=1&wait=25&src=<subtitle address> answers JSON:
 *   {"op":"wait"|"replace"|"stop", "file": "<path on the same origin>", "retryMs": n, "toast": "text"}
 *   (older answers {"ready", "enabled"} are understood too). "wait" may be held open by the server up to `wait` s.
 * - GET <base>/patch/v1/subtitle?v=1&src=<address> — the same answer for any other selected subtitle of the same
 *   addon (same origin: an addon is never told another addon's addresses), once per play; "stop" = nothing better.
 * - POST <base>/patch/v1/report — one small JSON report per play (versions, hooks seen, swaps, last error).
 * Nothing but subtitle lines and a short toast can come back: files only from the same origin, at most 8 MB, plain
 * text only.
 *
 * Every entry point is wrapped: on any error the patch hands the screen back to Stremio and steps aside.
 * All state lives on the main thread; only the network work runs on a background thread.
 */
public final class SubtitleRefresh {
    private static final String TAG = "SubsRefresh";
    static final String PATCH_VERSION = "0.2.1";
    private static final int PROTOCOL = 1;

    private static final Pattern OUR_PATH = Pattern.compile("/sub/[^/]+/\\d+\\.srt$");
    /** The processing file's visible line, for an addon that sends no tag: both parts must be there. */
    private static final String MARK_EN = "processing";
    private static final String MARK_EN_2 = "pick it again";
    private static final String MARK_AR = "قيد المعالجة";
    private static final String MARK_AR_2 = "اخترها";
    private static final String TOAST = "الترجمة جاهزة"; // الترجمة جاهزة

    private static final int WAIT_S = 25;
    private static final long RETRY_MS = 3000;
    private static final long ERROR_RETRY_MAX_MS = 60000;
    private static final long GIVE_UP_MS = 45 * 60 * 1000L;
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_FILE = 8 * 1024 * 1024;
    private static final long WATCHDOG_MS = 2000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SubsRefresh");
        t.setDaemon(true);
        return t;
    });

    // ---------------------------------------------------------------- per-player session

    /** One play: everything that must die with the player. A newer session makes every older callback a no-op. */
    private static final class Session {
        final int gen;
        final WeakReference<Player> player;
        final WeakReference<View> playerView;
        final Listener listener = new Listener();
        WeakReference<View> subtitleView = new WeakReference<>(null);
        final Report report = new Report();

        /** The selected subtitle, when it is a side-loaded one (null = none / not side-loaded). */
        Selected selected;
        /** Tasks asking about a subtitle, by its address. */
        final Map<String, Ask> asks = new HashMap<>();
        /** Replacements already downloaded, by subtitle address. */
        final Map<String, Line[]> files = new HashMap<>();
        /** Subtitle addresses whose generic question was answered "stop" (or failed) in this play. */
        final Set<String> settled = new HashSet<>();
        /** Subtitle addresses the addon said "stop" for, or that we gave up on: not asked again in this play. */
        final Set<String> stopped = new HashSet<>();
        /** Where this play's report goes: the addon whose question was asked (null = no report). */
        String reportBase;

        /** Drawing: the lines we draw instead of Stremio's, for `drawingFor` (null = Stremio draws). */
        Line[] drawing;
        String drawingFor;
        List<Cue> shown = Collections.emptyList();
        List<Cue> lastStremio = Collections.emptyList();
        long lastTickAt;
        long swappedAt;

        Session(int gen, Player p, View v) {
            this.gen = gen;
            this.player = new WeakReference<>(p);
            this.playerView = new WeakReference<>(v);
        }
    }

    private static final class Selected {
        final String trackKey;
        /** The subtitle's own address (the addon's), unwrapped from Stremio's local streaming server. */
        final String address;

        Selected(String trackKey, String address) {
            this.trackKey = trackKey;
            this.address = address;
        }
    }

    private static final class Ask {
        final String address;
        final String origin;
        String path;
        final boolean generic;
        /** The addon's base (for the report). */
        final String base;
        final long startedAt = SystemClock.elapsedRealtime();
        long errorDelay = RETRY_MS;
        boolean placeholderSeen;
        boolean busy;
        /** The one scheduled next question (null = none): a new schedule always replaces it. */
        Runnable pending;
        /** Set when the play ends: the background thread sends nothing more for this question. */
        volatile boolean cancelled;
        /** The open connection while a question or a file is on the way, so the end of the play can close it. */
        volatile HttpURLConnection conn;

        Ask(String address, String origin, String path, boolean generic, String base) {
            this.address = address;
            this.origin = origin;
            this.path = path;
            this.generic = generic;
            this.base = base;
        }
    }

    private static Session session;
    private static int generation;
    private static boolean drawingNow;
    private static SharedPreferences prefs;

    private SubtitleRefresh() {
    }

    // ---------------------------------------------------------------- hooks

    /** Start of PlayerView.setPlayer(player). */
    public static void onPlayerSet(View playerView, Player player) {
        try {
            Session old = session;
            Player oldPlayer = old != null ? old.player.get() : null;
            if (player != null && player == oldPlayer) return;
            if (player == null) {
                // only the play's own view (or a view that is gone) ends it: another PlayerView being emptied does not
                View oldView = old != null ? old.playerView.get() : null;
                if (old != null && (oldView == null || oldView == playerView)) endSession(old, "player detached");
                return;
            }
            if (old != null) endSession(old, "player replaced");
            if (prefs == null && playerView != null) {
                prefs = playerView.getContext().getApplicationContext().getSharedPreferences("subsense_refresh", Context.MODE_PRIVATE);
            }
            Session s = new Session(++generation, player, playerView);
            session = s;
            s.report.hookPlayer = true;
            s.report.stremio = appVersion(playerView);
            player.addListener(s.listener);
            log("player attached (" + player.getClass().getName() + "), session " + s.gen);
            evaluateSelection(s);
        } catch (Throwable t) {
            fail("onPlayerSet", t);
        }
    }

    /** Start of SubtitleView.setCues(cues): returns the cues to draw. */
    public static List<Cue> onSetCues(View subtitleView, List<Cue> cues) {
        if (drawingNow) return cues;
        Session s = session;
        if (s == null) return cues;
        try {
            s.subtitleView = new WeakReference<>(subtitleView);
            if (cues != null && !cues.isEmpty()) s.report.hookCues = true; // not Media3's own clearing call
            s.lastStremio = cues != null ? cues : Collections.emptyList();
            if (s.drawing != null) return s.shown; // ours are drawn: Stremio's copy stays hidden
            if (s.selected == null) evaluateSelection(s);
            Selected sel = s.selected;
            if (sel != null) {
                String tagPath = tagIn(cues);
                Ask a = s.asks.get(sel.address);
                if (tagPath != null || isPlaceholderText(cues)) {
                    Line[] have = s.files.get(sel.address);
                    if (have != null) { // already downloaded in this play (e.g. drawing was handed back): draw it again
                        startDrawing(s, sel.address, have, null);
                        return s.drawing != null ? s.shown : cues; // if drawing failed at once, Stremio's own cues stay
                    }
                    if (s.stopped.contains(sel.address)) return cues; // the addon said stop, or we gave up
                    if (a == null || a.generic) {
                        String path = tagPath != null ? tagPath : statePathFor(sel.address);
                        String origin = originOf(sel.address);
                        if (path != null) startAsking(s, new Ask(sel.address, origin, path, false, baseOf(origin, path)));
                    } else if (tagPath != null && !tagPath.equals(a.path)) {
                        a.path = tagPath;
                    }
                    Ask now = s.asks.get(sel.address);
                    if (now != null && !now.placeholderSeen) {
                        now.placeholderSeen = true;
                        s.report.placeholders++;
                        if (now.base != null) s.reportBase = now.base;
                        rememberBase(now.base);
                        log("processing line on screen for " + sel.address + (tagPath != null ? " (tag " + tagPath + ")" : ""));
                    }
                }
            }
        } catch (Throwable t) {
            fail("onSetCues", t);
        }
        return cues;
    }

    // ---------------------------------------------------------------- player events

    private static final class Listener implements Player.Listener {
        @Override
        public void onTracksChanged(Tracks tracks) {
            Session s = session;
            if (s == null || s.listener != this) return;
            try {
                evaluateSelection(s);
            } catch (Throwable t) {
                fail("onTracksChanged", t);
            }
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
            tickSoon(this);
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            tickSoon(this);
        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters parameters) {
            tickSoon(this);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            Session s = session;
            if (s == null || s.listener != this) return;
            Player p = s.player.get();
            if (s.swappedAt > 0 && SystemClock.elapsedRealtime() - s.swappedAt < 30000 && state == Player.STATE_BUFFERING) {
                s.report.bufferingAfterSwap++;
            }
            log("playback state " + stateName(state) + " at " + (p != null ? p.getCurrentPosition() : -1) + " ms"
                    + (s.swappedAt > 0 ? " (" + (SystemClock.elapsedRealtime() - s.swappedAt) + " ms after the swap)" : ""));
            tickSoon(this);
        }
    }

    private static void evaluateSelection(Session s) {
        Player p = s.player.get();
        if (p == null) return;
        Selected now = selectedSideLoaded(p);
        String was = s.selected != null ? s.selected.address : null;
        String is = now != null ? now.address : null;
        if (is == null ? was == null : is.equals(was)) return;
        s.selected = now;
        log("selected subtitle: " + (is != null ? is : "none / not side-loaded"));
        if (s.drawing != null && (is == null || !is.equals(s.drawingFor))) release(s, "another subtitle is selected", false);
        if (now == null) return;
        Line[] lines = s.files.get(now.address);
        if (lines != null) {
            startDrawing(s, now.address, lines, null); // replaced earlier in this play: back to it, no toast
            return;
        }
        Ask a = s.asks.get(now.address);
        if (a != null) {
            poll(s, a);
            return;
        }
        if (s.settled.contains(now.address) || s.stopped.contains(now.address)) return;
        String origin = originOf(now.address);
        String base;
        if (statePathFor(now.address) != null) {
            // one of ours that is not (yet) known to be a placeholder: one generic question, the addon decides
            base = baseOf(origin, Uri.parse(now.address).getEncodedPath());
        } else {
            // another subtitle: asked only of the addon that served it (same origin), never of another addon
            base = rememberedBase();
            if (base != null && (origin == null || !origin.equals(originOf(base)))) base = null;
        }
        if (base != null) startAsking(s, new Ask(now.address, originOf(base), pathOf(base) + "/patch/v1/subtitle", true, base));
    }

    /** The selected text track when it is a side-loaded subtitle, with the addon's own address. */
    private static Selected selectedSideLoaded(Player p) {
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
                    String address = addonAddress(c.uri);
                    return address != null ? new Selected(f.id, address) : null;
                }
            }
        }
        return null;
    }

    /**
     * The addon's address of a side-loaded subtitle. Stremio loads addon subtitles through its local streaming server
     * (http://127.0.0.1:11470/…?from=<the addon's address>); any http(s) query value is taken as the real address.
     */
    static String addonAddress(Uri uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        boolean local = host != null && (host.equals("127.0.0.1") || host.equals("localhost"));
        if (local) {
            try {
                for (String name : uri.getQueryParameterNames()) {
                    String v = uri.getQueryParameter(name);
                    if (v != null && (v.startsWith("http://") || v.startsWith("https://"))) return v;
                }
            } catch (Throwable ignored) {
                // an opaque address
            }
        }
        String s = uri.getScheme();
        return s != null && (s.equals("http") || s.equals("https")) ? uri.toString() : null;
    }

    /** /sub/<id>/<k>.srt → /sub/<id>/<k>.state (the addon's own route), else null. */
    static String statePathFor(String address) {
        Uri u = Uri.parse(address);
        String path = u.getEncodedPath(); // used in an address again: kept encoded
        if (path == null || !OUR_PATH.matcher(path).find()) return null;
        return path.substring(0, path.length() - ".srt".length()) + ".state";
    }

    // ---------------------------------------------------------------- the placeholder's tag

    /** "ssx1 <path>" written in Unicode tag characters (U+E0020…U+E007E, invisible) anywhere in the cues. */
    static String tagIn(List<Cue> cues) {
        if (cues == null) return null;
        for (Cue c : cues) {
            if (c == null || c.text == null) continue;
            String found = decodeTag(c.text.toString());
            if (found != null) return found;
        }
        return null;
    }

    static String decodeTag(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0xE0020 && cp <= 0xE007E) out.append((char) (cp - 0xE0000));
            else if (out.length() > 0 && out.toString().startsWith("ssx")) break;
        }
        String t = out.toString();
        if (!t.startsWith("ssx" + PROTOCOL + " ")) return null;
        String path = t.substring(5).trim();
        return path.startsWith("/") && !path.startsWith("//") && path.length() < 300 ? path : null;
    }

    private static boolean isPlaceholderText(List<Cue> cues) {
        if (cues == null) return false;
        for (Cue c : cues) {
            if (c != null && c.text != null && isPlaceholderLine(c.text.toString())) return true;
        }
        return false;
    }

    /** The processing line itself, not a subtitle line that merely says "processing" / "قيد المعالجة". */
    private static boolean isPlaceholderLine(String s) {
        return (s.contains(MARK_AR) && s.contains(MARK_AR_2)) || (s.contains(MARK_EN) && s.contains(MARK_EN_2));
    }

    // ---------------------------------------------------------------- asking the addon

    private static void startAsking(Session s, Ask a) {
        if (a.origin == null) return;
        Ask old = s.asks.get(a.address);
        if (old != null && !old.generic && a.generic) return;
        if (old != null) cancel(old); // the placeholder's question takes over from the generic one
        s.asks.put(a.address, a);
        log((a.generic ? "asking " : "waiting for ") + a.address + " via " + a.origin + a.path);
        poll(s, a);
    }

    private static void poll(Session s, Ask a) {
        if (s != session || s.asks.get(a.address) != a) return;
        if (a.pending != null) { // asked now: the scheduled question is not needed any more
            MAIN.removeCallbacks(a.pending);
            a.pending = null;
        }
        if (s.selected == null || !a.address.equals(s.selected.address)) return; // asked again when selected again
        if (SystemClock.elapsedRealtime() - a.startedAt > GIVE_UP_MS) {
            s.asks.remove(a.address);
            s.stopped.add(a.address);
            s.report.event("gave up waiting for " + slotOf(a.address));
            return;
        }
        if (a.busy) return;
        a.busy = true;
        final int gen = s.gen;
        NET.execute(() -> {
            if (a.cancelled) return; // the play ended while this question waited for a thread
            Answer r;
            long t0 = SystemClock.elapsedRealtime();
            try {
                r = ask(a);
            } catch (Throwable t) {
                r = new Answer();
                r.error = String.valueOf(t);
            }
            if (a.cancelled) return;
            r.tookMs = SystemClock.elapsedRealtime() - t0;
            final Answer answer = r;
            MAIN.post(() -> onAnswer(gen, a, answer));
        });
    }

    /** The next question for `a` in `delayMs`; replaces any question already scheduled, holds no play's state. */
    private static void pollLater(Session s, Ask a, long delayMs) {
        if (a.pending != null) MAIN.removeCallbacks(a.pending);
        final int gen = s.gen;
        a.pending = () -> {
            a.pending = null;
            Session now = session;
            if (now != null && now.gen == gen) poll(now, a);
        };
        MAIN.postDelayed(a.pending, delayMs);
    }

    /** The play ended or the question was replaced: nothing more is sent for it, and an open request is closed. */
    private static void cancel(Ask a) {
        a.cancelled = true;
        if (a.pending != null) {
            MAIN.removeCallbacks(a.pending);
            a.pending = null;
        }
        final HttpURLConnection c = a.conn;
        if (c != null) new Thread(c::disconnect, "SubsRefresh-close").start(); // closing may touch the network
    }

    private static void onAnswer(int gen, Ask a, Answer r) {
        a.busy = false;
        Session s = session;
        if (s == null || s.gen != gen || s.asks.get(a.address) != a) return;
        try {
            if (r.error != null) {
                s.report.lastError = r.error;
                s.report.lastErrorOrigin = a.origin;
                log("question failed: " + r.error);
                if (a.generic) { // a generic question is asked once per play
                    s.asks.remove(a.address);
                    s.settled.add(a.address);
                    return;
                }
                pollLater(s, a, a.errorDelay);
                a.errorDelay = Math.min(ERROR_RETRY_MAX_MS, a.errorDelay * 2);
                return;
            }
            a.errorDelay = RETRY_MS;
            if ("stop".equals(r.op)) {
                s.asks.remove(a.address);
                if (a.generic) {
                    s.settled.add(a.address);
                } else {
                    s.stopped.add(a.address);
                    log("the addon says stop for " + a.address);
                }
                return;
            }
            if ("replace".equals(r.op) && r.lines != null) {
                s.asks.remove(a.address);
                s.files.put(a.address, r.lines);
                log("replacement ready: " + r.lines.length + " lines for " + a.address);
                if (s.selected != null && a.address.equals(s.selected.address)) {
                    startDrawing(s, a.address, r.lines, r.toast != null ? r.toast : (a.generic ? null : TOAST));
                    s.report.swap(a, SystemClock.elapsedRealtime() - r.repliedAt);
                    if (a.base != null) s.reportBase = a.base;
                }
                return;
            }
            // an answer that was not held (an addon without the long wait): never ask faster than RETRY_MS
            long delay = Math.max(0, r.retryMs);
            if (r.tookMs < 5000) delay = Math.max(delay, RETRY_MS);
            pollLater(s, a, delay);
        } catch (Throwable t) {
            fail("onAnswer", t);
        }
    }

    private static final class Answer {
        String op = "wait";
        long retryMs = RETRY_MS;
        Line[] lines;
        String toast;
        String error;
        /** How long the question took (a held question takes long). */
        long tookMs;
        /** When the addon said the file is ready. */
        long repliedAt;
    }

    /** Background thread: one question, and the file when the answer is "replace". */
    private static Answer ask(Ask a) throws Exception {
        Answer r = new Answer();
        String q = a.origin + a.path + (a.path.contains("?") ? "&" : "?") + "v=" + PROTOCOL + "&wait=" + (a.generic ? 0 : WAIT_S)
                + "&src=" + URLEncoder.encode(a.address, "UTF-8");
        Response st = http("GET", q, null, a.generic ? TIMEOUT_MS : (WAIT_S + 15) * 1000, a);
        if (st.code == 404 && !a.generic && isProtocolStop(st)) {
            r.op = "stop"; // the addon answered: it does not know this subtitle (e.g. it restarted and forgot the job)
            return r;
        }
        if (st.code == 404 && !a.generic && a.path.endsWith(".state")) {
            // an addon without the question route: ask for the file itself and read its processing header
            Response f = http("GET", withQuery(a.address, "via=refresh"), null, TIMEOUT_MS, a);
            if (f.code == 200 && !f.processing) {
                Line[] lines = parseSrt(new String(f.body, StandardCharsets.UTF_8));
                if (lines.length > 0 && !hasPlaceholderLine(lines)) {
                    r.op = "replace";
                    r.lines = lines;
                    r.repliedAt = SystemClock.elapsedRealtime();
                    return r;
                }
            }
            r.retryMs = 15000;
            return r;
        }
        if (st.code == 404 && a.generic) {
            r.op = "stop";
            return r;
        }
        if (st.code != 200) {
            r.error = "HTTP " + st.code + (a.generic ? " from the subtitle question" : " from the ready question");
            return r;
        }
        r.repliedAt = SystemClock.elapsedRealtime();
        JSONObject j = new JSONObject(new String(st.body, StandardCharsets.UTF_8));
        String op = j.optString("op", "");
        if (op.isEmpty()) { // the first protocol's answer: {ready, enabled}
            op = !j.optBoolean("enabled", true) ? "stop" : j.optBoolean("ready", false) ? "replace" : "wait";
        }
        r.op = op;
        r.retryMs = Math.max(500, Math.min(120000, j.optLong("retryMs", RETRY_MS)));
        String toast = j.optString("toast", "");
        r.toast = toast.isEmpty() ? null : toast.substring(0, Math.min(80, toast.length()));
        if (!"replace".equals(op)) return r;
        String file = j.optString("file", "");
        String fileUrl = file.isEmpty() ? a.address : resolveSameOrigin(a.origin, file);
        if (fileUrl == null) {
            r.op = "wait";
            r.error = "a file on another origin was refused";
            return r;
        }
        if (a.cancelled) return r;
        Response f = http("GET", withQuery(fileUrl, "via=refresh"), null, TIMEOUT_MS, a);
        // "replace" but the file is not usable yet: asked again with the error back-off, never at the addon's pace
        if (f.code != 200 || f.processing) {
            r.op = "wait";
            r.error = f.code != 200 ? "file HTTP " + f.code : "the file is still marked processing";
            return r;
        }
        Line[] lines = parseSrt(new String(f.body, StandardCharsets.UTF_8));
        if (lines.length == 0 || hasPlaceholderLine(lines)) {
            r.op = "wait";
            r.error = lines.length == 0 ? "the file has no subtitle lines" : "the file is still the processing file";
            return r;
        }
        r.lines = lines;
        return r;
    }

    /** A 404 that is the protocol's own "stop" ({"subsense":1, "op":"stop"}); any other 404 is an addon without the route. */
    private static boolean isProtocolStop(Response st) {
        if (!st.json || st.body.length == 0) return false;
        try {
            JSONObject j = new JSONObject(new String(st.body, StandardCharsets.UTF_8));
            return j.has("subsense") && "stop".equals(j.optString("op"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasPlaceholderLine(Line[] lines) {
        for (Line l : lines) {
            String s = l.text.toString();
            if (isPlaceholderLine(s) || decodeTag(s) != null) return true;
        }
        return false;
    }

    private static final class Response {
        int code;
        byte[] body = new byte[0];
        boolean processing;
        boolean json;
    }

    /** One request; `owner` (may be null) holds the open connection so that the end of the play can close it. */
    private static Response http(String method, String address, byte[] body, int readTimeoutMs, Ask owner) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        if (owner != null) {
            owner.conn = conn;
            if (owner.cancelled) {
                owner.conn = null;
                throw new java.io.IOException("the play ended");
            }
        }
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false); // a redirect could leave the origin
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", "StremioSubsRefresh/" + PATCH_VERSION);
        conn.setRequestProperty("Cache-Control", "no-cache");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
        }
        try {
            if (owner != null) {
                // a close() before the connection existed did nothing: connect, then look again
                conn.connect();
                if (owner.cancelled) throw new java.io.IOException("the play ended");
            }
            Response r = new Response();
            r.code = conn.getResponseCode();
            r.processing = conn.getHeaderField("X-Addon-Processing") != null;
            String type = conn.getContentType();
            r.json = type != null && type.contains("json");
            if (r.code == 200) {
                try (InputStream in = conn.getInputStream()) {
                    r.body = readAll(in, MAX_FILE);
                }
            } else if (r.code == 404 && r.json) { // may be the protocol's own "stop" (read a little only)
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    try (InputStream in = err) {
                        r.body = readAll(in, 4096);
                    } catch (Throwable ignored) {
                        // not a stop then
                    }
                }
            }
            return r;
        } finally {
            if (owner != null) owner.conn = null;
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IllegalStateException("answer over " + max + " bytes");
        }
        return out.toByteArray();
    }

    static String originOf(String address) {
        try {
            Uri u = Uri.parse(address);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getEncodedAuthority();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A path or an absolute address, accepted only on `origin`. */
    static String resolveSameOrigin(String origin, String file) {
        if (file.startsWith("/") && !file.startsWith("//")) return origin + file;
        return origin.equals(originOf(file)) ? file : null;
    }

    private static String withQuery(String url, String q) {
        return url + (url.contains("?") ? "&" : "?") + q;
    }

    /** The addon's base address (origin + the path before /sub/): its other routes live under it. */
    static String baseOf(String origin, String path) {
        if (origin == null || path == null) return null;
        int i = path.indexOf("/sub/");
        return i >= 0 ? origin + path.substring(0, i) : null;
    }

    /** "<id>:<k>" for one of the addon's entries, else "another subtitle": a report never carries an address. */
    static String slotOf(String address) {
        Matcher m = Pattern.compile("/sub/([^/?#]+)/(\\d+)\\.srt").matcher(address == null ? "" : address);
        return m.find() ? m.group(1) + ":" + m.group(2) : "another subtitle";
    }

    private static String pathOf(String address) {
        String p = Uri.parse(address).getEncodedPath();
        return p == null ? "" : p;
    }

    private static void rememberBase(String base) {
        if (base == null || prefs == null) return;
        if (!base.equals(prefs.getString("base", null))) prefs.edit().putString("base", base).apply();
    }

    private static String rememberedBase() {
        return prefs != null ? prefs.getString("base", null) : null;
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
    private static final Pattern TAG_OTHER = Pattern.compile("</?(?!i>|/i>)[a-zA-Z][^>]*>|\\{\\\\[^}]*\\}");

    static Line[] parseSrt(String s) {
        if (s.startsWith("﻿")) s = s.substring(1);
        String[] rows = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Matcher m = TIME.matcher(rows[i]);
            if (!m.find()) continue;
            long a = ms(m, 1);
            long b = ms(m, 5);
            SpannableStringBuilder text = new SpannableStringBuilder();
            boolean italic = false;
            int j = i + 1;
            for (; j < rows.length && !rows[j].trim().isEmpty(); j++) {
                if (TIME.matcher(rows[j]).find()) break;
                String row = TAG_OTHER.matcher(rows[j]).replaceAll("").trim();
                if (row.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                // as Stremio's own onCues does to every line (NEW_LINE_REGEX "(^)" → "‎$1")
                text.append('‎');
                italic = appendWithItalics(text, row, italic);
            }
            i = j - 1;
            if (b > a && text.length() > 0) out.add(new Line(a, b, text));
        }
        Collections.sort(out, (x, y) -> Long.compare(x.start, y.start));
        return out.toArray(new Line[0]);
    }

    /** Appends `row`, turning <i>…</i> into an italic span (an <i> may span lines); returns whether italic is still open. */
    private static boolean appendWithItalics(SpannableStringBuilder text, String row, boolean italic) {
        int i = 0;
        int spanStart = italic ? text.length() : -1;
        while (i < row.length()) {
            if (row.startsWith("<i>", i)) {
                if (spanStart < 0) spanStart = text.length();
                i += 3;
            } else if (row.startsWith("</i>", i)) {
                if (spanStart >= 0 && text.length() > spanStart) {
                    text.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                spanStart = -1;
                i += 4;
            } else {
                text.append(row.charAt(i++));
            }
        }
        if (spanStart >= 0 && text.length() > spanStart) {
            text.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spanStart >= 0; // an <i> opened at the end of a line carries on to the next one
    }

    private static long ms(Matcher m, int g) {
        String frac = m.group(g + 3);
        while (frac.length() < 3) frac = frac + "0";
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60
                + Long.parseLong(m.group(g + 2))) * 1000 + Long.parseLong(frac);
    }

    // ---------------------------------------------------------------- drawing (one owner: us or Stremio)

    private static final Runnable TICK = () -> tick(session);
    private static final Runnable WATCHDOG = SubtitleRefresh::watchdog;

    private static void startDrawing(Session s, String address, Line[] lines, String toast) {
        s.drawing = lines;
        s.drawingFor = address;
        s.shown = Collections.emptyList();
        Player p = s.player.get();
        if (toast != null) {
            s.swappedAt = SystemClock.elapsedRealtime();
            log("swapped at " + (p != null ? p.getCurrentPosition() : -1) + " ms, text delay " + textDelayMs(s) + " ms");
            View v = s.playerView.get();
            // only over the player itself: never over another app when Stremio is in the background
            if (v != null && v.isAttachedToWindow() && v.getWindowVisibility() == View.VISIBLE) {
                Toast.makeText(v.getContext(), toast, Toast.LENGTH_LONG).show();
            }
        }
        tick(s);
        MAIN.removeCallbacks(WATCHDOG);
        MAIN.postDelayed(WATCHDOG, WATCHDOG_MS);
    }

    /** Hands the screen back to Stremio: its own last cues are shown again at once. */
    private static void release(Session s, String why, boolean failure) {
        MAIN.removeCallbacks(TICK);
        MAIN.removeCallbacks(WATCHDOG);
        if (s.drawing == null) return;
        s.drawing = null;
        s.drawingFor = null;
        draw(s, s.lastStremio);
        if (failure) {
            s.report.lastError = why;
            s.report.lastErrorOrigin = null; // the drawing: no addon's business but harmless to any
            s.report.releases++;
        }
        log("Stremio draws again: " + why);
    }

    private static void tickSoon(Listener from) {
        Session s = session;
        if (s == null || s.listener != from || s.drawing == null) return;
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    private static void tick(Session s) {
        MAIN.removeCallbacks(TICK);
        if (s == null || s != session || s.drawing == null) return;
        try {
            Player p = s.player.get();
            View v = subtitleViewOf(s);
            if (p == null || v == null) {
                release(s, "player or view gone", false);
                return;
            }
            if (s.selected == null || !s.drawingFor.equals(s.selected.address)) {
                release(s, "our subtitle is no longer selected", false);
                return;
            }
            Line[] lines = s.drawing;
            long t = p.getCurrentPosition() - textDelayMs(s); // Stremio shows a line at T when position − delay = T
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
            if (!sameText(now, s.shown)) draw(s, now);
            s.lastTickAt = SystemClock.uptimeMillis(); // the Handler's own clock: it stops while the device sleeps
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
            release(s, "error while drawing: " + t, true);
        }
    }

    /** The tick must have run recently while we draw; if the main loop lost it, draw again or step aside. */
    private static void watchdog() {
        Session s = session;
        if (s == null || s.drawing == null) return;
        long late = SystemClock.uptimeMillis() - s.lastTickAt;
        if (late > 5000) {
            release(s, "the drawing stalled for " + late + " ms", true);
            return;
        }
        if (late > 1500) tick(s);
        MAIN.postDelayed(WATCHDOG, WATCHDOG_MS);
    }

    private static View subtitleViewOf(Session s) {
        View v = s.subtitleView.get();
        if (v == null) {
            View pv = s.playerView.get();
            if (pv instanceof PlayerView) v = ((PlayerView) pv).getSubtitleView();
        }
        return v instanceof SubtitleView ? v : null;
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

    private static void draw(Session s, List<Cue> cues) {
        if (s.drawing != null) s.shown = cues;
        View v = subtitleViewOf(s);
        if (v == null) return;
        drawingNow = true;
        try {
            ((SubtitleView) v).setCues(cues);
        } finally {
            drawingNow = false;
        }
    }

    // ---------------------------------------------------------------- Stremio's subtitle delay (read only)

    private static WeakReference<Object> textRendererRef = new WeakReference<>(null);
    private static Method offsetMethod;

    /** Stremio keeps the subtitle delay in its own text renderer (getRenderOffsetMs); 0 when it cannot be read. */
    private static long textDelayMs(Session s) {
        Player p = s.player.get();
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
                if (r == null) {
                    s.report.delayReadable = false;
                    return 0;
                }
                offsetMethod = r.getClass().getMethod("getRenderOffsetMs");
                textRendererRef = new WeakReference<>(r);
            }
            s.report.delayReadable = true;
            return (Long) offsetMethod.invoke(r);
        } catch (Throwable t) {
            s.report.delayReadable = false;
            return 0;
        }
    }

    // ---------------------------------------------------------------- the play's report

    private static final class Report {
        String stremio = "";
        boolean hookPlayer;
        boolean hookCues;
        Boolean delayReadable;
        int placeholders;
        int releases;
        int bufferingAfterSwap;
        String lastError;
        /** The addon the last error came from (null = the drawing). */
        String lastErrorOrigin;
        final JSONArray swaps = new JSONArray();
        final List<String> events = new ArrayList<>();

        void swap(Ask a, long readyToDrawMs) {
            try {
                swaps.put(new JSONObject().put("src", a.address).put("generic", a.generic)
                        .put("waitedMs", SystemClock.elapsedRealtime() - a.startedAt).put("readyToDrawMs", readyToDrawMs));
            } catch (Throwable ignored) {
                // a report is best effort
            }
        }

        void event(String e) {
            if (events.size() < 20) events.add(e);
            log(e);
        }
    }

    private static void endSession(Session s, String why) {
        release(s, why, false);
        for (Ask a : s.asks.values()) cancel(a); // nothing more is asked for a play that ended
        Player p = s.player.get();
        if (p != null) {
            try {
                p.removeListener(s.listener);
            } catch (Throwable ignored) {
                // the old player may be released already
            }
        }
        if (session == s) session = null;
        textRendererRef = new WeakReference<>(null);
        sendReport(s);
        log("session " + s.gen + " ended: " + why);
    }

    /** One report per play that involved the protocol, to the addon that used it. Best effort, never retried. */
    private static void sendReport(Session s) {
        String base = s.reportBase; // only the addon this play asked: a report never goes to another addon
        if (base == null) return;
        JSONArray waiting = new JSONArray();
        // only what concerns the addon receiving the report: never another addon's addresses or errors
        String own = originOf(base);
        for (Ask a : s.asks.values()) if (!a.generic && a.placeholderSeen && own != null && own.equals(a.origin)) waiting.put(a.address);
        JSONArray swaps = new JSONArray();
        for (int i = 0; i < s.report.swaps.length(); i++) {
            JSONObject x = s.report.swaps.optJSONObject(i);
            if (x != null && own != null && own.equals(originOf(x.optString("src")))) swaps.put(x);
        }
        String lastError = s.report.lastErrorOrigin == null || s.report.lastErrorOrigin.equals(own) ? s.report.lastError : null;
        try {
            JSONObject j = new JSONObject()
                    .put("v", PROTOCOL).put("patch", PATCH_VERSION).put("stremio", s.report.stremio)
                    .put("hooks", new JSONObject().put("player", s.report.hookPlayer).put("cues", s.report.hookCues)
                            .put("delay", s.report.delayReadable == null ? JSONObject.NULL : s.report.delayReadable))
                    .put("placeholders", s.report.placeholders).put("swaps", swaps)
                    .put("waiting", waiting).put("releases", s.report.releases)
                    .put("bufferingAfterSwap", s.report.bufferingAfterSwap)
                    .put("lastError", lastError == null ? JSONObject.NULL : lastError)
                    .put("events", new JSONArray(s.report.events));
            final byte[] body = j.toString().getBytes(StandardCharsets.UTF_8);
            final String url = base + "/patch/v1/report";
            NET.execute(() -> {
                try {
                    http("POST", url, body, TIMEOUT_MS, null);
                } catch (Throwable ignored) {
                    // best effort
                }
            });
        } catch (Throwable t) {
            fail("report", t);
        }
    }

    // ---------------------------------------------------------------- misc

    private static String appVersion(View v) {
        try {
            Context c = v.getContext();
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
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
