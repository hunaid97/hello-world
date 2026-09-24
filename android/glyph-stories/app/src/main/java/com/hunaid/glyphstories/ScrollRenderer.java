package com.hunaid.glyphstories;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a story into one long strip of 9-pixel columns and slices 13x13 frames out of it,
 * scrolling right to left. Keeps track of which word each column belongs to so reading can
 * resume by word.
 */
public final class ScrollRenderer {

    /** Values the Glyph Matrix accepts per LED: 0 (off) to 4095 (full). */
    public static final int MAX_BRIGHTNESS = 4095;
    /** Every lit LED uses this one fixed level (50%); unlit LEDs are 0. Nothing in between. */
    public static final int LED_BRIGHTNESS = 2048;

    private final String[] words;
    private final int[] columns;      // bitmask per column (bit 0 = top text row)
    private final int[] columnWord;   // word index for each column
    private final int[] wordStart;    // first column of each word

    public ScrollRenderer(String text) {
        words = splitWords(text);
        List<Integer> cols = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        wordStart = new int[words.length];
        for (int w = 0; w < words.length; w++) {
            if (w > 0) {
                for (int i = 0; i < GlyphFont.WORD_GAP; i++) { cols.add(0); owner.add(w); }
            }
            wordStart[w] = cols.size();
            for (int c : GlyphFont.wordColumns(words[w])) { cols.add(c); owner.add(w); }
        }
        columns = new int[cols.size()];
        columnWord = new int[cols.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = cols.get(i);
            columnWord[i] = owner.get(i);
        }
    }

    static String[] splitWords(String text) {
        String t = GlyphFont.normalise(text == null ? "" : text).trim();
        if (t.isEmpty()) return new String[0];
        return t.split("\\s+");
    }

    public int wordCount() { return words.length; }

    public int columnCount() { return columns.length; }

    /**
     * Scroll offset that puts {@code wordIndex} just off the right edge, so it enters from the right.
     * Offset = strip column shown in the matrix's leftmost column (may be negative = blank lead-in).
     */
    public int offsetForWord(int wordIndex, int matrixSize) {
        if (words.length == 0) return 0;
        int w = Math.max(0, Math.min(wordIndex, words.length - 1));
        return wordStart[w] - matrixSize;
    }

    /** True once the whole story has scrolled off the left edge. */
    public boolean isFinished(int offset) {
        return offset >= columns.length;
    }

    /** Word currently in the middle of the matrix (used to remember where the reader got to). */
    public int wordAt(int offset, int matrixSize) {
        if (columns.length == 0) return 0;
        int col = offset + matrixSize / 2;
        if (col < 0) return 0;
        if (col >= columns.length) return words.length - 1;
        return columnWord[col];
    }

    /**
     * One frame for an N x N matrix, row-major. Text rows are centred vertically
     * (on the 13x13 Phone (4a) Pro they land on rows 2-10, the widest part of the round matrix).
     */
    public int[] frame(int offset, int matrixSize, int brightness) {
        int[] out = new int[matrixSize * matrixSize];
        int top = (matrixSize - GlyphFont.HEIGHT) / 2;
        for (int x = 0; x < matrixSize; x++) {
            int col = offset + x;
            if (col < 0 || col >= columns.length) continue;
            int mask = columns[col];
            if (mask == 0) continue;
            for (int r = 0; r < GlyphFont.HEIGHT; r++) {
                if ((mask & (1 << r)) != 0) {
                    int y = top + r;
                    if (y >= 0 && y < matrixSize) out[y * matrixSize + x] = brightness;
                }
            }
        }
        return out;
    }

    /**
     * Frame for a fractional offset. Snaps to the nearest whole column so every LED is either
     * fully on or off, with no in-between brightness.
     */
    public int[] frame(double offset, int matrixSize, int brightness) {
        return frame((int) Math.round(offset), matrixSize, brightness);
    }

    private int maskAt(int col) {
        return col < 0 || col >= columns.length ? 0 : columns[col];
    }

    public boolean isFinished(double offset) {
        return offset >= columns.length;
    }

    public int wordAt(double offset, int matrixSize) {
        return wordAt((int) Math.floor(offset), matrixSize);
    }

    /** Where to resume next time: 10 words before the word the reader stopped on. */
    public static int resumeWord(int stoppedAtWord) {
        return Math.max(0, stoppedAtWord - 10);
    }
}
