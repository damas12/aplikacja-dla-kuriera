package pl.kurierradar.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 501;
    private static final int REQ_IMAGES = 502;
    private static final int REQ_EXPORT = 503;

    private WebView webView;
    private AppDatabase db;
    private TextRecognizer recognizer;
    private String pendingPlatformHint = "auto";
    private boolean startAfterPermission = false;
    private String pendingCsv = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new AppDatabase(this);
        db.deduplicateUberOrders();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "Android");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        recoverTrackingIfNeeded();
    }


    @Override protected void onResume() {
        super.onResume();
        if (db != null) {
            recoverTrackingIfNeeded();
            if (webView != null) webView.postDelayed(this::sendStatusToWeb, 300);
        }
    }

    private void recoverTrackingIfNeeded() {
        if (!LocationTrackingService.isTrackingRequested(this) || LocationTrackingService.isRunning) return;
        Intent i = new Intent(this, LocationTrackingService.class).setAction(LocationTrackingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }

    private void startTrackingFlow() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Tracking can still work without notification permission, but ask once for a clearer foreground indicator.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_LOCATION);
        }
        Intent i = new Intent(this, LocationTrackingService.class).setAction(LocationTrackingService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        sendStatusToWeb();
    }

    private void stopTracking() {
        Intent i = new Intent(this, LocationTrackingService.class).setAction(LocationTrackingService.ACTION_STOP);
        startService(i);
        webView.postDelayed(this::sendStatusToWeb, 350);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && startAfterPermission) {
            startAfterPermission = false;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startTrackingFlow();
            } else {
                toast("Bez dostępu do lokalizacji nie mogę zapisywać trasy.");
            }
        }
    }

    private void chooseScreenshots(String platformHint) {
        pendingPlatformHint = platformHint == null ? "auto" : platformHint;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMAGES);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_IMAGES) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) processScreenshots(uris, 0, new ImportSummary());
        } else if (requestCode == REQ_EXPORT && pendingCsv != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) out.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                toast("Zapisano CSV.");
            } catch (IOException e) { toast("Nie udało się zapisać pliku."); }
            pendingCsv = null;
        }
    }

    private void processScreenshots(List<Uri> uris, int index, ImportSummary summary) {
        if (index >= uris.size()) {
            db.deduplicateUberOrders();
            summary.unmatched = db.countUnmatchedDeliveries();
            JSONObject o = summary.toJson();
            sendToWeb("window.onNativeImportResult && window.onNativeImportResult(" + o.toString() + ");");
            sendToWeb("window.refreshAll && window.refreshAll();");
            return;
        }

        Uri uri = uris.get(index);
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        summary.images++;
                        String raw = result.getText();
                        List<ParsedDelivery> parsed = DeliveryLayoutParser.parse(result, pendingPlatformHint);
                        summary.recognized += parsed.size();
                        for (ParsedDelivery d : parsed) {
                            if (db.insertDelivery(d)) summary.added++; else summary.duplicates++;
                        }
                        processScreenshots(uris, index + 1, summary);
                    })
                    .addOnFailureListener(e -> {
                        summary.images++;
                        summary.failedImages++;
                        processScreenshots(uris, index + 1, summary);
                    });
        } catch (IOException e) {
            summary.images++;
            summary.failedImages++;
            processScreenshots(uris, index + 1, summary);
        }
    }

    private void exportCsv() {
        pendingCsv = db.exportCsv();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "kurier-radar-dostawy.csv");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void sendStatusToWeb() {
        sendToWeb("window.onNativeStatus && window.onNativeStatus(" + db.getStatusJson(LocationTrackingService.isRunning, LocationTrackingService.isTrackingRequested(this)) + ");");
    }

    private void sendToWeb(String js) { runOnUiThread(() -> webView.evaluateJavascript(js, null)); }
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    public class Bridge {
        @JavascriptInterface public String getDeliveries() { return db.getDeliveriesJson(); }
        @JavascriptInterface public String getShifts() { return db.getShiftsJson(); }
        @JavascriptInterface public String getStatus() { return db.getStatusJson(LocationTrackingService.isRunning, LocationTrackingService.isTrackingRequested(MainActivity.this)); }
        @JavascriptInterface public void startTracking() { runOnUiThread(MainActivity.this::startTrackingFlow); }
        @JavascriptInterface public void stopTracking() { runOnUiThread(MainActivity.this::stopTracking); }
        @JavascriptInterface public void pickScreenshots(String platformHint) { runOnUiThread(() -> chooseScreenshots(platformHint)); }
        @JavascriptInterface public void exportCsv() { runOnUiThread(MainActivity.this::exportCsv); }
        @JavascriptInterface public void openBatterySettings() { runOnUiThread(MainActivity.this::openBatterySettings); }
        @JavascriptInterface public void clearAll() {
            db.clearAll();
            sendToWeb("window.refreshAll && window.refreshAll();");
        }
        @JavascriptInterface public String appVersion() { return "1.1.0"; }
    }

    private static class ImportSummary {
        int images, failedImages, recognized, added, duplicates, unmatched;
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("images", images); o.put("failedImages", failedImages); o.put("recognized", recognized);
                o.put("added", added); o.put("duplicates", duplicates); o.put("unmatched", unmatched);
            } catch (JSONException ignored) {}
            return o;
        }
    }
}
