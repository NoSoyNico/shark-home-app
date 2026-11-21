package mx.nico.sharkhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Letras sincronizadas desde lrclib.net (gratis, sin llave). SOLO LECTURA de internet.
 * Usa el título/artista que ya saca el MediaSession. No copia nada de DuDu.
 *
 * Formato LRC: líneas tipo "[01:23.45] texto". Las parseamos a (ms, texto) y ordenamos por tiempo.
 */
public class Lyrics {

    static final String TAG = "SharkLyrics";

    public static class Line {
        public final long ms;
        public final String text;
        Line(long ms, String text) { this.ms = ms; this.text = text; }
    }

    public interface Result {
        /** lines = letra sincronizada (vacía si no hay); synced = true si vino con tiempos. */
        void onLyrics(String key, List<Line> lines, boolean synced);
    }

    final Handler main = new Handler(Looper.getMainLooper());
    String wantKey;   // última canción pedida; descarta respuestas viejas

    static String key(String title, String artist) {
        return (title == null ? "" : title.trim()) + "\u0001" + (artist == null ? "" : artist.trim());
    }

    /** Pide la letra de esta canción. Si ya es la misma que la última, no hace nada. */
    void fetch(final String title, final String artist, final Result cb) {
        final String k = key(title, artist);
        if (k.equals(wantKey)) return;
        wantKey = k;
        if (title == null || title.trim().isEmpty()) {
            deliver(k, new ArrayList<Line>(), false, cb);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                List<Line> lines = new ArrayList<Line>();
                boolean[] synced = {false};
                try {
                    String lrc = lookup(title, artist, synced);
                    if (lrc != null) lines = parse(lrc);
                } catch (Throwable t) {
                    Log.w(TAG, "fetch: " + t);
                }
                deliver(k, lines, synced[0], cb);
            }
        }).start();
    }

    void deliver(final String k, final List<Line> lines, final boolean synced, final Result cb) {
        main.post(new Runnable() {
            public void run() {
                if (!k.equals(wantKey)) return; // ya cambió de canción
                cb.onLyrics(k, lines, synced);
            }
        });
    }

    /** Devuelve el texto LRC (sincronizado si hay; si no, letra plana). null si no encontró. */
    String lookup(String title, String artist, boolean[] syncedOut) throws Exception {
        // 1) /api/get exacto por artista+título
        String got = httpGetField(
                "https://lrclib.net/api/get?track_name=" + enc(title)
                        + "&artist_name=" + enc(artist == null ? "" : artist),
                syncedOut);
        if (got != null) return got;
        // 2) /api/search y nos quedamos con el que tenga letra sincronizada más corta de título
        String url = "https://lrclib.net/api/search?track_name=" + enc(title)
                + (artist == null || artist.isEmpty() ? "" : "&artist_name=" + enc(artist));
        String body = http(url);
        if (body == null) return null;
        JSONArray arr = new JSONArray(body);
        List<JSONObject> cands = new ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) cands.add(arr.getJSONObject(i));
        Collections.sort(cands, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                boolean sa = str(a, "syncedLyrics").length() > 0;
                boolean sb = str(b, "syncedLyrics").length() > 0;
                if (sa != sb) return sa ? -1 : 1; // primero los sincronizados
                return 0;
            }
        });
        for (JSONObject o : cands) {
            String s = str(o, "syncedLyrics");
            if (!s.isEmpty()) { syncedOut[0] = true; return s; }
        }
        for (JSONObject o : cands) {
            String s = str(o, "plainLyrics");
            if (!s.isEmpty()) { syncedOut[0] = false; return s; }
        }
        return null;
    }

    String httpGetField(String url, boolean[] syncedOut) throws Exception {
        String body = http(url);
        if (body == null) return null;
        JSONObject o = new JSONObject(body);
        String s = str(o, "syncedLyrics");
        if (!s.isEmpty()) { syncedOut[0] = true; return s; }
        String p = str(o, "plainLyrics");
        if (!p.isEmpty()) { syncedOut[0] = false; return p; }
        return null;
    }

    /** optString de Android devuelve "null" (texto) cuando el campo es JSON null; esto no. */
    static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    static String http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "SharkHome/0.3 (personal use)");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "http: " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Parsea texto LRC a líneas con tiempo. Si no hay tiempos, cada línea queda con ms = -1. */
    static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<Line>();
        String[] rows = lrc.split("\n");
        for (String row : rows) {
            int tags = 0;
            List<Long> stamps = new ArrayList<Long>();
            int i = 0;
            // puede haber varios [mm:ss.xx] al inicio de la misma línea
            while (i < row.length() && row.charAt(i) == '[') {
                int close = row.indexOf(']', i);
                if (close < 0) break;
                String tag = row.substring(i + 1, close);
                long ms = stampMs(tag);
                if (ms >= 0) { stamps.add(ms); tags++; }
                i = close + 1;
            }
            String text = row.substring(i).trim();
            if (stamps.isEmpty()) {
                if (!text.isEmpty()) out.add(new Line(-1, text)); // letra plana
            } else {
                for (Long ms : stamps) out.add(new Line(ms, text)); // puede ir vacío (pausa)
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            public int compare(Line a, Line b) { return Long.compare(a.ms, b.ms); }
        });
        return out;
    }

    /** "mm:ss.xx" o "mm:ss" -> ms; -1 si no es un tiempo. */
    static long stampMs(String tag) {
        int colon = tag.indexOf(':');
        if (colon < 0) return -1;
        try {
            int min = Integer.parseInt(tag.substring(0, colon).trim());
            String rest = tag.substring(colon + 1).trim();
            double sec = Double.parseDouble(rest);
            return (long) (min * 60000L + sec * 1000.0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Índice de la línea activa para una posición dada (ms). -1 si aún no empieza. */
    static int indexAt(List<Line> lines, long posMs) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).ms <= posMs) idx = i; else break;
        }
        return idx;
    }

    static String fmt(long ms) { // por si se necesita depurar
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
