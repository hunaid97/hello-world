package com.hunaid.glyphstories;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphException;
import com.nothing.ketchum.GlyphMatrixManager;

/**
 * Foreground service that listens for a shake while the phone is locked and scrolls the
 * current story across the Glyph Matrix. Shake to start, shake to stop; stopping saves the
 * position so the next start resumes 10 words earlier.
 */
public class ReaderService extends Service implements SensorEventListener {

    public static final String ACTION_ARM = "com.hunaid.glyphstories.ARM";
    public static final String ACTION_DISARM = "com.hunaid.glyphstories.DISARM";
    public static final String ACTION_TOGGLE = "com.hunaid.glyphstories.TOGGLE";

    private static final String TAG = "GlyphStories";
    private static final String CHANNEL = "reader";
    private static final int NOTIFICATION_ID = 1;

    /** Shake detection: two strong jolts within this window. */
    private static final float SHAKE_G = 2.4f;
    private static final long SHAKE_WINDOW_MS = 700;
    private static final long SHAKE_MIN_GAP_MS = 120;
    private static final long SHAKE_COOLDOWN_MS = 1500;

    /** Lets the screen show what the matrix is showing. Called on the main thread. */
    public interface Listener {
        void onFrame(int[] frame, int size);
        void onState(boolean playing, String status);
    }

    private static Listener listener;
    private static volatile boolean playing;
    private static volatile String status = "READY";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onState(playing, status);
    }

    public static boolean isPlaying() { return playing; }

    private StoryStore store;
    private GlyphMatrixManager glyph;
    private volatile boolean glyphReady;
    private int matrixSize = 13;

    private HandlerThread thread;
    private Handler worker;
    private SensorManager sensors;
    private PowerManager.WakeLock playLock;
    private PowerManager.WakeLock listenLock;

    private ScrollRenderer renderer;
    private String storyId;
    private double offset;          // fractional strip column at the matrix's left edge
    private long lastTickMs;
    private int lastSavedWord = -1;

    /** Frame interval: ~50 updates a second for smooth sub-pixel scrolling. */
    private static final long FRAME_MS = 20;

    private long firstJolt;
    private long lastJolt;
    private long lastShake;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new StoryStore(this);
        try {
            startInForeground();
        } catch (RuntimeException e) {
            // e.g. restarted by the system while the app is in the background: Android won't allow it
            Log.w(TAG, "Can't run in the foreground right now: " + e.getMessage());
            stopSelf();
        }

        thread = new HandlerThread("glyph-reader");
        thread.start();
        worker = new Handler(thread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        playLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphStories:play");
        listenLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphStories:listen");

        initGlyph();
        startListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ARM : intent.getAction();
        if (ACTION_DISARM.equals(action)) {
            worker.post(stopTask);
            store.setArmed(false);
            stopSelf();
        } else if (ACTION_TOGGLE.equals(action)) {
            worker.post(toggleTask);
        } else {
            store.setArmed(true);
            setStatus(playing ? "READING" : "LOCK YOUR PHONE AND SHAKE");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sensors != null) sensors.unregisterListener(this);
        if (listenLock.isHeld()) listenLock.release();
        worker.post(new Runnable() {
            @Override public void run() {
                stopReading();
                if (glyph != null) {
                try { glyph.unInit(); } catch (RuntimeException ignored) {}
            }
            }
        });
        thread.quitSafely();
        setStatus("OFF");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- Glyph Matrix ----------

    private void initGlyph() {
        try {
            glyph = GlyphMatrixManager.getInstance(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "Glyph SDK unavailable: " + t);
            return;
        }
        glyph.init(new GlyphMatrixManager.Callback() {
            @Override
            public void onServiceConnected(ComponentName name) {
                String device = Common.is23112() ? Glyph.DEVICE_23112 : Glyph.DEVICE_25111p;
                boolean ok = glyph.register(device);
                Log.i(TAG, "register(" + device + ") = " + ok + ", model " + android.os.Build.MODEL);
                int len = Common.getDeviceMatrixLength();
                matrixSize = len > 0 ? len : 13;
                glyphReady = true;
                Log.i(TAG, "Glyph service connected, matrix " + matrixSize);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                glyphReady = false;
                Log.w(TAG, "Glyph service disconnected");
            }
        });
    }

    private void showFrame(int[] frame) {
        if (glyphReady) {
            try {
                glyph.setAppMatrixFrame(frame);
            } catch (GlyphException | RuntimeException e) {
                Log.w(TAG, "setAppMatrixFrame: " + e.getMessage());
            }
        }
        Listener l = listener;
        if (l != null) {
            int size = matrixSize;
            MAIN.post(new Runnable() {
                @Override public void run() { if (listener != null) listener.onFrame(frame, size); }
            });
        }
    }

    private void clearMatrix() {
        if (glyphReady) {
            try {
                glyph.closeAppMatrix();
                glyph.setGlyphMatrixTimeout(true);
            } catch (GlyphException | RuntimeException e) {
                Log.w(TAG, "closeAppMatrix: " + e.getMessage());
            }
        }
        showFrame(new int[matrixSize * matrixSize]);
    }

    // ---------- reading ----------

    private final Runnable toggleTask = new Runnable() {
        @Override public void run() { toggle(); }
    };
    private final Runnable stopTask = new Runnable() {
        @Override public void run() { stopReading(); }
    };

    /** Called on the worker thread. */
    private void toggle() {
        if (playing) stopReading(); else startReading();
    }

    private void startReading() {
        StoryStore.Story story = store.current();
        if (story == null) {
            setStatus("NO STORY: FETCH ONE IN THE APP");
            buzz(new long[]{0, 60, 80, 60, 80, 60});
            return;
        }
        storyId = story.id;
        renderer = new ScrollRenderer(story.text);
        int start = story.position >= renderer.wordCount() ? 0 : story.position;
        offset = renderer.offsetForWord(start, matrixSize);
        playing = true;
        lastTickMs = SystemClock.uptimeMillis();
        lastSavedWord = -1;
        if (!playLock.isHeld()) playLock.acquire(60 * 60 * 1000L);
        if (glyphReady) {
            try {
                glyph.setGlyphMatrixTimeout(false); // keep the matrix on while the story scrolls
            } catch (GlyphException | RuntimeException e) {
                Log.w(TAG, "setGlyphMatrixTimeout: " + e.getMessage());
            }
        } else {
            setStatus("GLYPH MATRIX NOT CONNECTED (SEE APP)");
        }
        setStatus("READING: " + story.title.toUpperCase());
        buzz(new long[]{0, 40});
        worker.post(tick);
    }

    private void stopReading() {
        if (!playing) return;
        playing = false;
        worker.removeCallbacks(tick);
        if (renderer != null && storyId != null) {
            int at = renderer.wordAt(offset, matrixSize);
            store.setPosition(storyId, ScrollRenderer.resumeWord(at));
        }
        clearMatrix();
        if (playLock.isHeld()) playLock.release();
        setStatus("PAUSED: SHAKE TO CONTINUE");
        buzz(new long[]{0, 40, 90, 40});
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!playing || renderer == null) return;
            if (renderer.isFinished(offset)) {
                playing = false;
                store.setPosition(storyId, 0);
                clearMatrix();
                if (playLock.isHeld()) playLock.release();
                setStatus("FINISHED: SHAKE TO READ AGAIN");
                buzz(new long[]{0, 120});
                return;
            }
            showFrame(renderer.frame(offset, matrixSize, ScrollRenderer.MAX_BRIGHTNESS));
            // Save progress when the word changes, in case the app is killed
            int word = renderer.wordAt(offset, matrixSize);
            if (word != lastSavedWord) {
                lastSavedWord = word;
                store.setPosition(storyId, word);
            }
            // Advance by real elapsed time so the pace stays even even if a frame is late
            long now = SystemClock.uptimeMillis();
            long dt = Math.min(100, now - lastTickMs);
            lastTickMs = now;
            offset += StoryStore.columnsPerSecond(store.speed()) * dt / 1000.0;
            worker.postAtTime(this, now + FRAME_MS);
        }
    };

    // ---------- shake ----------

    private void startListening() {
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        // A wake-up accelerometer keeps delivering while the screen is off without keeping the CPU awake.
        Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true);
        if (accel == null) {
            accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            // No wake-up sensor: hold a partial wake lock so shakes are noticed with the screen off.
            if (!listenLock.isHeld()) listenLock.acquire();
        }
        if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float g = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                / SensorManager.GRAVITY_EARTH;
        if (g < SHAKE_G) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastShake < SHAKE_COOLDOWN_MS) return;
        if (now - lastJolt < SHAKE_MIN_GAP_MS) return;
        if (now - firstJolt > SHAKE_WINDOW_MS) {
            firstJolt = now;
            lastJolt = now;
            return;
        }
        lastJolt = now;
        // Second jolt inside the window: that's a shake
        lastShake = now;
        firstJolt = 0;
        if (store.onlyWhenLocked() && !isLocked()) return;
        worker.post(toggleTask);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean isLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    // ---------- plumbing ----------

    private void buzz(long[] pattern) {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private void setStatus(String s) {
        status = s;
        boolean p = playing;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onState(p, s);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(s));
            }
        });
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Shake reader", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps listening for a shake while the phone is locked");
        nm.createNotificationChannel(ch);
        startForeground(NOTIFICATION_ID, buildNotification("LOCK YOUR PHONE AND SHAKE"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Glyph Stories")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
