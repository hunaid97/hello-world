package com.hunaid.glyphstories;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Fetches a random story. Tries, in order:
 *  1. shortstories-api (free, no key): short stories with a title and author
 *  2. A classic public-domain short story from Project Gutenberg, found via Gutendex
 *  3. A fable bundled in the app, so there is always something to read offline
 * Blocking: call off the main thread.
 */
public final class StoryFetcher {

    private static final int MAX_WORDS = 2500;
    private static final Random RANDOM = new Random();

    private static final String[] CLASSICS = {
            "The Gift of the Magi", "The Last Leaf", "The Ransom of Red Chief",
            "The Tell-Tale Heart", "The Cask of Amontillado", "The Masque of the Red Death",
            "The Yellow Wallpaper", "The Story of an Hour", "The Monkey's Paw",
            "The Open Window", "An Occurrence at Owl Creek Bridge", "The Necklace",
            "Rip Van Winkle", "The Legend of Sleepy Hollow", "The Happy Prince",
            "The Nightingale and the Rose", "The Selfish Giant", "Bartleby, the Scrivener",
    };

    public interface Log { void line(String s); }

    public static StoryStore.Story fetch(Log log) {
        try {
            StoryStore.Story s = fromShortStoriesApi();
            if (s != null) return s;
        } catch (Exception e) {
            log.line("Short stories API failed: " + e.getMessage());
        }
        try {
            StoryStore.Story s = fromGutenberg();
            if (s != null) return s;
        } catch (Exception e) {
            log.line("Gutenberg failed: " + e.getMessage());
        }
        log.line("Offline: using a bundled story");
        return BundledStories.random(RANDOM);
    }

    private static StoryStore.Story fromShortStoriesApi() throws Exception {
        String body = get("https://shortstories-api.onrender.com/");
        JSONObject o = body.trim().startsWith("[")
                ? new JSONArray(body).getJSONObject(RANDOM.nextInt(new JSONArray(body).length()))
                : new JSONObject(body);
        String text = o.optString("story", "").trim();
        if (text.isEmpty()) return null;
        String moral = o.optString("moral", "").trim();
        if (!moral.isEmpty()) text += " Moral: " + moral;
        return make(o.optString("title", "Untitled"), o.optString("author", "Unknown"), "shortstories-api", text);
    }

    private static StoryStore.Story fromGutenberg() throws Exception {
        String title = CLASSICS[RANDOM.nextInt(CLASSICS.length)];
        String search = get("https://gutendex.com/books/?languages=en&search="
                + URLEncoder.encode(title, "UTF-8"));
        JSONArray results = new JSONObject(search).getJSONArray("results");
        if (results.length() == 0) return null;
        JSONObject book = results.getJSONObject(0);
        JSONObject formats = book.getJSONObject("formats");
        String url = null;
        for (java.util.Iterator<String> it = formats.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.startsWith("text/plain")) { url = formats.getString(k); break; }
        }
        if (url == null) return null;
        String raw = get(url.replace("http://", "https://"));
        String text = extractStory(raw, title);
        if (text.isEmpty()) return null;
        String author = "Unknown";
        JSONArray authors = book.optJSONArray("authors");
        if (authors != null && authors.length() > 0) author = authors.getJSONObject(0).optString("name", author);
        return make(title, author, "Project Gutenberg", text);
    }

    /** Strips the Gutenberg header/footer and starts at the story's title if the book is a collection. */
    static String extractStory(String raw, String title) {
        String body = raw;
        int start = body.indexOf("*** START");
        if (start >= 0) body = body.substring(body.indexOf('\n', start) + 1);
        int end = body.indexOf("*** END");
        if (end >= 0) body = body.substring(0, end);
        String upper = body.toUpperCase(Locale.ROOT);
        String key = title.toUpperCase(Locale.ROOT);
        int at = upper.indexOf(key, Math.min(upper.length(), 200)); // skip the title page / contents
        if (at < 0) at = upper.indexOf(key);
        if (at >= 0) body = body.substring(at + key.length());
        return clampWords(body.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim());
    }

    static String clampWords(String text) {
        String[] w = text.split("\\s+");
        if (w.length <= MAX_WORDS) return text;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < MAX_WORDS; i++) b.append(w[i]).append(' ');
        return b.append("...").toString();
    }

    static StoryStore.Story make(String title, String author, String source, String text) {
        StoryStore.Story s = new StoryStore.Story();
        s.id = UUID.randomUUID().toString();
        s.title = title;
        s.author = author;
        s.source = source;
        s.text = clampWords(text);
        s.position = 0;
        s.wordCount = ScrollRenderer.splitWords(s.text).length;
        return s;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "GlyphStories/1.0 (Android)");
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new IOException("HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
