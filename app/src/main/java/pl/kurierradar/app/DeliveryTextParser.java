package pl.kurierradar.app;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * OCR text parser for Wolt Courier and Uber Driver earnings/history screenshots.
 * It intentionally uses forgiving patterns because OCR commonly confuses punctuation and spacing.
 */
public final class DeliveryTextParser {
    private DeliveryTextParser() {}

    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,4}(?:[ .]\\d{3})*[,.]\\d{2})\\s*z[łl]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DISTANCE = Pattern.compile("(\\d{1,3}(?:[,.]\\d{1,2}))\\s*km", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("(\\d{1,3})\\s*min(?:\\s*(\\d{1,2})\\s*s)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(?i)(?<!\\d)(\\d{1,2})\\s*(?:[.,-]?\\s*)?(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|paź|paz|lis|gru)(?:\\s+(\\d{4}))?");

    private static final Map<String, Integer> MONTHS = new HashMap<>();
    static {
        MONTHS.put("sty", 1); MONTHS.put("lut", 2); MONTHS.put("mar", 3); MONTHS.put("kwi", 4);
        MONTHS.put("maj", 5); MONTHS.put("cze", 6); MONTHS.put("lip", 7); MONTHS.put("sie", 8);
        MONTHS.put("wrz", 9); MONTHS.put("paź", 10); MONTHS.put("paz", 10); MONTHS.put("lis", 11); MONTHS.put("gru", 12);
    }

    public static String detectPlatform(String text, String hint) {
        if (hint != null && !hint.equalsIgnoreCase("auto") && !hint.trim().isEmpty()) {
            return hint.toUpperCase(Locale.ROOT);
        }
        String s = normalize(text).toLowerCase(Locale.ROOT);
        if (s.contains("pełna płatność") || s.contains("pelna platnosc") || s.contains("zrealizowane zamówienia") || s.contains("zrealizowane zamowienia")) {
            return "WOLT";
        }
        if ((s.contains("delivery") || s.contains("dostawa")) && s.contains("km")) return "UBER";
        if (s.contains("uber")) return "UBER";
        if (s.contains("wolt")) return "WOLT";
        return "UNKNOWN";
    }

    public static List<ParsedDelivery> parse(String text, String hint) {
        String platform = detectPlatform(text, hint);
        LocalDate date = extractDate(text);
        if ("WOLT".equals(platform)) return parseWolt(text, date);
        if ("UBER".equals(platform)) return parseUber(text, date);
        // Try both and keep the parser that found more usable rows.
        List<ParsedDelivery> w = parseWolt(text, date);
        List<ParsedDelivery> u = parseUber(text, date);
        return w.size() >= u.size() ? w : u;
    }

    public static LocalDate extractDate(String text) {
        Matcher m = DATE.matcher(normalize(text));
        if (m.find()) {
            int day = safeInt(m.group(1), 1);
            String mm = m.group(2).toLowerCase(Locale.ROOT);
            int month = MONTHS.getOrDefault(mm, LocalDate.now().getMonthValue());
            int year = m.group(3) != null ? safeInt(m.group(3), LocalDate.now().getYear()) : LocalDate.now().getYear();
            try {
                LocalDate d = LocalDate.of(year, month, day);
                if (m.group(3) == null && d.isAfter(LocalDate.now().plusDays(31))) d = d.minusYears(1);
                return d;
            } catch (DateTimeException ignored) {}
        }
        return LocalDate.now();
    }

    private static List<ParsedDelivery> parseWolt(String text, LocalDate date) {
        List<String> lines = lines(text);
        List<ParsedDelivery> out = new ArrayList<>();
        Set<String> seenTimes = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher tm = TIME.matcher(line);
            if (!tm.find()) continue;
            String timeToken = tm.group(1) + ":" + tm.group(2);

            // Ignore the device clock/header when it is far away from delivery-looking content.
            SearchResult amountR = findAmount(lines, i, i + 5);
            SearchResult distanceR = findDistance(lines, i, i + 5);
            if (amountR == null || distanceR == null) continue;

            String restaurant = cleanRestaurant(line.substring(0, tm.start()).trim());
            if (restaurant.isEmpty()) restaurant = findRestaurantBefore(lines, i);
            if (restaurant.isEmpty() || isUiNoise(restaurant)) continue;

            LocalTime time;
            try { time = LocalTime.of(safeInt(tm.group(1), 0), safeInt(tm.group(2), 0)); }
            catch (DateTimeException e) { continue; }

            ParsedDelivery d = new ParsedDelivery();
            d.platform = "WOLT";
            d.timestampMs = toMillis(date, time);
            d.restaurant = restaurant;
            d.pickup = restaurant;
            d.amount = amountR.value;
            d.distanceKm = distanceR.value;
            d.raw = joinNeighborhood(lines, Math.max(0, i - 2), Math.min(lines.size(), i + 6));

            String dedupe = d.sourceKey();
            if (seenTimes.add(dedupe)) out.add(d);
        }
        return out;
    }

    private static List<ParsedDelivery> parseUber(String text, LocalDate date) {
        List<String> lines = lines(text);
        List<ParsedDelivery> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String lower = normalize(lines.get(i)).toLowerCase(Locale.ROOT);
            if (!(lower.contains("delivery") || lower.contains("dostawa"))) continue;

            String neighborhood = joinNeighborhood(lines, Math.max(0, i - 2), Math.min(lines.size(), i + 3));
            Matcher dm = DISTANCE.matcher(neighborhood);
            if (!dm.find()) continue;
            double distance = parseDecimal(dm.group(1));

            Matcher dur = DURATION.matcher(neighborhood);
            double duration = 0;
            if (dur.find()) duration = safeInt(dur.group(1), 0) + safeInt(dur.group(2), 0) / 60.0;

            SearchResult amountR = findAmountBackward(lines, Math.max(0, i - 4), i + 1);
            TimeResult timeR = findTimeBackward(lines, Math.max(0, i - 4), i + 1);
            if (amountR == null || timeR == null) continue;

            String pickup = findUberPickupAfter(lines, i + 1, Math.min(lines.size(), i + 12));
            String dropoff = findUberDropoffAfter(lines, i + 1, Math.min(lines.size(), i + 14), pickup);

            ParsedDelivery d = new ParsedDelivery();
            d.platform = "UBER";
            d.timestampMs = toMillis(date, timeR.time);
            d.restaurant = pickup.isEmpty() ? "Niezidentyfikowano" : pickup;
            d.pickup = pickup;
            d.dropoff = dropoff;
            d.amount = amountR.value;
            d.distanceKm = distance;
            d.durationMin = duration;
            d.raw = joinNeighborhood(lines, Math.max(0, i - 4), Math.min(lines.size(), i + 14));
            if (seen.add(d.sourceKey())) out.add(d);
        }
        return out;
    }

    private static String findRestaurantBefore(List<String> lines, int timeIndex) {
        List<String> parts = new ArrayList<>();
        for (int j = timeIndex - 1; j >= Math.max(0, timeIndex - 3); j--) {
            String c = cleanRestaurant(lines.get(j));
            if (c.isEmpty() || isUiNoise(c) || containsAmount(c) || containsDistance(c) || TIME.matcher(c).find()) break;
            parts.add(0, c);
            if (c.length() > 14) break;
        }
        return String.join(" ", parts).trim();
    }

    private static String findUberPickupAfter(List<String> lines, int from, int to) {
        for (int i = from; i < to; i++) {
            String s = lines.get(i).trim();
            if (isUberMapNoise(s) || isUiNoise(s) || s.length() < 3) continue;
            if (looksLikeAddress(s)) continue;
            if (containsAmount(s) || containsDistance(s) || TIME.matcher(s).find()) continue;
            if (looksLikeBusinessName(s)) return s;
        }
        return "";
    }

    private static String findUberDropoffAfter(List<String> lines, int from, int to, String pickup) {
        boolean afterPickup = pickup == null || pickup.isEmpty();
        for (int i = from; i < to; i++) {
            String s = lines.get(i).trim();
            if (!afterPickup) {
                if (s.equals(pickup)) afterPickup = true;
                continue;
            }
            if (isUberMapNoise(s) || isUiNoise(s) || s.length() < 3) continue;
            if (looksLikeAddress(s)) return s;
        }
        return "";
    }

    private static boolean looksLikeBusinessName(String s) {
        String n = normalize(s);
        if (n.length() < 3 || n.length() > 90) return false;
        int letters = 0, upper = 0;
        for (char c : n.toCharArray()) {
            if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        }
        if (letters == 0) return false;
        // Map labels are often all-uppercase; business names usually aren't.
        return upper < Math.max(4, letters * 0.8);
    }

    private static boolean looksLikeAddress(String s) {
        String n = normalize(s).toLowerCase(Locale.ROOT);
        return n.matches(".*\\d{2}-\\d{3}.*") || n.contains(", pl") || n.matches(".*\\b(ul\\.|aleja|al\\.|rynek|sandomierska|urocza|gdańska|gdanska|fordońska|fordonska)\\b.*");
    }

    private static boolean isUberMapNoise(String s) {
        String n = normalize(s).trim();
        String l = n.toLowerCase(Locale.ROOT);
        if (l.contains("google") || l.contains("map data") || l.contains("©")) return true;
        if (l.equals("bydgoszcz") || l.equals("bydgoszcz, pl")) return true;
        if (n.matches("^[0-9]{1,3}$")) return true;
        int letters = 0, upper = 0;
        for (char c : n.toCharArray()) if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        return letters >= 4 && upper > letters * 0.85;
    }


    private static SearchResult findAmountBackward(List<String> lines, int from, int to) {
        from = Math.max(0, from); to = Math.min(lines.size(), to);
        for (int i = to - 1; i >= from; i--) {
            Matcher m = AMOUNT.matcher(lines.get(i));
            if (m.find()) return new SearchResult(parseDecimal(m.group(1)), i);
        }
        return null;
    }

    private static TimeResult findTimeBackward(List<String> lines, int from, int to) {
        from = Math.max(0, from); to = Math.min(lines.size(), to);
        for (int i = to - 1; i >= from; i--) {
            Matcher m = TIME.matcher(lines.get(i));
            if (m.find()) {
                try { return new TimeResult(LocalTime.of(safeInt(m.group(1),0), safeInt(m.group(2),0)), i); }
                catch (DateTimeException ignored) {}
            }
        }
        return null;
    }

    private static SearchResult findAmount(List<String> lines, int from, int to) {
        from = Math.max(0, from); to = Math.min(lines.size(), to);
        for (int i = from; i < to; i++) {
            Matcher m = AMOUNT.matcher(lines.get(i));
            if (m.find()) return new SearchResult(parseDecimal(m.group(1)), i);
        }
        return null;
    }

    private static SearchResult findDistance(List<String> lines, int from, int to) {
        from = Math.max(0, from); to = Math.min(lines.size(), to);
        for (int i = from; i < to; i++) {
            Matcher m = DISTANCE.matcher(lines.get(i));
            if (m.find()) return new SearchResult(parseDecimal(m.group(1)), i);
        }
        return null;
    }

    private static TimeResult findTime(List<String> lines, int from, int to) {
        from = Math.max(0, from); to = Math.min(lines.size(), to);
        for (int i = from; i < to; i++) {
            Matcher m = TIME.matcher(lines.get(i));
            if (m.find()) {
                try { return new TimeResult(LocalTime.of(safeInt(m.group(1),0), safeInt(m.group(2),0)), i); }
                catch (DateTimeException ignored) {}
            }
        }
        return null;
    }

    private static boolean containsAmount(String s) { return AMOUNT.matcher(s).find(); }
    private static boolean containsDistance(String s) { return DISTANCE.matcher(s).find(); }

    private static boolean isUiNoise(String s) {
        String n = normalize(s).toLowerCase(Locale.ROOT);
        return n.trim().isEmpty() || n.equals("inne") || n.contains("zrealizowane zamówienia") || n.contains("zrealizowane zamowienia")
                || n.contains("pełna płatność") || n.contains("pelna platnosc") || n.contains("przychód") || n.contains("przychod")
                || n.contains("strona głów") || n.contains("strona glow") || n.contains("odkrywaj") || n.contains("skrzynka") || n.equals("menu")
                || n.contains("rodzaj") || n.contains("cecha") || n.matches(".*\\d{1,2}\\.\\d{2}[-–]\\d{1,2}\\.\\d{2}.*");
    }

    private static String cleanRestaurant(String s) {
        String x = s.replaceAll("[•·|]+$", "").trim();
        x = AMOUNT.matcher(x).replaceAll("");
        x = DISTANCE.matcher(x).replaceAll("");
        return x.replaceAll("\\s{2,}", " ").trim();
    }

    private static List<String> lines(String text) {
        String[] raw = normalize(text).split("\\n");
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String s = r.replace('\u00A0', ' ').replaceAll("\\s{2,}", " ").trim();
            if (!s.trim().isEmpty()) out.add(s);
        }
        return out;
    }

    private static String joinNeighborhood(List<String> lines, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (b.length() > 0) b.append("\\n");
            b.append(lines.get(i));
        }
        return b.toString();
    }

    private static long toMillis(LocalDate d, LocalTime t) {
        return ZonedDateTime.of(d, t, ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static double parseDecimal(String s) {
        if (s == null) return 0;
        String n = s.replace(" ", "").replace(',', '.');
        try { return Double.parseDouble(n); } catch (NumberFormatException e) { return 0; }
    }

    private static int safeInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00A0',' ').replace('–','-').replace('—','-');
    }

    private static final class SearchResult {
        final double value; final int line;
        SearchResult(double value, int line) { this.value = value; this.line = line; }
    }
    private static final class TimeResult {
        final LocalTime time; final int line;
        TimeResult(LocalTime time, int line) { this.time = time; this.line = line; }
    }
}
