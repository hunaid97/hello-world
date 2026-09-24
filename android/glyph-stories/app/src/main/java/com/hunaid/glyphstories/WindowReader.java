package com.hunaid.glyphstories;

/**
 * WINDOW mode: the Glyph Matrix is a window onto a line of the book. Sliding the phone sideways
 * pans along the line; a quick wrist flick, or sliding past either end, changes line.
 *
 * Pure Java (no Android types) so the motion maths can be tested off-device.
 *
 * Sliding is estimated by integrating sideways linear acceleration. That drifts, so velocity is
 * reset to zero whenever the phone is still ("zero-velocity update") and leaks away over time;
 * the result is that text moves while you slide and stays put when you stop.
 */
public final class WindowReader {

    public enum Event { NONE, NEXT_LINE, PREVIOUS_LINE, END_OF_STORY }

    public static final int WORDS_PER_LINE = 10;

    /** How far past either end of a line (in LED columns) before it moves to the next/previous line. */
    static final double OVERSHOOT = 10;
    /** Accelerations below this (m/s^2) are treated as noise. */
    static final double NOISE = 0.12;
    /** Below this (m/s^2) the phone counts as still ... */
    static final double STILL = 0.30;
    /** ... and once still for this long, velocity snaps to zero. */
    static final long STILL_NS = 120_000_000L;
    /** Velocity leaks away with this time constant (s), limiting drift. */
    static final double LEAK_TAU = 0.8;
    /** A wrist flick: rotation about the phone's vertical axis faster than this (rad/s). */
    static final double FLICK_RAD_S = 5.0;
    static final long FLICK_COOLDOWN_NS = 600_000_000L;
    /** Ignore sliding for a moment after a flick (the twist itself jolts the accelerometer). */
    static final long FLICK_FREEZE_NS = 350_000_000L;

    private final String[] words;
    private final int lineCount;
    private final int matrixSize;

    private int line;
    private ScrollRenderer lineRenderer;
    private double offset;

    private double columnsPerMeter;
    private boolean reversed;

    private double velocity;
    private long lastAccelNs;
    private long stillSinceNs;
    private long freezeUntilNs;
    private long lastFlickNs = Long.MIN_VALUE / 2;

    public WindowReader(String text, int startWord, int matrixSize, double columnsPerMeter, boolean reversed) {
        this.words = ScrollRenderer.splitWords(text);
        this.lineCount = Math.max(1, (words.length + WORDS_PER_LINE - 1) / WORDS_PER_LINE);
        this.matrixSize = matrixSize;
        this.columnsPerMeter = columnsPerMeter;
        this.reversed = reversed;
        int startLine = Math.max(0, Math.min(lineCount - 1, startWord / WORDS_PER_LINE));
        setLine(startLine, false);
    }

    public void setColumnsPerMeter(double c) { columnsPerMeter = c; }

    public void setReversed(boolean r) { reversed = r; }

    public int line() { return line; }

    public int lineCount() { return lineCount; }

    public double offset() { return offset; }

    /** First word of the current line: where to resume next time. */
    public int firstWordOfLine() { return line * WORDS_PER_LINE; }

    String lineText(int i) {
        StringBuilder b = new StringBuilder();
        int end = Math.min(words.length, (i + 1) * WORDS_PER_LINE);
        for (int w = i * WORDS_PER_LINE; w < end; w++) {
            if (b.length() > 0) b.append(' ');
            b.append(words[w]);
        }
        return b.toString();
    }

    private int lineWidth() { return lineRenderer.columnCount(); }

    private void setLine(int i, boolean atEnd) {
        line = i;
        lineRenderer = new ScrollRenderer(lineText(i));
        // Start with the first letter one column in from the left edge, or the last letter one in from the right
        offset = atEnd ? lineWidth() - matrixSize + 1 : -1;
        velocity = 0;
    }

    private Event changeLine(int delta) {
        int next = line + delta;
        if (next >= lineCount) {
            offset = lineWidth() - matrixSize + 1;
            velocity = 0;
            return Event.END_OF_STORY;
        }
        if (next < 0) {
            offset = -1;
            velocity = 0;
            return Event.NONE;
        }
        setLine(next, delta < 0);
        return delta > 0 ? Event.NEXT_LINE : Event.PREVIOUS_LINE;
    }

    /**
     * Sideways linear acceleration (device x axis, gravity removed) in m/s^2.
     * Device +x points right when looking at the screen; the reader looks at the back (the matrix),
     * so their right is device -x. Moving to the reader's right moves the window right along the line.
     */
    public Event onSidewaysAcceleration(double ax, long timeNs) {
        if (lastAccelNs == 0) {
            lastAccelNs = timeNs;
            return Event.NONE;
        }
        double dt = Math.max(0, Math.min(0.05, (timeNs - lastAccelNs) / 1e9));
        lastAccelNs = timeNs;
        if (timeNs < freezeUntilNs) {
            velocity = 0;
            return Event.NONE;
        }

        // Zero-velocity update when the phone is still
        if (Math.abs(ax) < STILL) {
            if (stillSinceNs == 0) stillSinceNs = timeNs;
            else if (timeNs - stillSinceNs > STILL_NS) velocity = 0;
        } else {
            stillSinceNs = 0;
        }

        double a = Math.abs(ax) < NOISE ? 0 : ax;
        if (!reversed) a = -a; // reader's right = device -x
        velocity += a * dt;
        velocity *= Math.exp(-dt / LEAK_TAU);
        offset += velocity * dt * columnsPerMeter;

        int delta = offset > lineWidth() - matrixSize + OVERSHOOT ? +1 : offset < -OVERSHOOT ? -1 : 0;
        if (delta == 0) return Event.NONE;
        // Let the rest of this slide die out so the new line starts where it should
        freezeUntilNs = timeNs + FLICK_FREEZE_NS;
        return changeLine(delta);
    }

    /**
     * Rotation rate about the phone's vertical (y) axis in rad/s. Turning to the right (clockwise
     * seen from above) is negative, whichever way the screen faces. A fast turn is a flick.
     */
    public Event onYawRate(double wy, long timeNs) {
        if (Math.abs(wy) < FLICK_RAD_S) return Event.NONE;
        if (timeNs - lastFlickNs < FLICK_COOLDOWN_NS) return Event.NONE; // ignore the twist back
        lastFlickNs = timeNs;
        freezeUntilNs = timeNs + FLICK_FREEZE_NS;
        velocity = 0;
        boolean right = wy < 0;
        if (reversed) right = !right;
        return changeLine(right ? +1 : -1);
    }

    public int[] frame(int brightness) {
        return lineRenderer.frame(offset, matrixSize, brightness);
    }
}
