package app.subsense.stremio;

import android.net.Uri;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The extension's only door to Media3. Stremio for Android TV 1.12.0 shrinks and renames Media3 with R8 (Player, MediaItem,
 * Cue, PlayerView… get short random names that change with every release), so the extension never names a Media3 class.
 *
 * The methods marked "written by the patch" are stand-ins: when patching, each one is replaced by a call to the app's own
 * Media3 member, found by its structure (what it holds and does, never its name) — see SubtitleRefreshPatch.kt. A release
 * where one is not found fails the patch instead of shipping a broken app. Everything else here reads the objects those
 * calls return by reflection on their field types, again never by name. Main thread only (no locking).
 */
public final class M3 {
    private M3() {
    }

    private static RuntimeException stub() {
        return new IllegalStateException("M3 not patched");
    }

    // ---------------------------------------------------------------- written by the patch

    /** Player.getCurrentPosition(). */
    static long position(Object player) {
        throw stub();
    }

    /** Player.isPlaying(). */
    static boolean playing(Object player) {
        throw stub();
    }

    /** Player.addListener(Player.Listener). */
    static void addListener(Object player, Object listener) {
        throw stub();
    }

    /** Player.removeListener(Player.Listener). */
    static void removeListener(Object player, Object listener) {
        throw stub();
    }

    /** Player.getCurrentMediaItem() (null = none). */
    static Object mediaItem(Object player) {
        throw stub();
    }

    /** Player.getCurrentTracks(). */
    static Object tracks(Object player) {
        throw stub();
    }

    /** Format.id. */
    static String formatId(Object format) {
        throw stub();
    }

    /** PlayerView.getSubtitleView() — only for an object of playerViewType(). */
    static View subtitleViewOf(View playerView) {
        throw stub();
    }

    /** SubtitleView.setCues(List) — only for an object of subtitleViewType(). */
    static void setCues(View subtitleView, List<?> cues) {
        throw stub();
    }

    /** Player.Listener. */
    static Class<?> listenerType() {
        throw stub();
    }

    /** PlayerView. */
    static Class<?> playerViewType() {
        throw stub();
    }

    /** SubtitleView. */
    static Class<?> subtitleViewType() {
        throw stub();
    }

    /** MediaItem.LocalConfiguration. */
    static Class<?> localConfigType() {
        throw stub();
    }

    /** MediaItem.SubtitleConfiguration. */
    static Class<?> subtitleConfigType() {
        throw stub();
    }

    /** TrackGroup. */
    static Class<?> trackGroupType() {
        throw stub();
    }

    /** Cue. */
    static Class<?> cueType() {
        throw stub();
    }

    /** TextRenderer (Stremio wraps or extends it to keep its subtitle delay). */
    static Class<?> textRendererType() {
        throw stub();
    }

    // ---------------------------------------------------------------- the player's events

    /**
     * A Player.Listener that runs `onEvent` for every event the player sends (the player calls it on the main thread).
     * A proxy: the listener's own methods are renamed in a shrunk app, and any event may mean the selection or the timing
     * changed, so every event is handled the same way.
     */
    static Object listener(Runnable onEvent) {
        Class<?> type = listenerType();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals":
                        return args != null && args.length == 1 && proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "SubsRefresh.Listener@" + Integer.toHexString(System.identityHashCode(proxy));
                }
            }
            onEvent.run();
            return zero(m.getReturnType());
        });
    }

    private static Object zero(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        return null;
    }

    // ---------------------------------------------------------------- the media item

    /** MediaItem.localConfiguration (null = none). */
    static Object localConfig(Object mediaItem) {
        return mediaItem == null ? null : fieldOfType(mediaItem, localConfigType());
    }

    /** The one Uri an object holds (LocalConfiguration.uri, SubtitleConfiguration.uri), else null. */
    static Uri uriOf(Object o) {
        Object u = o == null ? null : fieldOfType(o, Uri.class);
        return u instanceof Uri ? (Uri) u : null;
    }

    /** LocalConfiguration.subtitleConfigurations, in order (the list whose items are exactly SubtitleConfiguration). */
    static List<?> subtitleConfigs(Object localConfig) {
        if (localConfig == null) return Collections.emptyList();
        Class<?> sc = subtitleConfigType();
        List<?> empty = null;
        for (Field f : fields(localConfig.getClass())) {
            if (!List.class.isAssignableFrom(f.getType()) && f.getType() != Object.class) continue;
            Object v = get(f, localConfig);
            if (!(v instanceof List)) continue;
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                if (empty == null) empty = l;
                continue;
            }
            boolean all = true;
            for (Object x : l) all &= x != null && x.getClass() == sc; // not MediaItem.Subtitle, its old subclass copy
            if (all) return l;
        }
        return empty != null ? empty : Collections.emptyList();
    }

    // ---------------------------------------------------------------- the tracks

    /** Tracks.groups. */
    static List<?> groups(Object tracks) {
        if (tracks == null) return Collections.emptyList();
        for (Field f : fields(tracks.getClass())) {
            Object v = get(f, tracks);
            if (v instanceof List) return (List<?>) v;
        }
        return Collections.emptyList();
    }

    /** Tracks.Group's per-track "selected" flags (its only boolean[]), or null. */
    static boolean[] selectedFlags(Object group) {
        Object v = group == null ? null : fieldOfType(group, boolean[].class);
        return v instanceof boolean[] ? (boolean[]) v : null;
    }

    /** The formats of a Tracks.Group (its TrackGroup's only object array), or an empty array. */
    static Object[] formats(Object group) {
        Object tg = group == null ? null : fieldOfType(group, trackGroupType());
        if (tg == null) return new Object[0];
        for (Field f : fields(tg.getClass())) {
            if (!f.getType().isArray() || f.getType().getComponentType().isPrimitive()) continue;
            Object v = get(f, tg);
            if (v instanceof Object[]) return (Object[]) v;
        }
        return new Object[0];
    }

    // ---------------------------------------------------------------- cues

    private static Field cueTextField;
    private static Method buildUpon;
    private static Method setText;
    private static Method build;
    private static Constructor<?> newBuilder;

    /** Cue.text (its only CharSequence), or null. */
    static CharSequence cueText(Object cue) {
        if (cue == null) return null;
        try {
            if (cueTextField == null || cueTextField.getDeclaringClass() != cue.getClass()) {
                cueTextField = null;
                for (Field f : fields(cue.getClass())) if (f.getType() == CharSequence.class) { cueTextField = f; break; }
                if (cueTextField == null) return null;
            }
            return (CharSequence) cueTextField.get(cue);
        } catch (Throwable t) {
            return null;
        }
    }

    /** cue.buildUpon().setText(text).build(). */
    static Object withText(Object cue, CharSequence text) throws Exception {
        builderMethods();
        Object b = buildUpon.invoke(cue);
        setText.invoke(b, text);
        return build.invoke(b);
    }

    /** new Cue.Builder().setText(text).build(). */
    static Object newCue(CharSequence text) throws Exception {
        builderMethods();
        Object b = newBuilder.newInstance();
        setText.invoke(b, text);
        return build.invoke(b);
    }

    /** Cue.buildUpon (no arguments, returns a builder whose no-argument method builds a Cue), the builder's setText and build. */
    private static void builderMethods() throws Exception {
        if (build != null) return;
        Class<?> cue = cueType();
        for (Method m : cue.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
            Class<?> b = m.getReturnType();
            if (b.isPrimitive() || b == cue || b.getName().startsWith("java.") || b.getName().startsWith("android.")) continue;
            Method bm = null;
            Method st = null;
            for (Method x : b.getDeclaredMethods()) {
                if (Modifier.isStatic(x.getModifiers())) continue;
                Class<?>[] p = x.getParameterTypes();
                if (p.length == 0 && x.getReturnType() == cue) bm = x;
                if (p.length == 1 && p[0] == CharSequence.class) st = x;
            }
            if (bm == null || st == null) continue;
            m.setAccessible(true);
            bm.setAccessible(true);
            st.setAccessible(true);
            Constructor<?> c = b.getDeclaredConstructor();
            c.setAccessible(true);
            buildUpon = m;
            setText = st;
            newBuilder = c;
            build = bm;
            return;
        }
        throw new IllegalStateException("no Cue builder in " + cue.getName());
    }

    // ---------------------------------------------------------------- Stremio's subtitle delay (read only)

    /**
     * Stremio's text renderer, found from the player: the object in a renderer array that wraps a TextRenderer (1.12.0) or
     * is Stremio's CustomTextRenderer (1.11.3, not shrunk).
     * Searched through the player's fields two levels deep (Stremio's player → Media3's player → its renderers).
     */
    static Object findTextRenderer(Object player) {
        Class<?> tr = textRendererType();
        List<Object> level = new ArrayList<>();
        level.add(player);
        Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object o : level) {
                if (o == null || seen.put(o, true) != null) continue;
                for (Field f : fields(o.getClass())) {
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t == String.class) continue;
                    Object v = get(f, o);
                    if (v == null) continue;
                    if (v instanceof Object[]) {
                        for (Object e : (Object[]) v) if (e != null && (e.getClass().getName().endsWith("CustomTextRenderer") || fieldOfType(e, tr) != null)) return e;
                    } else if (!v.getClass().getName().startsWith("java.") && !v.getClass().getName().startsWith("android.") && next.size() < 200) {
                        next.add(v);
                    }
                }
            }
            level = next;
        }
        return null;
    }

    /** The subtitle delay a text renderer keeps, in ms: getRenderOffsetMs() when it has one, else its wrapper's one long (µs). */
    static long delayMs(Object renderer) throws Exception {
        try {
            Method m = renderer.getClass().getMethod("getRenderOffsetMs");
            return (Long) m.invoke(renderer);
        } catch (NoSuchMethodException e) {
            Field only = null;
            for (Field f : fields(renderer.getClass())) {
                if (f.getType() != long.class || f.getDeclaringClass() != renderer.getClass()) continue;
                if (only != null) throw new IllegalStateException("more than one long in " + renderer.getClass().getName());
                only = f;
            }
            if (only == null) throw new IllegalStateException("no delay in " + renderer.getClass().getName());
            return only.getLong(renderer) / 1000;
        }
    }

    // ---------------------------------------------------------------- reflection by type

    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    /** The instance fields of a class and its superclasses (up to Object), accessible. */
    static Field[] fields(Class<?> c) {
        Field[] got = FIELDS.get(c);
        if (got != null) return got;
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (Throwable ignored) {
                    // a field the runtime will not open is skipped
                }
            }
        }
        got = out.toArray(new Field[0]);
        FIELDS.put(c, got);
        return got;
    }

    /** The value of the object's first field declared with exactly this type (null = none or null). */
    static Object fieldOfType(Object o, Class<?> type) {
        for (Field f : fields(o.getClass())) if (f.getType() == type) return get(f, o);
        return null;
    }

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
