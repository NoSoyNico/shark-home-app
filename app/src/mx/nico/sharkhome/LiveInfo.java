package mx.nico.sharkhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * Datos en vivo que una app normal SÍ puede leer: GPS (velocidad/rumbo), clima (Open-Meteo, sin llave)
 * y lo que está sonando (MediaSession). Nada de esto habla con el vehículo.
 */
public class LiveInfo implements LocationListener {
    static final String TAG = "SharkLive";
    static final long WEATHER_EVERY_MS = 15 * 60 * 1000;

    interface Ui {
        void onGps(String speed, String heading, String status);
        void onWeather(String temp, String desc);
        /** código WMO del clima y si es de día, para los efectos visuales (lluvia/sol/nieve…). */
        void onWeatherFx(int wmoCode, boolean day);
        /** pronóstico por horas para el slide del reloj. */
        void onHourly(java.util.List<HourFc> hours);
        void onMedia(String title, String subtitle, boolean playing, String pkg);
        void onLocation(double lat, double lon);
        /** rumbo real en grados (0-360, norte=0) y velocidad km/h. bearing<0 = sin rumbo fiable. */
        void onHeading(float bearingDeg, int kmh);
        void onLyrics(java.util.List<Lyrics.Line> lines, boolean synced, boolean playing);
    }

    final Context ctx;
    final Ui ui;
    final Lyrics lyrics = new Lyrics();
    final Handler main = new Handler(Looper.getMainLooper());
    LocationManager lm;
    MediaSessionManager msm;
    MediaController current;
    Location last;
    long lastWeatherAt = 0;
    boolean running;

    LiveInfo(Context c, Ui u) { ctx = c; ui = u; }

    void start() {
        running = true;
        startGps();
        startMedia();
        main.post(weatherTick);
    }

    void stop() {
        running = false;
        main.removeCallbacks(weatherTick);
        try { if (lm != null) lm.removeUpdates(this); } catch (Throwable ignored) {}
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Throwable ignored) {}
        if (current != null) current.unregisterCallback(mediaCallback);
        current = null;
        mediaBound = false; // CLAVE: al volver (p.ej. de BYD Maps) hay que RE-enganchar el listener de música
    }

    // ---------- GPS ----------
    boolean hasLocationPermission() {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void startGps() {
        if (!hasLocationPermission()) { ui.onGps("—", "—", "Sin permiso"); return; }
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) { last = l; ui.onLocation(l.getLatitude(), l.getLongitude()); }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            ui.onGps("0", "—", "Buscando");
        } catch (SecurityException e) {
            ui.onGps("—", "—", "Sin permiso");
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        last = l;
        int kmh = l.hasSpeed() ? Math.round(l.getSpeed() * 3.6f) : 0;
        if (kmh < 2) kmh = 0; // ruido del GPS estando parado
        String heading = l.hasBearing() && kmh > 0 ? cardinal(l.getBearing()) : "—";
        ui.onGps(String.valueOf(kmh), heading, "GPS OK");
        ui.onLocation(l.getLatitude(), l.getLongitude());
        ui.onHeading(l.hasBearing() ? l.getBearing() : -1f, kmh);
        if (System.currentTimeMillis() - lastWeatherAt > WEATHER_EVERY_MS) fetchWeather();
    }

    static String cardinal(float deg) {
        String[] c = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        return c[Math.round(((deg % 360) + 360) % 360 / 45f) % 8];
    }

    public void onStatusChanged(String p, int s, Bundle b) {}
    public void onProviderEnabled(String p) {}
    public void onProviderDisabled(String p) { ui.onGps("—", "—", "GPS apagado"); }

    // ---------- Clima ----------
    final Runnable weatherTick = new Runnable() {
        public void run() {
            if (!running) return;
            fetchWeather();
            main.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

    void fetchWeather() {
        final Location l = last;
        if (l == null) return;
        lastWeatherAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                    + "&current=temperature_2m,weather_code,is_day"
                                    + "&hourly=temperature_2m,weather_code&forecast_days=2&timezone=auto",
                            l.getLatitude(), l.getLongitude()));
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    JSONObject root = new JSONObject(sb.toString());
                    JSONObject cur = root.getJSONObject("current");
                    final String temp = Math.round(cur.getDouble("temperature_2m")) + "°";
                    final int code = cur.getInt("weather_code");
                    final boolean day = cur.optInt("is_day", 1) == 1;
                    final String desc = weatherText(code);
                    final java.util.List<HourFc> hours = parseHourly(root, cur.optString("time", ""));
                    main.post(new Runnable() { public void run() {
                        ui.onWeather(temp, desc);
                        ui.onWeatherFx(code, day);
                        ui.onHourly(hours);
                    } });
                } catch (Throwable t) {
                    Log.w(TAG, "clima: " + t);
                    lastWeatherAt = 0; // reintenta con la siguiente lectura de GPS
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    /** Una hora del pronóstico. */
    public static class HourFc {
        public String label; public int temp; public int code; public boolean day;
    }

    /** Toma las próximas ~8 horas del pronóstico horario, empezando por la hora actual. */
    java.util.List<HourFc> parseHourly(JSONObject root, String curTime) {
        java.util.List<HourFc> out = new java.util.ArrayList<HourFc>();
        try {
            JSONObject hourly = root.getJSONObject("hourly");
            org.json.JSONArray times = hourly.getJSONArray("time");
            org.json.JSONArray temps = hourly.getJSONArray("temperature_2m");
            org.json.JSONArray codes = hourly.getJSONArray("weather_code");
            String curH = curTime.length() >= 13 ? curTime.substring(0, 13) : "";
            int start = 0;
            for (int i = 0; i < times.length(); i++) {
                String t = times.getString(i);
                if (t.length() >= 13 && t.substring(0, 13).compareTo(curH) >= 0) { start = i; break; }
            }
            for (int i = start; i < times.length() && out.size() < 8; i++) {
                HourFc h = new HourFc();
                String t = times.getString(i);
                int hh = Integer.parseInt(t.substring(11, 13));
                h.label = out.isEmpty() ? "Ahora" : hourLabel(hh);
                h.temp = (int) Math.round(temps.getDouble(i));
                h.code = codes.getInt(i);
                h.day = hh >= 7 && hh < 19;
                out.add(h);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static String hourLabel(int h) {
        int h12 = h % 12; if (h12 == 0) h12 = 12;
        return h12 + (h < 12 ? "am" : "pm");
    }

    static String weatherText(int code) {
        if (code == 0) return "Despejado";
        if (code <= 2) return "Algo nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 51 && code <= 57) return "Llovizna";
        if (code >= 61 && code <= 67) return "Lluvia";
        if (code >= 71 && code <= 77) return "Nieve";
        if (code >= 80 && code <= 82) return "Chubascos";
        if (code >= 95) return "Tormenta";
        return "—";
    }

    // ---------- Música ----------
    final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> list) { pick(list); }
            };

    final MediaController.Callback mediaCallback = new MediaController.Callback() {
        public void onPlaybackStateChanged(PlaybackState s) { pushMedia(); }
        public void onMetadataChanged(MediaMetadata m) { pushMedia(); }
        public void onSessionDestroyed() { refreshSessions(); }
    };

    ComponentName listener() { return new ComponentName(ctx, MediaListener.class); }

    boolean mediaBound = false;
    int mediaTries = 0;

    void startMedia() { startMedia(0); }

    /**
     * Engancha el "qué está sonando". El servicio de acceso a notificaciones puede tardar unos
     * segundos en ATAR después de arrancar (o justo después de concederlo por ADB), así que si
     * falla reintentamos unas veces. attempts = cuántos reintentos llevamos.
     */
    void startMedia(final int attempts) {
        if (mediaBound) return;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener(), main);
            mediaBound = true;
            refreshSessions();
        } catch (SecurityException e) {
            // Aún no está el permiso/enlace. Mostrar aviso y reintentar un rato (hasta ~30 s).
            ui.onMedia("Música", "Falta permitir acceso a notificaciones", false, null);
            if (attempts < 15) {
                main.postDelayed(new Runnable() { public void run() { startMedia(attempts + 1); } }, 2000);
            }
        }
    }

    /** Reintentar enganchar la música ya (p.ej. justo después de conceder el acceso por ADB). */
    void retryMedia() {
        mediaBound = false;
        startMedia(0);
    }

    void refreshSessions() {
        try { pick(msm.getActiveSessions(listener())); } catch (Throwable t) { pick(null); }
    }

    void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best == null) {
                for (MediaController c : list) {
                    PlaybackState s = c.getPlaybackState();
                    if (s != null && s.getState() != PlaybackState.STATE_ERROR && s.getState() != PlaybackState.STATE_NONE) { best = c; break; }
                }
            }
        }
        if (current != null) current.unregisterCallback(mediaCallback);
        current = best;
        if (current != null) current.registerCallback(mediaCallback, main);
        pushMedia();
    }

    void pushMedia() {
        MediaController c = current;
        if (c == null) {
            ui.onMedia("Nada sonando", "Toca Música o Radio abajo", false, null);
            lyrics.fetch(null, null, lyricsResult(false));
            return;
        }
        MediaMetadata m = c.getMetadata();
        String title = null, artist = null;
        if (m != null) {
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.trim().isEmpty())
                artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (title == null && m.getDescription() != null && m.getDescription().getTitle() != null)
                title = m.getDescription().getTitle().toString();
        }
        String rawTitle = (title == null) ? "" : title.trim();
        String rawArtist = (artist == null) ? "" : artist.trim();
        String shownTitle = rawTitle.isEmpty() ? appName(c.getPackageName()) : rawTitle;
        String shownSub = rawArtist.isEmpty() ? appName(c.getPackageName()) : rawArtist;
        PlaybackState s = c.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        ui.onMedia(shownTitle, shownSub, playing, c.getPackageName());
        // Solo buscamos letra si hay un título de canción de verdad (no el nombre de la app / radio).
        if (rawTitle.isEmpty()) lyrics.fetch(null, null, lyricsResult(playing));
        else lyrics.fetch(rawTitle, rawArtist, lyricsResult(playing));
    }

    Lyrics.Result lyricsResult(final boolean playing) {
        return new Lyrics.Result() {
            public void onLyrics(String key, java.util.List<Lyrics.Line> lines, boolean synced) {
                ui.onLyrics(lines, synced, playing);
            }
        };
    }

    /** Posición actual de reproducción en ms (estimada), o -1 si no hay. */
    long positionMs() {
        MediaController c = current;
        if (c == null) return -1;
        PlaybackState s = c.getPlaybackState();
        if (s == null) return -1;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            long dt = android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            pos += (long) (dt * s.getPlaybackSpeed());
        }
        return pos;
    }

    boolean isPlaying() {
        MediaController c = current;
        if (c == null) return false;
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    String appName(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) { return pkg; }
    }

    void prev() { if (current != null) current.getTransportControls().skipToPrevious(); }
    void next() { if (current != null) current.getTransportControls().skipToNext(); }
    void playPause() {
        if (current == null) return;
        PlaybackState s = current.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }
}
