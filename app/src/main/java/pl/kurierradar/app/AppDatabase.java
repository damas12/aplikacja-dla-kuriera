package pl.kurierradar.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import org.json.*;

import java.util.*;
import java.util.regex.Pattern;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "kurier_radar.db";
    private static final int DB_VERSION = 1;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final Pattern EXPLICIT_DATE = Pattern.compile("(?i)(?<!\\d)\\d{1,2}\\s*(?:[.,-]?\\s*)?(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|paź|paz|lis|gru)(?:\\s+\\d{4})?");

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

    public synchronized long getOrStartActiveShift() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long keepId = -1;
            try (Cursor c = db.rawQuery("SELECT id FROM shifts WHERE end_ts IS NULL ORDER BY start_ts ASC", null)) {
                if (c.moveToFirst()) keepId = c.getLong(0);
            }
            if (keepId > 0) {
                // Old versions could accidentally create more than one open row after a process restart.
                // Keep the original start and remove only the duplicate open rows.
                db.delete("shifts", "end_ts IS NULL AND id<>?", new String[]{Long.toString(keepId)});
                db.setTransactionSuccessful();
                return keepId;
            }
            ContentValues cv = new ContentValues();
            cv.put("start_ts", System.currentTimeMillis());
            long id = db.insert("shifts", null, cv);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized long getActiveShiftId() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM shifts WHERE end_ts IS NULL ORDER BY start_ts ASC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    public synchronized boolean hasOpenShift() { return getActiveShiftId() > 0; }

    public synchronized void endActiveShift(long preferredId) {
        SQLiteDatabase db = getWritableDatabase();
        long id = preferredId > 0 ? preferredId : getActiveShiftId();
        if (id <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put("end_ts", System.currentTimeMillis());
        db.update("shifts", cv, "id=? AND end_ts IS NULL", new String[]{Long.toString(id)});
    }

    public long getActiveShiftStartTs() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT start_ts FROM shifts WHERE end_ts IS NULL ORDER BY start_ts ASC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public long getLastLocationTsForActiveShift() {
        long start = getActiveShiftStartTs();
        if (start <= 0) return 0L;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(ts) FROM locations WHERE ts>=?", new String[]{Long.toString(start)})) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0L;
        }
    }

    public boolean insertDelivery(ParsedDelivery d) {
        SQLiteDatabase db = getWritableDatabase();
        LocationMatch match = nearestLocation(d.timestampMs, 10 * 60 * 1000L);

        ExistingDelivery existing = findExistingDelivery(db, d);
        if (existing != null) {
            mergeIntoExisting(db, existing, d, match);
            return false;
        }

        ContentValues cv = new ContentValues();
        cv.put("platform", d.platform);
        cv.put("ts", d.timestampMs);
        cv.put("restaurant", canonicalRestaurant(d.restaurant));
        cv.put("pickup", cleanCandidate(d.pickup));
        cv.put("dropoff", cleanCandidate(d.dropoff));
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
        long id = db.insertWithOnConflict("deliveries", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    private ExistingDelivery findExistingDelivery(SQLiteDatabase db, ParsedDelivery d) {
        // Fast exact match first.
        try (Cursor c = db.rawQuery(
                "SELECT id,ts,restaurant,pickup,dropoff,duration_min,lat,lon,raw FROM deliveries " +
                "WHERE platform=? AND ts=? AND ABS(amount-?)<0.021 AND ABS(COALESCE(distance_km,0)-?)<0.051 LIMIT 1",
                new String[]{d.platform, Long.toString(d.timestampMs), Double.toString(d.amount), Double.toString(d.distanceKm)})) {
            if (c.moveToFirst()) return existingFromCursor(c);
        }

        // Uber history can show the same order on several screenshots. A fallback OCR pass can
        // pick the phone clock or a wrong date, so timestamp/sourceKey alone is not safe enough.
        if (!"UBER".equalsIgnoreCase(d.platform)) return null;
        try (Cursor c = db.rawQuery(
                "SELECT id,ts,restaurant,pickup,dropoff,duration_min,lat,lon,raw FROM deliveries " +
                "WHERE platform='UBER' AND ABS(amount-?)<0.021 AND ABS(COALESCE(distance_km,0)-?)<0.051 " +
                "ORDER BY created_ts DESC LIMIT 80",
                new String[]{Double.toString(d.amount), Double.toString(d.distanceKm)})) {
            while (c.moveToNext()) {
                ExistingDelivery e = existingFromCursor(c);
                if (likelySameUberOrder(e, d)) return e;
            }
        }
        return null;
    }

    private void mergeIntoExisting(SQLiteDatabase db, ExistingDelivery old, ParsedDelivery d, LocationMatch match) {
        ContentValues up = new ContentValues();
        String incomingRestaurant = canonicalRestaurant(d.restaurant);
        if (isUnknownRestaurant(old.restaurant) && !isUnknownRestaurant(incomingRestaurant)) up.put("restaurant", incomingRestaurant);
        if (isWeakText(old.pickup) && !isWeakText(d.pickup)) up.put("pickup", cleanCandidate(d.pickup));
        if (isWeakText(old.dropoff) && !isWeakText(d.dropoff)) up.put("dropoff", cleanCandidate(d.dropoff));
        if (old.duration <= 0 && d.durationMin > 0) up.put("duration_min", d.durationMin);
        if ((old.lat == null || old.lon == null) && match != null) {
            up.put("lat", match.lat); up.put("lon", match.lon); up.put("match_gap_sec", match.gapMs / 1000L);
        }
        // Prefer the timestamp from a screenshot that actually contains a visible date.
        if (!hasExplicitDate(old.raw) && hasExplicitDate(d.raw)) up.put("ts", d.timestampMs);
        if (d.raw != null && !d.raw.isEmpty() && (old.raw == null || d.raw.length() > old.raw.length())) up.put("raw", d.raw);
        if (up.size() > 0) db.update("deliveries", up, "id=?", new String[]{Long.toString(old.id)});
    }

    public int deduplicateUberOrders() {
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<ExistingDeliveryFull> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id,ts,restaurant,pickup,dropoff,duration_min,lat,lon,raw,amount,distance_km FROM deliveries WHERE platform='UBER' ORDER BY id ASC", null)) {
            while (c.moveToNext()) {
                ExistingDelivery e = existingFromCursor(c);
                rows.add(new ExistingDeliveryFull(e, c.getDouble(9), c.getDouble(10)));
            }
        }

        int removed = 0;
        boolean[] gone = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            if (gone[i]) continue;
            ExistingDeliveryFull keep = rows.get(i);
            for (int j = i + 1; j < rows.size(); j++) {
                if (gone[j]) continue;
                ExistingDeliveryFull other = rows.get(j);
                if (!likelySameUberRows(keep, other)) continue;

                ContentValues up = new ContentValues();
                if (isUnknownRestaurant(keep.restaurant) && !isUnknownRestaurant(other.restaurant)) {
                    keep.restaurant = canonicalRestaurant(other.restaurant); up.put("restaurant", keep.restaurant);
                }
                if (isWeakText(keep.pickup) && !isWeakText(other.pickup)) { keep.pickup = cleanCandidate(other.pickup); up.put("pickup", keep.pickup); }
                if (isWeakText(keep.dropoff) && !isWeakText(other.dropoff)) { keep.dropoff = cleanCandidate(other.dropoff); up.put("dropoff", keep.dropoff); }
                if (keep.duration <= 0 && other.duration > 0) { keep.duration = other.duration; up.put("duration_min", keep.duration); }
                if ((keep.lat == null || keep.lon == null) && other.lat != null && other.lon != null) {
                    keep.lat = other.lat; keep.lon = other.lon; up.put("lat", keep.lat); up.put("lon", keep.lon);
                }
                if (!hasExplicitDate(keep.raw) && hasExplicitDate(other.raw)) { keep.ts = other.ts; keep.raw = other.raw; up.put("ts", keep.ts); up.put("raw", keep.raw); }
                else if ((keep.raw == null || keep.raw.length() < (other.raw == null ? 0 : other.raw.length())) && other.raw != null) {
                    keep.raw = other.raw; up.put("raw", keep.raw);
                }
                if (up.size() > 0) db.update("deliveries", up, "id=?", new String[]{Long.toString(keep.id)});
                db.delete("deliveries", "id=?", new String[]{Long.toString(other.id)});
                gone[j] = true;
                removed++;
            }
        }
        return removed;
    }

    private static boolean likelySameUberOrder(ExistingDelivery e, ParsedDelivery d) {
        if (Math.abs(e.duration - d.durationMin) <= 0.20 && e.duration > 0 && d.durationMin > 0) {
            // Exact fare + distance + duration is already a very strong fingerprint. Keep the
            // date window generous because one screenshot may not show the date at all.
            return Math.abs(e.ts - d.timestampMs) <= 31L * DAY_MS || minuteOfDayDiff(e.ts, d.timestampMs) <= 5;
        }
        return minuteOfDayDiff(e.ts, d.timestampMs) <= 3;
    }

    private static boolean likelySameUberRows(ExistingDeliveryFull a, ExistingDeliveryFull b) {
        if (Math.abs(a.amount - b.amount) > 0.021) return false;
        if (Math.abs(a.distance - b.distance) > 0.051) return false;
        if (a.duration > 0 && b.duration > 0) {
            if (Math.abs(a.duration - b.duration) > 0.20) return false;
            return Math.abs(a.ts - b.ts) <= 31L * DAY_MS || minuteOfDayDiff(a.ts, b.ts) <= 5;
        }
        return minuteOfDayDiff(a.ts, b.ts) <= 3;
    }

    private static long minuteOfDayDiff(long a, long b) {
        Calendar ca = Calendar.getInstance(); ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance(); cb.setTimeInMillis(b);
        int ma = ca.get(Calendar.HOUR_OF_DAY) * 60 + ca.get(Calendar.MINUTE);
        int mb = cb.get(Calendar.HOUR_OF_DAY) * 60 + cb.get(Calendar.MINUTE);
        int diff = Math.abs(ma - mb);
        return Math.min(diff, 24 * 60 - diff);
    }

    private static boolean hasExplicitDate(String raw) {
        return raw != null && EXPLICIT_DATE.matcher(raw).find();
    }

    private static String canonicalRestaurant(String s) {
        if (isUnknownRestaurant(s)) return "Niezidentyfikowano";
        return s == null ? "Niezidentyfikowano" : s.trim();
    }

    private static String cleanCandidate(String s) {
        if (s == null) return "";
        String n = s.trim();
        if (isMapCityNoise(n)) return "";
        return n;
    }

    private static boolean isWeakText(String s) { return s == null || s.trim().isEmpty() || isMapCityNoise(s); }

    private static boolean isMapCityNoise(String s) {
        if (s == null) return false;
        String n = s.trim().toLowerCase(Locale.ROOT);
        return n.equals("bydgoszcz") || n.equals("bydgoszcz, pl") || n.equals("bydgosz") || n.equals("bydgosz, pl");
    }

    private static ExistingDelivery existingFromCursor(Cursor c) {
        ExistingDelivery e = new ExistingDelivery();
        e.id = c.getLong(0); e.ts = c.getLong(1); e.restaurant = safe(c,2); e.pickup = safe(c,3); e.dropoff = safe(c,4);
        e.duration = c.isNull(5) ? 0.0 : c.getDouble(5);
        e.lat = c.isNull(6) ? null : c.getDouble(6); e.lon = c.isNull(7) ? null : c.getDouble(7); e.raw = safe(c,8);
        return e;
    }

    private static class ExistingDelivery {
        long id, ts; String restaurant, pickup, dropoff, raw; double duration; Double lat, lon;
    }
    private static class ExistingDeliveryFull extends ExistingDelivery {
        final double amount, distance;
        ExistingDeliveryFull(ExistingDelivery e, double amount, double distance) {
            this.id=e.id; this.ts=e.ts; this.restaurant=e.restaurant; this.pickup=e.pickup; this.dropoff=e.dropoff; this.raw=e.raw;
            this.duration=e.duration; this.lat=e.lat; this.lon=e.lon; this.amount=amount; this.distance=distance;
        }
    }

    private static boolean isUnknownRestaurant(String s) {
        if (s == null) return true;
        String n = s.trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() || n.equals("uber eats") || n.equals("niezidentyfikowano") || n.equals("nieznane") || isMapCityNoise(n);
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

    public String getStatusJson(boolean serviceRunning, boolean trackingRequested) {
        JSONObject o = new JSONObject();
        try {
            o.put("tracking", trackingRequested);
            o.put("serviceRunning", serviceRunning);
            o.put("deliveryCount", countDeliveries());
            o.put("locationCount", countLocations());
            o.put("unmatchedCount", countUnmatchedDeliveries());
            o.put("activeShiftStartTs", getActiveShiftStartTs());
            o.put("lastGpsTs", getLastLocationTsForActiveShift());
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
