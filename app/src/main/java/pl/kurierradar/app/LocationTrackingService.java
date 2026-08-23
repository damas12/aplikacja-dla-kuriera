package pl.kurierradar.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

public class LocationTrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "pl.kurierradar.app.START_TRACKING";
    public static final String ACTION_STOP = "pl.kurierradar.app.STOP_TRACKING";
    private static final String CHANNEL_ID = "tracking";
    private static final int NOTIF_ID = 1001;
    private static final String PREFS = "tracking_state";
    private static final String KEY_REQUESTED = "tracking_requested";
    public static volatile boolean isRunning = false;

    private LocationManager locationManager;
    private AppDatabase db;
    private long shiftId = -1;

    public static boolean isTrackingRequested(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false);
    }

    private static void setTrackingRequested(Context context, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUESTED, value).apply();
    }

    @Override public void onCreate() {
        super.onCreate();
        db = new AppDatabase(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            setTrackingRequested(this, false);
            stopTrackingAndSelf(true);
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) setTrackingRequested(this, true);

        // START_STICKY can recreate the service with a null Intent. Resume only when the user
        // had an active shift before Android/MIUI killed the process.
        if (!isTrackingRequested(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!isRunning) {
            isRunning = true;
            startForeground(NOTIF_ID, buildNotification("Zapisuję trasę GPS…"));
            shiftId = db.getOrStartActiveShift();
            requestUpdates();
        }
        return START_STICKY;
    }

    private void requestUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setTrackingRequested(this, false);
            stopTrackingAndSelf(true);
            return;
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 5f, this, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000L, 10f, this, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        if (location.hasAccuracy() && location.getAccuracy() > 150f) return;
        db.addLocation(location);
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}
    @Deprecated @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    private void stopTrackingAndSelf(boolean closeShift) {
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) {}
        if (closeShift && db != null) db.endActiveShift(shiftId);
        shiftId = -1;
        isRunning = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) {}
        // Do NOT close the shift here. Android/MIUI may destroy the service temporarily.
        // The open shift + persistent tracking flag let START_STICKY or the app resume it.
        isRunning = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Śledzenie zmiany", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Stałe zapisywanie pozycji podczas zmiany kurierskiej");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, LocationTrackingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Kurier Radar – zmiana aktywna")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Zatrzymaj", stopPi).build())
                .build();
    }
}
