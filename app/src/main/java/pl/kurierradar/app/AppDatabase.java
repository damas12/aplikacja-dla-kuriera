package pl.kurierradar.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import org.json.*;

import java.util.Locale;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "kurier_radar.db";
    private static final int DB_VERSION = 1;

    public AppDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, speed REAL)");
        db.execSQL("CREATE INDEX idx_locations_ts ON locations(ts)");
        db.execSQL("CREATE TABLE deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, ts INTEGER NOT NULL, restaurant TEXT, pickup TEXT, dropoff TEXT, amount REAL NOT NULL, distance_km REAL, duration_min REAL, lat REAL, lon REAL, match_gap_sec INTEGER, source_key TEXT UNIQUE, raw TEXT, created_ts INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_deliveries_ts ON deliveries(ts)");
        db.execSQL("CREATE TABLE shifts (id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER NOT NULL, end_ts INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void addLocation(Location loc) {
        ContentValues cv = new ContentValues();
        cv.put("ts", loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis());
        cv.put("lat", loc.getLatitude());
        cv.put("lon", loc.getLongitude());
        cv.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : null);
        cv.put("speed", loc.hasSpeed() ? loc.getSpeed() : null);
        getWritableDatabase().insert("locations", null, cv);
    }

    public long startShift() {
        ContentValues cv = new ContentValues();
        cv.put("start_ts", System.currentTimeMillis());
        return getWritableDatabase().insert("shifts", null, cv);
    }

    public void endOpenShift() {
        ContentValues cv = new ContentValues();
        cv.put("end_ts", System.currentTimeMillis());
        getWritableDatabase().update("shifts", cv, "end_ts IS NULL", null);
    }

    public boolean insertDelivery(ParsedDelivery d) {
        LocationMatch match = nearestLocation(d.timestampMs, 10 * 60 * 1000L);
        ContentValues cv = new ContentValues();
        cv.put("platform", d.platform);
        cv.put("ts", d.timestampMs);
        cv.put("restaurant", d.restaurant);
        cv.put("pickup", d.pickup);
        cv.put("dropoff", d.dropoff);
        cv.put("amount", d.amount);
        cv.put("distance_km", d.distanceKm);
        cv.put("duration_min", d.durationMin);
        if (match != null) {
            cv.put("lat", match.lat);
            cv.put("lon", match.lon);
            cv.put("match_gap_sec", match.gapMs / 1000L);
        }
        cv.put("source_key", d.sourceKey());
        cv.put("raw", d.raw);
        cv.put("created_ts", System.currentTimeMillis());
        long id = getWritableDatabase().insertWithOnConflict("deliveries", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    public int countDeliveries() { return scalarInt("SELECT COUNT(*) FROM deliveries"); }
    public int countLocations() { return scalarInt("SELECT COUNT(*) FROM locations"); }
    public int countUnmatchedDeliveries() { return scalarInt("SELECT COUNT(*) FROM deliveries WHERE lat IS NULL OR lon IS NULL"); }

    private int scalarInt(String sql) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public String getDeliveriesJson() {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,platform,ts,restaurant,pickup,dropoff,amount,distance_km,duration_min,lat,lon,match_gap_sec FROM deliveries ORDER BY ts DESC", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.getLong(0));
                    o.put("platform", safe(c,1));
                    o.put("ts", c.getLong(2));
                    o.put("restaurant", safe(c,3));
                    o.put("pickup", safe(c,4));
                    o.put("dropoff", safe(c,5));
                    o.put("amount", c.getDouble(6));
                    o.put("distanceKm", c.getDouble(7));
                    o.put("durationMin", c.getDouble(8));
                    if (!c.isNull(9)) o.put("lat", c.getDouble(9)); else o.put("lat", JSONObject.NULL);
                    if (!c.isNull(10)) o.put("lon", c.getDouble(10)); else o.put("lon", JSONObject.NULL);
                    if (!c.isNull(11)) o.put("matchGapSec", c.getLong(11)); else o.put("matchGapSec", JSONObject.NULL);
                    arr.put(o);
                } catch (JSONException ignored) {}
            }
        }
        return arr.toString();
    }

    public String getShiftsJson() {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,start_ts,end_ts FROM shifts ORDER BY start_ts DESC", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.getLong(0));
                    o.put("startTs", c.getLong(1));
                    if (!c.isNull(2)) o.put("endTs", c.getLong(2)); else o.put("endTs", JSONObject.NULL);
                    arr.put(o);
                } catch (JSONException ignored) {}
            }
        }
        return arr.toString();
    }

    public String getStatusJson(boolean tracking) {
        JSONObject o = new JSONObject();
        try {
            o.put("tracking", tracking);
            o.put("deliveryCount", countDeliveries());
            o.put("locationCount", countLocations());
            o.put("unmatchedCount", countUnmatchedDeliveries());
        } catch (JSONException ignored) {}
        return o.toString();
    }

    public String exportCsv() {
        StringBuilder b = new StringBuilder("platform,data_czas,restauracja,odbior,dowoz,kwota_zl,dystans_km,czas_min,lat,lon,roznica_gps_s\n");
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT platform,ts,restaurant,pickup,dropoff,amount,distance_km,duration_min,lat,lon,match_gap_sec FROM deliveries ORDER BY ts", null)) {
            while (c.moveToNext()) {
                b.append(csv(safe(c,0))).append(',')
                 .append(c.getLong(1)).append(',')
                 .append(csv(safe(c,2))).append(',')
                 .append(csv(safe(c,3))).append(',')
                 .append(csv(safe(c,4))).append(',')
                 .append(String.format(Locale.US,"%.2f",c.getDouble(5))).append(',')
                 .append(String.format(Locale.US,"%.2f",c.getDouble(6))).append(',')
                 .append(String.format(Locale.US,"%.2f",c.getDouble(7))).append(',')
                 .append(c.isNull(8)?"":String.format(Locale.US,"%.7f",c.getDouble(8))).append(',')
                 .append(c.isNull(9)?"":String.format(Locale.US,"%.7f",c.getDouble(9))).append(',')
                 .append(c.isNull(10)?"":c.getLong(10)).append('\n');
            }
        }
        return b.toString();
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("deliveries", null, null);
        db.delete("locations", null, null);
        db.delete("shifts", null, null);
    }

    private static String safe(Cursor c, int idx) { return c.isNull(idx) ? "" : c.getString(idx); }
    private static String csv(String s) { return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\""; }

    private LocationMatch nearestLocation(long ts, long maxGapMs) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT lat,lon,ABS(ts-?) AS gap FROM locations ORDER BY gap ASC LIMIT 1",
                new String[]{Long.toString(ts)})) {
            if (c.moveToFirst()) {
                long gap = c.getLong(2);
                if (gap <= maxGapMs) return new LocationMatch(c.getDouble(0), c.getDouble(1), gap);
            }
        }
        return null;
    }

    private static final class LocationMatch {
        final double lat, lon; final long gapMs;
        LocationMatch(double lat, double lon, long gapMs) { this.lat=lat; this.lon=lon; this.gapMs=gapMs; }
    }
}
