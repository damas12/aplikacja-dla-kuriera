package pl.kurierradar.app;

public class ParsedDelivery {
    public String platform = "UNKNOWN";
    public long timestampMs;
    public String restaurant = "";
    public String pickup = "";
    public String dropoff = "";
    public double amount = 0.0;
    public double distanceKm = 0.0;
    public double durationMin = 0.0;
    public String raw = "";

    public String sourceKey() {
        String name = restaurant == null ? "" : restaurant.trim().toLowerCase();
        return platform + "|" + timestampMs + "|" + String.format(java.util.Locale.US, "%.2f", amount)
                + "|" + String.format(java.util.Locale.US, "%.2f", distanceKm) + "|" + name;
    }
}
