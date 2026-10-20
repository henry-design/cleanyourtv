package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Current weather for one chosen place, from Open-Meteo (free, no account or key). The place is picked in the
 * Theme app (Ruaka, Kenya until then). Readings are cached in the launcher's settings and refreshed at most every
 * 30 minutes, so Home and the screensaver show the last reading at once and never wait on the network.
 */
final class Weather {

    private static final String TAG = "TvHome.Weather";
    private static final long MAX_AGE_MS = 30 * 60 * 1000L;

    static final String NAME = "wx_name", LAT = "wx_lat", LON = "wx_lon", FAHRENHEIT = "wx_fahrenheit";
    static final String ON_HOME = "wx_home", ON_SAVER = "wx_saver";
    private static final String CACHE = "wx_cache", CACHE_KEY = "wx_cache_key";

    static final String DEFAULT_NAME = "Ruaka";
    static final float DEFAULT_LAT = -1.2f, DEFAULT_LON = 36.78333f;

    static final class Now {
        final double temp, high, low;
        final int code;
        final boolean day;
        final long at;

        Now(double temp, double high, double low, int code, boolean day, long at) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
            this.at = at;
        }
    }

    static final class Place {
        final String name, region;
        final double lat, lon;

        Place(String name, String region, double lat, double lon) {
            this.name = name;
            this.region = region;
            this.lat = lat;
            this.lon = lon;
        }

        @Override public String toString() { return region.isEmpty() ? name : name + ", " + region; }
    }

    private Weather() { }

    // ---- settings --------------------------------------------------------------------------------

    static String placeName(Context ctx) { return ThemePrefs.get(ctx).getString(NAME, DEFAULT_NAME); }
    static boolean fahrenheit(Context ctx) { return ThemePrefs.get(ctx).getBoolean(FAHRENHEIT, false); }
    static boolean onHome(Context ctx) { return ThemePrefs.get(ctx).getBoolean(ON_HOME, true); }
    static boolean onSaver(Context ctx) { return ThemePrefs.get(ctx).getBoolean(ON_SAVER, true); }

    static void setPlace(Context ctx, Place p) {
        ThemePrefs.edit(ctx).putString(NAME, p.name).putFloat(LAT, (float) p.lat).putFloat(LON, (float) p.lon)
                .remove(CACHE).apply();
    }

    private static String key(Context ctx) {
        SharedPreferences p = ThemePrefs.get(ctx);
        return p.getFloat(LAT, DEFAULT_LAT) + "," + p.getFloat(LON, DEFAULT_LON) + "," + fahrenheit(ctx);
    }

    // ---- readings --------------------------------------------------------------------------------

    /** The last reading for the current place and unit, however old; null when there is none. */
    static Now cached(Context ctx) {
        SharedPreferences p = ThemePrefs.get(ctx);
        String json = p.getString(CACHE, null);
        if (json == null || !key(ctx).equals(p.getString(CACHE_KEY, ""))) return null;
        try {
            JSONObject o = new JSONObject(json);
            return new Now(o.getDouble("t"), o.getDouble("h"), o.getDouble("l"), o.getInt("c"), o.getBoolean("d"),
                    o.getLong("at"));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Calls {@code done} on the main thread with a fresh reading when the cached one is missing or older than
     * 30 minutes (or {@code force}), fetched in the background; nothing happens when the cache is fresh or offline.
     */
    static void refresh(Context ctx, boolean force, Consumer<Now> done) {
        Now c = cached(ctx);
        if (!force && c != null && System.currentTimeMillis() - c.at < MAX_AGE_MS) return;
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Shelf.EXEC.execute(() -> {
            Now n = fetch(app);
            if (n != null) main.post(() -> done.accept(n));
        });
    }

    private static Now fetch(Context ctx) {
        SharedPreferences p = ThemePrefs.get(ctx);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + p.getFloat(LAT, DEFAULT_LAT)
                + "&longitude=" + p.getFloat(LON, DEFAULT_LON)
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=1" + (fahrenheit(ctx) ? "&temperature_unit=fahrenheit" : "");
        try {
            JSONObject o = new JSONObject(get(url));
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            Now n = new Now(cur.getDouble("temperature_2m"), daily.getJSONArray("temperature_2m_max").getDouble(0),
                    daily.getJSONArray("temperature_2m_min").getDouble(0), cur.getInt("weather_code"),
                    cur.optInt("is_day", 1) == 1, System.currentTimeMillis());
            JSONObject cache = new JSONObject().put("t", n.temp).put("h", n.high).put("l", n.low).put("c", n.code)
                    .put("d", n.day).put("at", n.at);
            p.edit().putString(CACHE, cache.toString()).putString(CACHE_KEY, key(ctx)).apply();
            return n;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "no weather: " + e);
            return null;
        }
    }

    /** Places matching {@code query}, best first (Open-Meteo geocoding). Off the main thread. */
    static List<Place> search(String query) throws IOException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                + Uri.encode(query.trim());
        List<Place> out = new ArrayList<>();
        try {
            JSONArray results = new JSONObject(get(url)).optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String region = join(r.optString("admin1", ""), r.optString("country", ""));
                out.add(new Place(r.getString("name"), region, r.getDouble("latitude"), r.getDouble("longitude")));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return out;
    }

    private static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + ", " + b;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    // ---- presentation ----------------------------------------------------------------------------

    static String degrees(double t) {
        return String.format(Locale.getDefault(), "%d°", Math.round(t));
    }

    /** WMO weather code to a short description. */
    static String describe(Context ctx, int code) {
        int res;
        if (code == 0) res = R.string.wx_clear;
        else if (code == 1) res = R.string.wx_mostly_clear;
        else if (code == 2) res = R.string.wx_partly_cloudy;
        else if (code == 3) res = R.string.wx_cloudy;
        else if (code == 45 || code == 48) res = R.string.wx_fog;
        else if (code >= 51 && code <= 57) res = R.string.wx_drizzle;
        else if (code == 65 || code == 67 || code == 82) res = R.string.wx_heavy_rain;
        else if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) res = R.string.wx_rain;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) res = R.string.wx_snow;
        else if (code >= 95) res = R.string.wx_storm;
        else res = R.string.wx_cloudy;
        return ctx.getString(res);
    }

    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 1 || code == 2) return day ? R.drawable.ic_wx_partly_day : R.drawable.ic_wx_partly_night;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.drawable.ic_wx_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_cloud;
    }
}
