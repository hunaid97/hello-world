package com.hunaid.glyphstories;

/**
 * Plain-Java checks for the font and scroller (no Android needed):
 *   javac -d out src/main/java/com/hunaid/glyphstories/{GlyphFont,ScrollRenderer}.java src/test/java/com/hunaid/glyphstories/ScrollRendererTest.java
 *   java -cp out com.hunaid.glyphstories.ScrollRendererTest
 */
public class ScrollRendererTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok  " + what);
    }

    public static void main(String[] args) {
        // Every letter is 3 wide and 9 tall
        for (char c = 'A'; c <= 'Z'; c++) {
            String[] g = GlyphFont.glyph(c);
            check(g != null && g.length == 9 && g[0].length() == 3, "glyph " + c + " is 3x9");
        }

        // Word layout: "HI" = 3 + 1 gap + 3 columns
        check(GlyphFont.wordColumns("HI").length == 7, "HI is 7 columns wide");
        // Unknown characters are skipped, lower case is upper-cased by normalise
        check(GlyphFont.wordColumns(GlyphFont.normalise("h~i")).length == 7, "unknown chars skipped");

        ScrollRenderer r = new ScrollRenderer("one two three four five six seven eight nine ten eleven twelve");
        check(r.wordCount() == 12, "12 words");

        // Starting at a word puts it just off the right edge: the first frame is blank
        int off = r.offsetForWord(0, 13);
        int[] f = r.frame(off, 13, 4095);
        int lit = 0;
        for (int v : f) if (v != 0) lit++;
        check(lit == 0, "first frame starts blank (text enters from the right)");

        // After scrolling 13 columns, the first word fills the left side of the matrix
        f = r.frame(off + 13, 13, 4095);
        lit = 0;
        for (int v : f) if (v != 0) lit++;
        check(lit > 0, "text visible after scrolling in");
        // Text only on rows 2..10 (centred 9 rows in 13)
        for (int y = 0; y < 13; y++) for (int x = 0; x < 13; x++) {
            if (f[y * 13 + x] != 0) check(y >= 2 && y <= 10, "pixel row " + y + " within 2..10");
        }

        // Word tracking and resume
        int mid = r.offsetForWord(11, 13) + 13; // word 11 scrolled into view
        check(r.wordAt(mid, 13) >= 10, "wordAt follows scroll position");
        check(ScrollRenderer.resumeWord(25) == 15, "resume 10 words back");
        check(ScrollRenderer.resumeWord(4) == 0, "resume clamps at start");
        check(r.isFinished(r.columnCount()), "finished after last column");

        // Print a preview of "HELLO" as ASCII to eyeball the font
        ScrollRenderer hello = new ScrollRenderer("HELLO WORLD");
        int[] strip = hello.frame(0, 40, 1);
        StringBuilder sb = new StringBuilder();
        for (int y = 15; y < 25; y++) {
            for (int x = 0; x < 40; x++) sb.append(strip[y * 40 + x] != 0 ? '#' : '.');
            sb.append('\n');
        }
        System.out.print(sb);
        System.out.println("ALL PASSED");
    }
}
