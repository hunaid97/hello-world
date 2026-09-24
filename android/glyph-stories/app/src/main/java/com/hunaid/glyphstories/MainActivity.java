package com.hunaid.glyphstories;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements ReaderService.Listener {

    private StoryStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MatrixPreviewView preview;
    private TextView status, current, speedValue;
    private Button arm, play, locked, fetch;
    private LinearLayout library;
    private View reader;
    private String expandedId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        store = new StoryStore(this);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        current = findViewById(R.id.current);
        speedValue = findViewById(R.id.speed_value);
        arm = findViewById(R.id.arm);
        play = findViewById(R.id.play);
        locked = findViewById(R.id.locked);
        fetch = findViewById(R.id.fetch);
        library = findViewById(R.id.library);
        reader = findViewById(R.id.reader);

        arm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !store.armed();
                store.setArmed(on);
                Intent i = new Intent(MainActivity.this, ReaderService.class)
                        .setAction(on ? ReaderService.ACTION_ARM : ReaderService.ACTION_DISARM);
                if (on) startForegroundService(i); else startService(i);
                refresh();
            }
        });

        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startForegroundService(new Intent(MainActivity.this, ReaderService.class)
                        .setAction(ReaderService.ACTION_TOGGLE));
            }
        });

        locked.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.setOnlyWhenLocked(!store.onlyWhenLocked());
                refresh();
            }
        });

        SeekBar speed = findViewById(R.id.speed);
        speed.setProgress(store.speed());
        speedValue.setText(String.valueOf(store.speed()));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = Math.max(1, p);
                store.setSpeed(v);
                speedValue.setText(String.valueOf(v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        fetch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchStory(); }
        });
        findViewById(R.id.reader_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reader.setVisibility(View.GONE); }
        });

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (store.armed()) {
            startForegroundService(new Intent(this, ReaderService.class).setAction(ReaderService.ACTION_ARM));
        }
        if (store.all().isEmpty()) fetchStory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReaderService.setListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        ReaderService.setListener(null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (reader.getVisibility() == View.VISIBLE) reader.setVisibility(View.GONE);
        else super.onBackPressed();
    }

    // ---------- ReaderService.Listener ----------

    @Override
    public void onFrame(int[] frame, int size) {
        preview.setFrame(frame, size);
    }

    @Override
    public void onState(boolean playing, String s) {
        status.setText(s);
        play.setText(playing ? "STOP ON GLYPH" : "PLAY ON GLYPH NOW");
        if (!playing) refresh();
    }

    // ---------- stories ----------

    private void fetchStory() {
        fetch.setEnabled(false);
        fetch.setText("FETCHING…");
        io.execute(new Runnable() {
            @Override public void run() {
                final StoryStore.Story s = StoryFetcher.fetch(new StoryFetcher.Log() {
                    @Override public void line(final String line) {
                        main.post(new Runnable() {
                            @Override public void run() { status.setText(line.toUpperCase()); }
                        });
                    }
                });
                store.add(s);
                main.post(new Runnable() {
                    @Override public void run() {
                        fetch.setEnabled(true);
                        fetch.setText("FETCH A RANDOM STORY");
                        status.setText("GOT: " + s.title.toUpperCase());
                        refresh();
                    }
                });
            }
        });
    }

    private void refresh() {
        arm.setText(store.armed() ? "SHAKE READER: ON" : "SHAKE READER: OFF");
        setInverted(arm, store.armed());
        locked.setText(store.onlyWhenLocked() ? "ONLY WHEN LOCKED: ON" : "ONLY WHEN LOCKED: OFF");

        StoryStore.Story cur = store.current();
        current.setText(cur == null ? "NOTHING YET"
                : cur.title.toUpperCase() + "\n" + cur.author.toUpperCase() + " · " + cur.percent() + "% READ");

        library.removeAllViews();
        List<StoryStore.Story> all = store.all();
        String curId = store.currentId();
        for (StoryStore.Story s : all) library.addView(storyRow(s, s.id.equals(curId)));
    }

    private View storyRow(final StoryStore.Story s, boolean isCurrent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(isCurrent ? R.drawable.row_selected : R.drawable.row);
        int pad = dp(12);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setTextAppearance(R.style.Body);
        t.setTextColor(isCurrent ? Color.BLACK : Color.WHITE);
        t.setText(s.title.toUpperCase() + "\n" + s.author.toUpperCase() + " · " + s.wordCount + " WORDS · " + s.percent() + "%");
        box.addView(t);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.setCurrent(s.id);
                expandedId = s.id.equals(expandedId) ? null : s.id;
                refresh();
            }
        });

        if (s.id.equals(expandedId)) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(10), 0, 0);
            actions.addView(smallButton("READ", isCurrent, new View.OnClickListener() {
                @Override public void onClick(View v) { openReader(s); }
            }));
            actions.addView(smallButton("FROM START", isCurrent, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.setPosition(s.id, 0);
                    refresh();
                }
            }));
            actions.addView(smallButton("DELETE", isCurrent, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    store.delete(s.id);
                    expandedId = null;
                    refresh();
                }
            }));
            box.addView(actions);
        }
        return box;
    }

    private Button smallButton(String label, boolean onWhite, View.OnClickListener l) {
        Button b = new Button(this, null, 0, R.style.SharpButton);
        b.setText(label);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (onWhite) setInverted(b, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private void setInverted(Button b, boolean inverted) {
        b.setBackgroundResource(inverted ? R.drawable.button_inverted : R.drawable.button);
        b.setTextColor(inverted ? Color.BLACK : Color.WHITE);
    }

    private void openReader(StoryStore.Story s) {
        ((TextView) findViewById(R.id.reader_title)).setText(s.title.toUpperCase() + "\n" + s.author.toUpperCase());
        ((TextView) findViewById(R.id.reader_text)).setText(s.text);
        reader.setVisibility(View.VISIBLE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
