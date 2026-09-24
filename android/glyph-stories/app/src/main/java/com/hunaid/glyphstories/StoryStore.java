package com.hunaid.glyphstories;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Saved stories, the current story, reading positions and settings (SharedPreferences). */
public final class StoryStore {

    public static final class Story {
        public String id;
        public String title;
        public String author;
        public String source;
        public String text;
        public int position;   // word index to resume from
        public int wordCount;

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id).put("title", title).put("author", author)
                    .put("source", source).put("text", text)
                    .put("position", position).put("wordCount", wordCount);
        }

        static Story fromJson(JSONObject o) {
            Story s = new Story();
            s.id = o.optString("id");
            s.title = o.optString("title");
            s.author = o.optString("author");
            s.source = o.optString("source");
            s.text = o.optString("text");
            s.position = o.optInt("position");
            s.wordCount = o.optInt("wordCount");
            return s;
        }

        public int percent() {
            return wordCount <= 0 ? 0 : Math.min(100, Math.round(100f * position / wordCount));
        }
    }

    private static final String PREFS = "glyph_stories";
    private final SharedPreferences prefs;

    public StoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Story> all() {
        List<Story> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("stories", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Story.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {}
        return out;
    }

    private synchronized void saveAll(List<Story> stories) {
        JSONArray a = new JSONArray();
        try {
            for (Story s : stories) a.put(s.toJson());
        } catch (JSONException ignored) {}
        prefs.edit().putString("stories", a.toString()).apply();
    }

    public synchronized void add(Story story) {
        List<Story> list = all();
        list.add(0, story);
        saveAll(list);
        if (currentId() == null) setCurrent(story.id);
    }

    public synchronized void delete(String id) {
        List<Story> list = all();
        for (java.util.Iterator<Story> it = list.iterator(); it.hasNext(); ) {
            if (it.next().id.equals(id)) it.remove();
        }
        saveAll(list);
        if (id.equals(currentId())) setCurrent(list.isEmpty() ? null : list.get(0).id);
    }

    public synchronized Story get(String id) {
        if (id == null) return null;
        for (Story s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    public synchronized void setPosition(String id, int position) {
        List<Story> list = all();
        for (Story s : list) if (s.id.equals(id)) s.position = position;
        saveAll(list);
    }

    public String currentId() { return prefs.getString("current", null); }

    public void setCurrent(String id) { prefs.edit().putString("current", id).apply(); }

    public Story current() { return get(currentId()); }

    /** Reading speed, 1 (slowest) to 20 (fastest). */
    public int speed() { return prefs.getInt("speed", 8); }

    public void setSpeed(int speed) { prefs.edit().putInt("speed", speed).apply(); }

    /** Columns scrolled per second for a speed setting. */
    public static float columnsPerSecond(int speed) { return 3f + speed * 2.5f; }

    public boolean onlyWhenLocked() { return prefs.getBoolean("onlyLocked", true); }

    public void setOnlyWhenLocked(boolean v) { prefs.edit().putBoolean("onlyLocked", v).apply(); }

    public boolean armed() { return prefs.getBoolean("armed", false); }

    public void setArmed(boolean v) { prefs.edit().putBoolean("armed", v).apply(); }
}
