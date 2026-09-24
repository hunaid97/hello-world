package com.hunaid.glyphstories;

import java.util.HashMap;
import java.util.Map;

/**
 * 9-pixel-tall bitmap font for the Glyph Matrix.
 *
 * Letters A-Z come from the Figma file (HN_caterpie_working-file, "Section 2"): each is 3 wide x 9 tall.
 * Digits and punctuation are not in the Figma font, so they are drawn here in the same style.
 *
 * Each glyph is stored as rows (top to bottom) of '0'/'1' characters; all rows of a glyph share a width.
 */
public final class GlyphFont {

    public static final int HEIGHT = 9;
    /** Blank columns between letters. */
    public static final int LETTER_GAP = 1;
    /** Blank columns for a space between words. */
    public static final int WORD_GAP = 3;

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void put(char c, String rows) {
        String[] r = rows.split(",");
        if (r.length != HEIGHT) throw new IllegalStateException("Glyph " + c + " must have " + HEIGHT + " rows");
        GLYPHS.put(c, r);
    }

    static {
        // --- From Figma ---
        put('A', "010,101,101,101,111,101,101,101,101");
        put('B', "110,101,101,101,110,101,101,101,110");
        put('C', "011,100,100,100,100,100,100,100,011");
        put('D', "110,101,101,101,101,101,101,101,110");
        put('E', "011,100,100,100,111,100,100,100,011");
        put('F', "011,100,100,100,111,100,100,100,100");
        put('G', "011,100,100,100,101,101,101,101,011");
        put('H', "101,101,101,101,111,101,101,101,101");
        put('I', "111,010,010,010,010,010,010,010,111");
        put('J', "111,010,010,010,010,010,010,010,001");
        put('K', "101,101,101,101,110,101,101,101,101");
        put('L', "100,100,100,100,100,100,100,100,111");
        put('M', "101,111,111,111,111,101,101,101,101");
        put('N', "110,101,101,101,101,101,101,101,101");
        put('O', "010,101,101,101,101,101,101,101,010");
        put('P', "110,101,101,101,110,100,100,100,100");
        put('Q', "010,101,101,101,101,101,101,101,011");
        put('R', "110,101,101,101,110,101,101,101,101");
        put('S', "011,100,100,100,110,001,001,001,110");
        put('T', "111,010,010,010,010,010,010,010,010");
        put('U', "101,101,101,101,101,101,101,101,111");
        put('V', "101,101,101,101,101,101,101,101,010");
        put('W', "101,101,101,101,101,101,101,111,101");
        put('X', "101,101,101,101,010,010,010,101,101");
        put('Y', "101,101,101,101,010,010,010,010,010");
        put('Z', "111,001,001,010,010,010,010,100,111");

        // --- Added in the same style (not in the Figma file) ---
        put('0', "111,101,101,101,101,101,101,101,111");
        put('1', "010,110,010,010,010,010,010,010,111");
        put('2', "110,001,001,001,010,100,100,100,111");
        put('3', "110,001,001,001,010,001,001,001,110");
        put('4', "101,101,101,101,111,001,001,001,001");
        put('5', "111,100,100,100,110,001,001,001,110");
        put('6', "011,100,100,100,110,101,101,101,010");
        put('7', "111,001,001,001,010,010,010,010,010");
        put('8', "010,101,101,101,010,101,101,101,010");
        put('9', "010,101,101,101,011,001,001,001,110");
        put('.', "0,0,0,0,0,0,0,0,1");
        put(',', "0,0,0,0,0,0,0,1,1");
        put('!', "1,1,1,1,1,1,0,0,1");
        put('?', "110,001,001,001,010,010,000,000,010");
        put('\'', "1,1,0,0,0,0,0,0,0");
        put('"', "101,101,000,000,000,000,000,000,000");
        put('-', "000,000,000,000,111,000,000,000,000");
        put(':', "0,0,0,1,0,0,0,1,0");
        put(';', "0,0,0,1,0,0,0,1,1");
        put('(', "01,10,10,10,10,10,10,10,01");
        put(')', "10,01,01,01,01,01,01,01,10");
    }

    private GlyphFont() {}

    /** Normalises text for this caps-only font: upper case, curly quotes/dashes to plain ones. */
    public static String normalise(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '‘': case '’': case '`': b.append('\''); break;
                case '“': case '”': b.append('"'); break;
                case '–': case '—': b.append('-'); break;
                case '…': b.append("..."); break;
                case '&': b.append("AND"); break;
                default: b.append(Character.toUpperCase(c));
            }
        }
        return b.toString();
    }

    /** Glyph rows for a character, or null if the font can't draw it (it is then skipped). */
    public static String[] glyph(char c) {
        return GLYPHS.get(c);
    }

    /**
     * Column bitmasks for one word: bit r (0 = top row) is set when that pixel is lit.
     * Letters are separated by {@link #LETTER_GAP} blank columns.
     */
    public static int[] wordColumns(String word) {
        int total = 0;
        int drawn = 0;
        for (char c : word.toCharArray()) {
            String[] g = GLYPHS.get(c);
            if (g == null) continue;
            if (drawn++ > 0) total += LETTER_GAP;
            total += g[0].length();
        }
        int[] cols = new int[total];
        int x = 0;
        drawn = 0;
        for (char c : word.toCharArray()) {
            String[] g = GLYPHS.get(c);
            if (g == null) continue;
            if (drawn++ > 0) x += LETTER_GAP;
            int w = g[0].length();
            for (int col = 0; col < w; col++) {
                int mask = 0;
                for (int row = 0; row < HEIGHT; row++) {
                    if (g[row].charAt(col) == '1') mask |= 1 << row;
                }
                cols[x + col] = mask;
            }
            x += w;
        }
        return cols;
    }
}
