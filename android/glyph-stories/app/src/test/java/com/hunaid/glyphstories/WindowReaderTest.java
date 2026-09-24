package com.hunaid.glyphstories;

/**
 * Plain-Java checks for WINDOW mode motion, with simulated sensor readings:
 *   javac -d out src/main/java/com/hunaid/glyphstories/{GlyphFont,ScrollRenderer,WindowReader}.java src/test/java/com/hunaid/glyphstories/WindowReaderTest.java
 *   java -cp out com.hunaid.glyphstories.WindowReaderTest
 */
public class WindowReaderTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok  " + what);
    }

    static final long MS = 1_000_000L;
    static final String TEXT;
    static {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 35; i++) b.append("word").append(i).append(' ');
        TEXT = b.toString();
    }

    /** Simulate a sideways slide: accelerate for half the time, decelerate for the other half, 100 Hz. */
    static long slide(WindowReader w, long t, double peak, int ms, java.util.List<WindowReader.Event> events) {
        int n = ms / 10;
        for (int i = 0; i < n; i++) {
            double a = i < n / 2 ? peak : -peak;
            WindowReader.Event e = w.onSidewaysAcceleration(a, t);
            if (e != WindowReader.Event.NONE) events.add(e);
            t += 10 * MS;
        }
        return t;
    }

    static long still(WindowReader w, long t, int ms) {
        for (int i = 0; i < ms / 10; i++) { w.onSidewaysAcceleration(0.02, t); t += 10 * MS; }
        return t;
    }

    public static void main(String[] args) {
        java.util.List<WindowReader.Event> ev = new java.util.ArrayList<>();
        WindowReader w = new WindowReader(TEXT, 0, 13, 500, false);
        check(w.lineCount() == 4, "35 words -> 4 lines of 10");
        check(w.line() == 0 && w.offset() == -1, "starts at the beginning of line 0");

        // Slide to the reader's right = device -x acceleration first
        long t = 1;
        t = still(w, t, 100);
        double before = w.offset();
        t = slide(w, t, -2.0, 400, ev);
        t = still(w, t, 300);
        double moved = w.offset() - before;
        check(moved > 5, "sliding right pans forward along the line (moved " + String.format("%.1f", moved) + " cols)");

        // Staying still: no drift
        double settled = w.offset();
        t = still(w, t, 2000);
        check(Math.abs(w.offset() - settled) < 0.5, "no drift while still");

        // Up/down (not fed) and noise below threshold do nothing
        for (int i = 0; i < 200; i++) { w.onSidewaysAcceleration(0.1, t); t += 10 * MS; }
        check(Math.abs(w.offset() - settled) < 0.5, "sub-noise jitter ignored");

        // Slide left goes back
        t = slide(w, t, 2.0, 400, ev);
        t = still(w, t, 300);
        check(w.offset() < settled - 3, "sliding left pans back");

        // Flick right -> next line, and the twist back is ignored
        ev.clear();
        WindowReader.Event e = w.onYawRate(-7, t);
        check(e == WindowReader.Event.NEXT_LINE && w.line() == 1, "flick right -> next line");
        check(w.onYawRate(+7, t + 150 * MS) == WindowReader.Event.NONE && w.line() == 1, "twist back ignored");
        t += 800 * MS;
        e = w.onYawRate(+7, t);
        check(e == WindowReader.Event.PREVIOUS_LINE && w.line() == 0, "flick left -> previous line");
        check(w.onYawRate(-2, t + 900 * MS) == WindowReader.Event.NONE, "slow turn is not a flick");

        // Sliding far right runs off the end of the line -> next line
        ev.clear();
        t += 900 * MS;
        for (int k = 0; k < 20 && w.line() == 0; k++) { t = slide(w, t, -3.0, 400, ev); t = still(w, t, 200); }
        check(w.line() == 1 && ev.contains(WindowReader.Event.NEXT_LINE), "sliding past the end -> next line");
        check(w.offset() <= 0, "new line starts at its beginning");

        // Resume position is the first word of the current line
        check(w.firstWordOfLine() == 10, "resume from start of current line");
        WindowReader r = new WindowReader(TEXT, 23, 13, 500, false);
        check(r.line() == 2, "starting at word 23 opens line 2");

        // End of story
        WindowReader last = new WindowReader(TEXT, 34, 13, 500, false);
        check(last.onYawRate(-7, 1) == WindowReader.Event.END_OF_STORY, "flick past the last line -> end of story");

        // Reversed direction
        WindowReader rev = new WindowReader(TEXT, 0, 13, 500, true);
        long t2 = still(rev, 1, 100);
        double b2 = rev.offset();
        t2 = slide(rev, t2, 2.0, 400, ev);
        still(rev, t2, 300);
        check(rev.offset() > b2 + 5, "reversed: +x slide pans forward");

        System.out.println("ALL PASSED");
    }
}
