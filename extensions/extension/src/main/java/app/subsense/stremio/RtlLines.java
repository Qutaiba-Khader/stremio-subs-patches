package app.subsense.stremio;

import android.text.SpannableStringBuilder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-to-left subtitle lines. Two things lay every subtitle line out left to right: Stremio puts a left-to-right mark
 * (U+200E) in front of each line, and Media3's own SubtitlePainter wraps each line as left-to-right text when it draws
 * it (BidiUtils.wrapText). An Arabic or Hebrew line then shows its punctuation on the wrong side ("-مرحبا" shows the
 * dash on the left). At the start of SubtitleView.setCues this drops Stremio's mark and wraps every line whose first
 * letter is right-to-left in a right-to-left embedding (U+202B … U+202C), which both of those keep. Lines that already
 * start with an embedding or isolate are left as they are. Only Media3's Cue is used (through M3: a shrunk app renames
 * it); nothing of Stremio's own code is read. Any failure leaves the cues exactly as they came.
 */
public final class RtlLines {
    private static final String TAG = "SubsRtl";
    private static final char LRM = '\u200e';
    private static final char RLE = '\u202b';
    private static final char PDF = '\u202c';
    private static boolean logged;

    private RtlLines() {
    }

    /** Start of SubtitleView.setCues(cues): returns the cues to draw (the same list when nothing needed changing). */
    public static List<?> fix(List<?> cues) {
        if (cues == null || cues.isEmpty()) return cues;
        try {
            List<Object> out = null;
            for (int i = 0; i < cues.size(); i++) {
                Object cue = cues.get(i);
                CharSequence fixed = cue == null ? null : fixText(M3.cueText(cue));
                if (fixed != null && out == null) out = new ArrayList<>(cues.subList(0, i));
                if (out != null) out.add(fixed != null ? M3.withText(cue, fixed) : cue);
            }
            if (out == null) return cues;
            if (!logged) {
                logged = true;
                Log.i(TAG, "right-to-left subtitle lines embedded right to left");
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "fix failed, cues left as they came", t);
            return cues;
        }
    }

    /** The text with every right-to-left line embedded (Stremio's mark in front dropped), or null when no line needed it. */
    static CharSequence fixText(CharSequence text) {
        if (text == null || text.length() == 0) return null;
        SpannableStringBuilder b = null;
        int lineStart = 0;
        int shift = 0; // characters added so far (each fixed line: -1 for a dropped mark, +2 for the embedding)
        int n = text.length();
        while (lineStart <= n) {
            int lineEnd = lineStart;
            while (lineEnd < n && text.charAt(lineEnd) != '\n') lineEnd++;
            int from = lineStart < lineEnd && text.charAt(lineStart) == LRM ? lineStart + 1 : lineStart;
            if (from < lineEnd && !embedded(text.charAt(from)) && rightToLeft(text, from, lineEnd)) {
                if (b == null) b = new SpannableStringBuilder(text); // keeps any styling spans
                int at = lineStart + shift;
                if (from > lineStart) {
                    b.delete(at, at + 1);
                    shift--;
                }
                b.insert(lineEnd + shift, String.valueOf(PDF));
                b.insert(at, String.valueOf(RLE));
                shift += 2;
            }
            lineStart = lineEnd + 1;
        }
        return b;
    }

    /** An explicit embedding, override or isolate already opens the line: its author chose the direction. */
    static boolean embedded(char c) {
        return (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069');
    }

    /** True when the first letter between from and to (skipping marks, digits and punctuation) is right-to-left. */
    static boolean rightToLeft(CharSequence text, int from, int to) {
        for (int i = from; i < to; ) {
            int cp = Character.codePointAt(text, i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            i += Character.charCount(cp);
        }
        return false;
    }
}
