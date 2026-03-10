package com.navlistener;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NavListenerService
 *
 * Mendengar notifikasi Google Maps dan mengekstrak:
 *  - Maneuver saat ini (belok kiri/kanan/lurus/dll)
 *  - Jarak ke belokan berikut
 *  - Nama jalan
 *  - ETA & jarak sisa
 *  - Maneuver BERIKUTNYA (next turn preview)
 *  - Nama jalan berikutnya
 *
 * Google Maps mengirim notifikasi dalam format:
 *   Title   : "Belok kanan dalam 200 m" / jarak saja / nama jalan
 *   Text    : nama jalan / instruksi
 *   BigText : teks expanded (kadang berisi 2 baris instruksi)
 *   SubText : "Tiba pk 14:30 · 2,3 km tersisa"
 *   Lines[] : array baris untuk notifikasi multi-line (berisi next maneuver)
 */
public class NavListenerService extends NotificationListenerService {

    private static final String TAG      = "NavListener";
    public  static final String MAPS_PKG = "com.google.android.apps.maps";

    public static final String ACTION_NAV_UPDATE  = "com.navlistener.NAV_UPDATE";
    public static final String ACTION_NAV_STOPPED = "com.navlistener.NAV_STOPPED";

    public static volatile NavState currentNav  = new NavState();
    public static volatile boolean  isListening = false;

    private MqttManager mqttManager;

    @Override
    public void onCreate() {
        super.onCreate();
        isListening = true;
        mqttManager = MqttManager.getInstance();
        Log.d(TAG, "Service started");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isListening = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    /* ─── NOTIFIKASI MASUK ─────────────────────────────────────── */

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MAPS_PKG.equals(sbn.getPackageName())) return;

        Notification notif  = sbn.getNotification();
        Bundle       extras = notif.extras;
        if (extras == null) return;

        // Ekstrak semua field teks
        String title    = safe(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text     = safe(extras.getCharSequence(Notification.EXTRA_TEXT));
        String bigText  = safe(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String subText  = safe(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String infoText = safe(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        // Lines array — Google Maps kadang kirim next maneuver di sini
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        String line0 = "", line1 = "", line2 = "";
        if (lines != null && lines.length > 0) {
            line0 = safe(lines[0]);
            if (lines.length > 1) line1 = safe(lines[1]);
            if (lines.length > 2) line2 = safe(lines[2]);
        }

        Log.d(TAG, "=== Maps Notification ===");
        Log.d(TAG, "Title  : " + title);
        Log.d(TAG, "Text   : " + text);
        Log.d(TAG, "Big    : " + bigText);
        Log.d(TAG, "Sub    : " + subText);
        Log.d(TAG, "Info   : " + infoText);
        Log.d(TAG, "Line0  : " + line0);
        Log.d(TAG, "Line1  : " + line1);

        // Gabung semua untuk cek apakah ini notif navigasi
        String allText = (title + " " + text + " " + bigText + " "
                        + subText + " " + line0 + " " + line1).toLowerCase();

        if (!isNavNotification(allText)) {
            Log.d(TAG, "Bukan notif navigasi, skip");
            return;
        }

        NavState nav = new NavState();
        nav.active = true;

        // ── 1. MANEUVER & JARAK utama ──────────────────────────────
        // Google Maps biasanya: title = "Belok kanan dalam 200 m"
        // atau title = "200 m" dan text = "Belok kanan ke Jl. Sudirman"
        String mainInstruction = pickMainInstruction(title, text, bigText, line0);
        nav.maneuver = detectManeuver(mainInstruction);

        DistResult dist = extractDistance(mainInstruction);
        if (dist.value == 0) dist = extractDistance(title + " " + text);
        nav.distance = dist.value;
        nav.distUnit = dist.unit;

        // ── 2. NAMA JALAN ──────────────────────────────────────────
        nav.street = extractStreetName(title, text, bigText, line0, line1, nav.maneuver);

        // ── 3. ETA & JARAK SISA ────────────────────────────────────
        EtaResult eta = extractEta(subText + " " + infoText + " " + bigText + " " + line1 + " " + line2);
        nav.eta        = eta.time;
        nav.remainDist = eta.remainDist;
        nav.remainUnit = eta.remainUnit;

        // ── 4. NEXT MANEUVER (arah berikutnya) ────────────────────
        // Google Maps expanded notification menampilkan baris ke-2 sebagai next step
        // Contoh: "Lalu belok kiri ke Jl. Thamrin"
        //         "Kemudian lurus 500 m"
        parseNextManeuver(nav, text, bigText, line1, line2, subText);

        // ── 5. Update & publish ────────────────────────────────────
        currentNav = nav;
        Log.d(TAG, "Nav: " + nav.toString());

        mqttManager.publish(nav.toJson());

        Intent bc = new Intent(ACTION_NAV_UPDATE);
        bc.putExtra("json", nav.toJsonString());
        sendBroadcast(bc);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!MAPS_PKG.equals(sbn.getPackageName())) return;
        Log.d(TAG, "Maps notif dihapus — nav selesai");
        currentNav = new NavState();
        mqttManager.publish(stopJson());
        sendBroadcast(new Intent(ACTION_NAV_STOPPED));
    }

    /* ─── PILIH INSTRUKSI UTAMA ────────────────────────────────── */

    /**
     * Tentukan mana yang berisi instruksi utama (arah + jarak).
     * Google Maps punya beberapa format:
     * - Format 1: title="Belok kanan dalam 200 m", text="Jl. Sudirman"
     * - Format 2: title="Jl. Sudirman", text="Belok kanan dalam 200 m"
     * - Format 3: title="200 m", text="Belok kanan", bigText lebih lengkap
     * - Format 4: line0 = instruksi penuh dari expanded notification
     */
    private String pickMainInstruction(String title, String text, String bigText, String line0) {
        // Prioritas: line0 jika ada dan berisi kata arah
        if (!line0.isEmpty() && hasDirectionKeyword(line0)) return line0;

        // bigText jika lebih panjang dan berisi keyword
        if (bigText.length() > title.length() && hasDirectionKeyword(bigText))
            return bigText;

        // Title jika berisi kata arah
        if (hasDirectionKeyword(title)) return title;

        // Text jika berisi kata arah
        if (hasDirectionKeyword(text)) return text;

        // Default gabungan
        return title + " " + text;
    }

    private boolean hasDirectionKeyword(String s) {
        String t = s.toLowerCase();
        return t.contains("belok") || t.contains("turn") || t.contains("lurus")
            || t.contains("straight") || t.contains("tiba") || t.contains("arrive")
            || t.contains("bundaran") || t.contains("roundabout") || t.contains("balik")
            || t.contains("uturn") || t.contains("exit") || t.contains("keluar")
            || t.contains("gabung") || t.contains("merge");
    }

    /* ─── NEXT MANEUVER PARSER ─────────────────────────────────── */

    /**
     * Cari instruksi berikutnya dari notifikasi.
     *
     * Google Maps sering menyertakan next step di:
     * - Baris kedua notifikasi (line1)
     * - BigText setelah baris pertama
     * - Text dengan prefix "lalu", "kemudian", "then"
     */
    private void parseNextManeuver(NavState nav, String text, String bigText,
                                    String line1, String line2, String subText) {

        // Coba dari line1 dulu (paling reliable)
        String nextCandidate = "";

        if (!line1.isEmpty() && hasDirectionKeyword(line1)) {
            nextCandidate = line1;
        } else if (!line2.isEmpty() && hasDirectionKeyword(line2)) {
            nextCandidate = line2;
        } else {
            // Coba extract dari bigText — cari setelah newline atau kata "lalu/then/kemudian"
            nextCandidate = extractNextFromText(bigText);
            if (nextCandidate.isEmpty()) nextCandidate = extractNextFromText(text);
        }

        if (!nextCandidate.isEmpty()) {
            nav.nextManeuver = detectManeuver(nextCandidate);
            DistResult nd = extractDistance(nextCandidate);
            nav.nextDistance = nd.value;

            // Nama jalan berikutnya — cari setelah kata "ke/onto/on"
            nav.nextStreet = extractNextStreet(nextCandidate);
        }

        Log.d(TAG, "Next: " + nav.nextManeuver + " @ " + nav.nextStreet);
    }

    /**
     * Ekstrak bagian "next instruction" dari teks yang mungkin berisi prefix
     * seperti "lalu", "kemudian", "then", "setelah itu"
     */
    private String extractNextFromText(String text) {
        if (text == null || text.isEmpty()) return "";
        String t = text.toLowerCase();

        // Cari prefix kata "berikutnya"
        String[] prefixes = {"lalu ", "kemudian ", "then ", "setelah itu ", "after that ",
                              "selanjutnya ", "next ", "\n"};
        for (String prefix : prefixes) {
            int idx = t.indexOf(prefix);
            if (idx >= 0) {
                String after = text.substring(idx + prefix.length()).trim();
                if (!after.isEmpty() && hasDirectionKeyword(after.toLowerCase())) {
                    return after;
                }
            }
        }
        return "";
    }

    private String extractNextStreet(String text) {
        // Cari nama jalan setelah kata "ke/onto/menuju"
        Pattern p = Pattern.compile(
            "(?:ke|onto|on|menuju|toward)\\s+(.{3,35}?)(?:\\s*\\d|\\s*$|,|\\.|\\n)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    /* ─── CEK NOTIFIKASI NAVIGASI ──────────────────────────────── */

    private boolean isNavNotification(String text) {
        String[] keywords = {
            "belok", "lurus", "bundaran", "tiba", "keluar", "gabung",
            "tersisa", "menit", "km tersisa",
            "turn", "straight", "arrive", "destination", "roundabout",
            "exit", "merge", "keep", "head", "continue", "ramp",
            " km ", " m ", "dalam ", "in 1", "in 2", "in 3", "in 4", "in 5"
        };
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /* ─── MANEUVER DETECTOR ────────────────────────────────────── */

    private String detectManeuver(String text) {
        if (text == null) return "straight";
        String t = text.toLowerCase();

        if (t.contains("tiba") || t.contains("arrive") || t.contains("destination")
                || t.contains("sampai") || t.contains("tujuan di"))
            return "destination";

        if (t.contains("balik") || t.contains("u-turn") || t.contains("uturn")
                || t.contains("putar balik"))
            return "uturn-left";

        if (t.contains("bundaran") || t.contains("roundabout"))
            return (t.contains("kanan") || t.contains("right"))
                    ? "roundabout-right" : "roundabout-left";

        // Slight
        boolean slight = t.contains("sedikit") || t.contains("slight") || t.contains("agak");
        if (slight && (t.contains("kanan") || t.contains("right"))) return "turn-slight-right";
        if (slight && (t.contains("kiri")  || t.contains("left")))  return "turn-slight-left";

        // Sharp
        boolean sharp = t.contains("tajam") || t.contains("sharp");
        if (sharp && (t.contains("kanan") || t.contains("right"))) return "turn-sharp-right";
        if (sharp && (t.contains("kiri")  || t.contains("left")))  return "turn-sharp-left";

        // Normal turn
        if (t.contains("kanan") || t.contains("right")) return "turn-right";
        if (t.contains("kiri")  || t.contains("left"))  return "turn-left";

        // Fork
        if (t.contains("percabangan") || t.contains("fork") || t.contains("simpang"))
            return t.contains("kanan") ? "fork-right" : "fork-left";

        // Merge / ramp
        if (t.contains("gabung") || t.contains("merge")) return "merge";
        if (t.contains("tanjakan") || t.contains("ramp")) return "ramp-right";

        // Keep / continue
        if (t.contains("tetap") || t.contains("keep") || t.contains("ikuti") || t.contains("continue"))
            return t.contains("kanan") ? "turn-slight-right"
                 : t.contains("kiri")  ? "turn-slight-left" : "straight";

        return "straight";
    }

    /* ─── JARAK EXTRACTOR ─────────────────────────────────────── */

    static class DistResult {
        float value = 0; String unit = "m";
    }

    private DistResult extractDistance(String text) {
        DistResult r = new DistResult();
        if (text == null) return r;
        Pattern p = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?:\\b|\\s|$)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                r.value = Float.parseFloat(m.group(1).replace(',', '.'));
                r.unit  = m.group(2).toLowerCase();
            } catch (NumberFormatException ignored) {}
        }
        return r;
    }

    /* ─── NAMA JALAN EXTRACTOR ─────────────────────────────────── */

    private String extractStreetName(String title, String text, String bigText,
                                      String line0, String line1, String maneuver) {
        // Field yang berisi instruksi arah tidak berisi nama jalan
        // Cari field yang TIDAK berisi keyword arah
        String[] candidates = {text, title, line0, line1, bigText};
        for (String c : candidates) {
            if (c == null || c.isEmpty()) continue;
            String cl = c.toLowerCase();
            // Skip jika mengandung kata arah utama
            if (cl.contains("belok") || cl.contains("turn") || cl.contains("lurus")
                    || cl.contains("bundaran") || cl.contains("tiba"))
                continue;
            // Skip jika terlalu pendek atau hanya angka+unit
            if (c.trim().length() < 3) continue;
            if (c.trim().matches("\\d+\\.?\\d*\\s*(m|km)")) continue;
            // Ini kemungkinan nama jalan
            String street = c.trim()
                .replaceAll("(?i)(menuju|toward|onto|on)\\s+", "")
                .replaceAll("(?i)(dalam|in)\\s+\\d+.*", "")
                .trim();
            if (street.length() >= 2) {
                return street.length() > 40 ? street.substring(0, 40) : street;
            }
        }

        // Fallback: cari nama jalan setelah kata "ke/onto" di instruksi
        String instruksi = title + " " + text;
        Pattern p = Pattern.compile(
            "(?:ke|onto|on|menuju)\\s+([\\w\\s\\.]{3,40}?)(?:\\s+dalam|\\s+in|$|,)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(instruksi);
        if (m.find()) return m.group(1).trim();

        return "---";
    }

    /* ─── ETA EXTRACTOR ────────────────────────────────────────── */

    static class EtaResult {
        String time = "--:--"; float remainDist = 0; String remainUnit = "km";
    }

    private EtaResult extractEta(String text) {
        EtaResult r = new EtaResult();
        if (text == null || text.trim().isEmpty()) return r;

        // Waktu tiba: "pk 14:30", "pukul 14:30", "14:30", "2:30 PM"
        Pattern timeP = Pattern.compile(
            "(?:pk\\.?|pukul|at)?\\s*(\\d{1,2}[:.](\\d{2}))\\s*(?:pm|am|wib|wita|wit)?",
            Pattern.CASE_INSENSITIVE
        );
        Matcher timeM = timeP.matcher(text);
        if (timeM.find()) {
            r.time = timeM.group(1).replace('.', ':');
        } else {
            // Hitung dari "X menit" / "X min"
            Pattern minP = Pattern.compile("(\\d+)\\s*(?:menit|min)");
            Matcher minM = minP.matcher(text.toLowerCase());
            if (minM.find()) {
                try {
                    int mnt = Integer.parseInt(minM.group(1));
                    Calendar cal = Calendar.getInstance();
                    cal.add(Calendar.MINUTE, mnt);
                    r.time = String.format("%02d:%02d",
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE));
                } catch (NumberFormatException ignored) {}
            }
        }

        // Jarak sisa: "2,3 km tersisa", "500 m remaining", "1.2 km lagi"
        Pattern remP = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\s*(?:tersisa|remaining|lagi)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher remM = remP.matcher(text);
        if (remM.find()) {
            try {
                r.remainDist = Float.parseFloat(remM.group(1).replace(',', '.'));
                r.remainUnit = remM.group(2).toLowerCase();
            } catch (NumberFormatException ignored) {}
        }

        return r;
    }

    /* ─── HELPERS ─────────────────────────────────────────────── */

    private String safe(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }

    private JSONObject stopJson() {
        NavState stop = new NavState();
        stop.active = false;
        return stop.toJson();
    }
}
