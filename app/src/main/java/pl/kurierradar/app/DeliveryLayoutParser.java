package pl.kurierradar.app;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * Layout-aware OCR parser for courier screenshots.
 *
 * ML Kit's result.getText() may flatten multi-column UI in an order that does not
 * match what is visually on screen. Wolt and Uber both use two-column cards, so
 * this parser also uses each OCR line's bounding box and groups fields by their
 * visual card/row. The old text-only parser is kept as a fallback.
 */
public final class DeliveryLayoutParser {
    private DeliveryLayoutParser() {}

    private static final Pattern TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,4}(?:[ .]\\d{3})*[,.]\\d{2})\\s*z[łl]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DISTANCE = Pattern.compile("(\\d{1,3}(?:[,.]\\d{1,2}))\\s*km", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile("(\\d{1,3})\\s*min(?:\\s*(\\d{1,2})\\s*s)?", Pattern.CASE_INSENSITIVE);

    public static List<ParsedDelivery> parse(Text result, String hint) {
        if (result == null) return Collections.emptyList();
        String raw = result.getText() == null ? "" : result.getText();
        String platform = DeliveryTextParser.detectPlatform(raw, hint);
        LocalDate date = DeliveryTextParser.extractDate(raw);
        List<OcrLine> lines = extractLines(result);

        List<ParsedDelivery> layout;
        if ("WOLT".equals(platform)) {
            layout = parseWolt(lines, date, raw);
        } else if ("UBER".equals(platform)) {
            layout = parseUber(lines, date, raw);
        } else {
            List<ParsedDelivery> w = parseWolt(lines, date, raw);
            List<ParsedDelivery> u = parseUber(lines, date, raw);
            layout = w.size() >= u.size() ? w : u;
        }

        List<ParsedDelivery> flat = DeliveryTextParser.parse(raw, hint);
        return mergePreferLayout(layout, flat);
    }

    private static List<ParsedDelivery> mergePreferLayout(List<ParsedDelivery> layout, List<ParsedDelivery> flat) {
        // The flat OCR parser is only a fallback. On Uber screenshots it may accidentally
        // bind the phone clock or a map label to a card that the layout parser already read.
        // De-duplicate by the stable order fields instead of restaurant/sourceKey.
        List<ParsedDelivery> merged = new ArrayList<>();
        merged.addAll(layout);
        for (ParsedDelivery f : flat) {
            boolean same = false;
            for (ParsedDelivery d : merged) {
                if (sameOrderOnScreenshot(d, f)) { same = true; break; }
            }
            if (!same) merged.add(f);
        }
        return merged;
    }

    private static boolean sameOrderOnScreenshot(ParsedDelivery a, ParsedDelivery b) {
        if (a == null || b == null || !Objects.equals(a.platform, b.platform)) return false;
        if (Math.abs(a.amount - b.amount) > 0.03) return false;
        if (Math.abs(a.distanceKm - b.distanceKm) > 0.06) return false;
        if (a.durationMin > 0 && b.durationMin > 0 && Math.abs(a.durationMin - b.durationMin) <= 0.20) return true;
        long minuteA = Math.floorMod(a.timestampMs / 60000L, 24L * 60L);
        long minuteB = Math.floorMod(b.timestampMs / 60000L, 24L * 60L);
        long diff = Math.abs(minuteA - minuteB);
        diff = Math.min(diff, 24L * 60L - diff);
        return diff <= 3;
    }

    private static List<OcrLine> extractLines(Text result) {
        List<OcrLine> out = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                String s = clean(line.getText());
                if (r != null && !s.isEmpty()) out.add(new OcrLine(s, new Rect(r)));
            }
        }
        out.sort(Comparator.comparingInt((OcrLine l) -> l.box.top).thenComparingInt(l -> l.box.left));
        return out;
    }

    private static List<ParsedDelivery> parseWolt(List<OcrLine> lines, LocalDate date, String raw) {
        List<ParsedDelivery> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        int width = imageWidth(lines);

        List<OcrLine> times = new ArrayList<>();
        for (OcrLine l : lines) {
            if (parseTime(l.text) != null && l.box.centerX() > width * 0.58) times.add(l);
        }
        times.sort(Comparator.comparingInt(l -> l.box.centerY()));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < times.size(); i++) {
            OcrLine timeLine = times.get(i);
            LocalTime time = parseTime(timeLine.text);
            if (time == null) continue;

            int startY = Math.max(0, timeLine.box.centerY() - Math.max(55, timeLine.box.height() * 2));
            int endY = i + 1 < times.size()
                    ? Math.max(timeLine.box.centerY() + 35, (timeLine.box.centerY() + times.get(i + 1).box.centerY()) / 2)
                    : timeLine.box.centerY() + 180;

            OcrLine amountLine = nearestBelowMatching(lines, timeLine.box.centerY() - 10, endY, AMOUNT, width * 0.50, true);
            OcrLine distanceLine = nearestBelowMatching(lines, timeLine.box.centerY() - 5, endY, DISTANCE, 0, false);
            if (amountLine == null || distanceLine == null) continue; // ignores device clock/header

            double amount = firstDecimal(amountLine.text, AMOUNT);
            double distance = firstDecimal(distanceLine.text, DISTANCE);
            if (amount <= 0 || distance <= 0) continue;

            int detailsY = Math.min(amountLine.box.top, distanceLine.box.top);
            List<OcrLine> nameLines = new ArrayList<>();
            for (OcrLine l : lines) {
                int cy = l.box.centerY();
                if (cy < startY || cy >= detailsY) continue;
                if (l.box.centerX() > width * 0.82) continue;
                if (l == timeLine) continue;
                if (TIME.matcher(l.text).find() || AMOUNT.matcher(l.text).find() || DISTANCE.matcher(l.text).find()) continue;
                if (isWoltNoise(l.text)) continue;
                // Names live in the left part of the top row. This still allows a long name to reach toward the right.
                if (l.box.left < width * 0.72) nameLines.add(l);
            }
            nameLines.sort(Comparator.comparingInt((OcrLine l) -> l.box.top).thenComparingInt(l -> l.box.left));
            String restaurant = joinNameLines(nameLines);
            if (restaurant.isEmpty()) restaurant = "Wolt";

            ParsedDelivery d = new ParsedDelivery();
            d.platform = "WOLT";
            d.timestampMs = toMillis(date, time);
            d.restaurant = restaurant;
            d.pickup = restaurant;
            d.amount = amount;
            d.distanceKm = distance;
            d.raw = zoneText(lines, startY, endY);

            if (seen.add(d.sourceKey())) out.add(d);
        }
        return out;
    }

    private static List<ParsedDelivery> parseUber(List<OcrLine> lines, LocalDate date, String raw) {
        List<ParsedDelivery> out = new ArrayList<>();
        if (lines.isEmpty()) return out;
        int width = imageWidth(lines);

        // Uber cards start with amount on the left and time on the right on approximately the same visual row.
        List<UberCardStart> starts = new ArrayList<>();
        for (OcrLine amountLine : lines) {
            Matcher am = AMOUNT.matcher(amountLine.text);
            if (!am.find() || amountLine.box.centerX() > width * 0.62) continue;
            OcrLine bestTime = null;
            int bestDy = Integer.MAX_VALUE;
            for (OcrLine candidate : lines) {
                if (candidate.box.centerX() < width * 0.58) continue;
                if (parseTime(candidate.text) == null) continue;
                int dy = Math.abs(candidate.box.centerY() - amountLine.box.centerY());
                int tolerance = Math.max(36, Math.max(candidate.box.height(), amountLine.box.height()) * 2);
                if (dy <= tolerance && dy < bestDy) {
                    bestDy = dy;
                    bestTime = candidate;
                }
            }
            if (bestTime != null) starts.add(new UberCardStart(amountLine, bestTime));
        }
        starts.sort(Comparator.comparingInt(s -> s.amount.box.top));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < starts.size(); i++) {
            UberCardStart s = starts.get(i);
            int startY = Math.max(0, Math.min(s.amount.box.top, s.time.box.top) - 12);
            // A full Uber card is tall because the route map sits between the fare row and
            // the pickup/drop-off lines. Using the midpoint to the next fare row cut the card
            // inside the map and hid the restaurant name. Keep the whole card up to just before
            // the next order starts.
            int endY = i + 1 < starts.size()
                    ? Math.max(startY + 120, starts.get(i + 1).amount.box.top - 8)
                    : maxBottom(lines) + 20;

            OcrLine deliveryLine = null;
            for (OcrLine l : lines) {
                int cy = l.box.centerY();
                if (cy <= s.amount.box.centerY() || cy >= endY) continue;
                String low = normalize(l.text).toLowerCase(Locale.ROOT);
                if ((low.contains("delivery") || low.contains("dostawa")) && DISTANCE.matcher(l.text).find()) {
                    deliveryLine = l;
                    break;
                }
            }
            if (deliveryLine == null) continue;

            double amount = firstDecimal(s.amount.text, AMOUNT);
            double distance = firstDecimal(deliveryLine.text, DISTANCE);
            if (amount <= 0 || distance <= 0) continue;
            double duration = parseDurationMinutes(deliveryLine.text);
            LocalTime time = parseTime(s.time.text);
            if (time == null) continue;

            // Locate the text below the route map. Uber renders "Google / Map data" at the
            // bottom of the map, so when OCR sees that footer we ignore every map label above it.
            // This prevents labels such as BYDGOSZCZ / SKRZETUSKO from becoming restaurant names.
            int contentFloorY = deliveryLine.box.bottom;
            for (OcrLine l : lines) {
                int cy = l.box.centerY();
                if (cy <= deliveryLine.box.bottom || cy >= endY) continue;
                if (isUberMapFooter(l.text)) contentFloorY = Math.max(contentFloorY, l.box.bottom + 2);
            }

            OcrLine addressLine = null;
            List<OcrLine> candidates = new ArrayList<>();
            for (OcrLine l : lines) {
                int cy = l.box.centerY();
                if (cy <= contentFloorY || cy >= endY) continue;
                if (isUberNoise(l.text) || AMOUNT.matcher(l.text).find() || TIME.matcher(l.text).find()) continue;
                if (looksLikeAddress(l.text)) {
                    if (addressLine == null) addressLine = l;
                } else if (looksLikeBusinessName(l.text)) {
                    candidates.add(l);
                }
            }

            String dropoff = addressLine == null ? "" : addressLine.text;
            String pickup = "";
            if (addressLine != null) {
                OcrLine best = null;
                int bestGap = Integer.MAX_VALUE;
                for (OcrLine l : candidates) {
                    if (l.box.centerY() >= addressLine.box.centerY()) continue;
                    int gap = addressLine.box.top - l.box.bottom;
                    if (gap >= -20 && gap < bestGap && gap < 180) {
                        bestGap = gap;
                        best = l;
                    }
                }
                if (best != null) pickup = best.text;
            }

            ParsedDelivery d = new ParsedDelivery();
            d.platform = "UBER";
            d.timestampMs = toMillis(date, time);
            d.restaurant = pickup.isEmpty() ? "Niezidentyfikowano" : pickup;
            d.pickup = pickup;
            d.dropoff = dropoff;
            d.amount = amount;
            d.distanceKm = distance;
            d.durationMin = duration;
            d.raw = zoneText(lines, startY, endY);

            if (seen.add(d.sourceKey())) out.add(d);
        }
        return out;
    }

    private static OcrLine nearestBelowMatching(List<OcrLine> lines, int fromY, int toY, Pattern p,
                                                double minCenterX, boolean enforceMinX) {
        OcrLine best = null;
        int bestY = Integer.MAX_VALUE;
        for (OcrLine l : lines) {
            int cy = l.box.centerY();
            if (cy < fromY || cy >= toY) continue;
            if (enforceMinX && l.box.centerX() < minCenterX) continue;
            if (!p.matcher(l.text).find()) continue;
            if (cy < bestY) { bestY = cy; best = l; }
        }
        return best;
    }

    private static String joinNameLines(List<OcrLine> lines) {
        StringBuilder b = new StringBuilder();
        for (OcrLine l : lines) {
            String s = cleanRestaurant(l.text);
            if (s.isEmpty() || isWoltNoise(s)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        return b.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static String zoneText(List<OcrLine> lines, int startY, int endY) {
        List<OcrLine> in = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.box.centerY() >= startY && l.box.centerY() < endY) in.add(l);
        }
        in.sort(Comparator.comparingInt((OcrLine l) -> l.box.top).thenComparingInt(l -> l.box.left));
        StringBuilder b = new StringBuilder();
        for (OcrLine l : in) {
            if (b.length() > 0) b.append("\\n");
            b.append(l.text);
        }
        return b.toString();
    }

    private static boolean isWoltNoise(String s) {
        String n = normalize(s).toLowerCase(Locale.ROOT).trim();
        return n.isEmpty()
                || n.equals("inne")
                || n.contains("pełna płatność") || n.contains("pelna platnosc")
                || n.contains("zrealizowane zamówienia") || n.contains("zrealizowane zamowienia")
                || n.contains("przychód") || n.contains("przychod")
                || n.contains("strona głów") || n.contains("strona glow")
                || n.contains("odkrywaj") || n.contains("skrzynka") || n.equals("menu")
                || n.contains("rodzaj") || n.contains("cecha") || n.contains("kompaktowy")
                || n.matches(".*\\d{1,2}\\.\\d{2}[-–]\\d{1,2}\\.\\d{2}.*")
                || n.matches("(?i).*(pon|wt|śr|sr|czw|pt|sob|nd)\\.?[, ]+\\d{1,2}\\s+(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|paź|paz|lis|gru).*")
                || n.matches("(?i)^\\d{1,2}\\s+(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|paź|paz|lis|gru)(?:\\s+\\d{4})?$");
    }

    private static boolean isUberMapFooter(String s) {
        String l = normalize(s).trim().toLowerCase(Locale.ROOT);
        return l.equals("google") || l.startsWith("map data") || l.contains("map data ©") || l.contains("map data (c)");
    }

    private static boolean isUberNoise(String s) {
        String n = normalize(s).trim();
        String l = n.toLowerCase(Locale.ROOT);
        if (l.isEmpty() || l.contains("google") || l.contains("map data") || l.contains("©")) return true;
        if (l.equals("bydgoszcz") || l.equals("bydgoszcz, pl") || l.equals("bydgosz") || l.equals("bydgosz, pl")) return true;
        if (l.contains("strona głów") || l.contains("strona glow") || l.contains("odkrywaj") || l.contains("przychód") || l.contains("przychod") || l.contains("skrzynka") || l.equals("menu")) return true;
        if (l.contains("rodzaj") || l.contains("cecha") || l.contains("kompaktowy")) return true;
        if (n.matches("^[0-9]{1,3}$")) return true;
        if (n.matches("(?i).*(pon|wt|śr|sr|czw|pt|sob|nd)\\.?[, ]+\\d{1,2}\\s+(sty|lut|mar|kwi|maj|cze|lip|sie|wrz|paź|paz|lis|gru).*") ) return true;
        int letters = 0, upper = 0;
        for (char c : n.toCharArray()) {
            if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        }
        return letters >= 4 && upper > letters * 0.88; // map district labels are often all-uppercase
    }

    private static boolean looksLikeBusinessName(String s) {
        String n = normalize(s).trim();
        if (n.length() < 3 || n.length() > 100) return false;
        if (looksLikeAddress(n) || isUberNoise(n)) return false;
        int letters = 0;
        for (char c : n.toCharArray()) if (Character.isLetter(c)) letters++;
        return letters >= 3;
    }

    private static boolean looksLikeAddress(String s) {
        String n = normalize(s).toLowerCase(Locale.ROOT);
        return n.matches(".*\\d{2}-\\d{3}.*") || n.contains(", pl")
                || n.matches(".*\\b(ul\\.|aleja|al\\.|rynek|sandomierska|urocza|gdańska|gdanska|fordońska|fordonska|dworcowa|jagiellońska|jagiellonska)\\b.*");
    }

    private static String cleanRestaurant(String s) {
        String x = clean(s);
        x = AMOUNT.matcher(x).replaceAll("");
        x = DISTANCE.matcher(x).replaceAll("");
        x = TIME.matcher(x).replaceAll("");
        return x.replaceAll("[•·|]+$", "").replaceAll("\\s{2,}", " ").trim();
    }

    private static LocalTime parseTime(String s) {
        Matcher m = TIME.matcher(normalize(s));
        if (!m.find()) return null;
        try { return LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))); }
        catch (Exception e) { return null; }
    }

    private static double firstDecimal(String s, Pattern p) {
        Matcher m = p.matcher(normalize(s));
        if (!m.find()) return 0;
        return parseDecimal(m.group(1));
    }

    private static double parseDurationMinutes(String s) {
        Matcher m = DURATION.matcher(normalize(s));
        if (!m.find()) return 0;
        try {
            int min = Integer.parseInt(m.group(1));
            int sec = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            return min + sec / 60.0;
        } catch (Exception e) { return 0; }
    }

    private static double parseDecimal(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s.replace(" ", "").replace(',', '.')); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long toMillis(LocalDate d, LocalTime t) {
        return ZonedDateTime.of(d, t, ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static int imageWidth(List<OcrLine> lines) {
        int max = 1;
        for (OcrLine l : lines) max = Math.max(max, l.box.right);
        return max;
    }

    private static int maxBottom(List<OcrLine> lines) {
        int max = 0;
        for (OcrLine l : lines) max = Math.max(max, l.box.bottom);
        return max;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').replace('–', '-').replace('—', '-');
    }

    private static String clean(String s) {
        return normalize(s).replaceAll("\\s{2,}", " ").trim();
    }

    private static final class OcrLine {
        final String text;
        final Rect box;
        OcrLine(String text, Rect box) { this.text = text; this.box = box; }
    }

    private static final class UberCardStart {
        final OcrLine amount;
        final OcrLine time;
        UberCardStart(OcrLine amount, OcrLine time) { this.amount = amount; this.time = time; }
    }
}
