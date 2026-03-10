package com.navlistener;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NavAccessibilityService
 *
 * Membaca UI Google Maps secara real-time menggunakan AccessibilityService.
 * Ini jauh lebih akurat dibanding NotificationListenerService karena:
 *  - Jarak diupdate setiap 1-3 detik (seperti di layar Maps)
 *  - Dapat membaca next maneuver yang tampil di UI Maps
 *  - Dapat membaca ETA & jarak sisa yang terus berubah
 *  - Bekerja meskipun Maps tidak mengirim notifikasi
 *
 * Cara kerja:
 *  Google Maps menampilkan navigasi dengan komponen UI yang bisa dibaca
 *  via AccessibilityNodeInfo. Service ini melakukan "crawl" tree node
 *  setiap kali ada perubahan konten, lalu memparse teks yang ditemukan.
 *
 * Node yang dicari di Google Maps:
 *  - Distance node: "200 m", "1,2 km" — teks besar di bagian atas
 *  - Maneuver desc: ContentDescription icon ("Turn right", "Belok kanan")
 *  - Street name: teks nama jalan di bawah jarak
 *  - Next maneuver: preview belokan berikutnya
 *  - ETA bar: "Tiba pk 14:30 · 2,3 km" di bagian bawah
 */
public class NavAccessibilityService extends AccessibilityService {

    private static final String TAG      = "NavAccess";
    private static final String MAPS_PKG = "com.google.android.apps.maps";

    // Broadcast action
    public static final String ACTION_NAV_UPDATE  = "com.navlistener.ACC_NAV_UPDATE";
    public static final String ACTION_NAV_STOPPED = "com.navlistener.ACC_NAV_STOPPED";

    // Flag status service
    public static volatile boolean isRunning = false;

    // State terakhir — hindari publish duplikat
    private String lastDistStr  = "";
    private String lastManeuver = "";
    private String lastStreet   = "";
    private long   lastPublish  = 0;

    // Throttle: publish max setiap 800ms agar tidak spam MQTT
    private static final long THROTTLE_MS = 800;

    // Timeout: jika tidak ada data Maps selama 12 detik, kirim stop
    private static final long NAV_TIMEOUT = 12000;
    private long lastDataTime = 0;

    private MqttManager mqttManager;
    private Handler     handler;
    private Runnable    timeoutChecker;

    /* ─── LIFECYCLE ─────────────────────────────────────────────── */

    @Override
    public void onServiceConnected() {
        isRunning   = true;
        mqttManager = MqttManager.getInstance();
        handler     = new Handler(Looper.getMainLooper());
        startTimeoutChecker();
        Log.d(TAG, "AccessibilityService connected — monitoring Google Maps");
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (handler != null && timeoutChecker != null) {
            handler.removeCallbacks(timeoutChecker);
        }
        Log.d(TAG, "AccessibilityService destroyed");
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted");
    }

    /* ─── EVENT HANDLER ─────────────────────────────────────────── */

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Hanya proses event dari Google Maps
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().equals(MAPS_PKG)) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        // Throttle
        long now = System.currentTimeMillis();
        if (now - lastPublish < THROTTLE_MS) return;

        // Ambil root window
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            parseGoogleMapsUI(root, now);
        } finally {
            root.recycle();
        }
    }

    /* ─── PARSER UTAMA ──────────────────────────────────────────── */

    private void parseGoogleMapsUI(AccessibilityNodeInfo root, long now) {

        // Kumpulkan semua teks dan content description dari seluruh node tree
        List<String> allTexts    = new ArrayList<>();
        List<String> allDescriptions = new ArrayList<>();
        collectNodeData(root, allTexts, allDescriptions);

        Log.d(TAG, "=== Maps UI nodes ===");
        Log.d(TAG, "Texts: " + allTexts);
        Log.d(TAG, "Descs: " + allDescriptions);

        // ── 1. Cari JARAK (wajib ada untuk deteksi nav aktif) ──────
        DistResult dist = findDistance(allTexts, allDescriptions);

        // Jika tidak ada jarak navigasi, kemungkinan Maps tidak sedang nav
        if (dist.value <= 0 && !hasNavKeyword(allTexts, allDescriptions)) {
            // Jika sebelumnya sedang nav, tunggu timeout dulu
            return;
        }

        // Update timestamp data terakhir
        lastDataTime = now;

        // ── 2. Cari MANEUVER ───────────────────────────────────────
        String maneuver = findManeuver(allTexts, allDescriptions);

        // ── 3. Cari NAMA JALAN ─────────────────────────────────────
        String street = findStreetName(allTexts, allDescriptions, maneuver, dist.raw);

        // ── 4. Cari ETA & JARAK SISA ──────────────────────────────
        EtaResult eta = findEta(allTexts, allDescriptions);

        // ── 5. Cari NEXT MANEUVER ──────────────────────────────────
        NextResult next = findNextManeuver(allTexts, allDescriptions, maneuver, dist.raw);

        // ── 6. Cek apakah data benar-benar berubah ─────────────────
        String distStr = dist.value + dist.unit;
        boolean changed = !distStr.equals(lastDistStr)
                       || !maneuver.equals(lastManeuver)
                       || !street.equals(lastStreet);

        if (!changed && now - lastPublish < 3000) return; // Publish paksa tiap 3 detik

        lastDistStr  = distStr;
        lastManeuver = maneuver;
        lastStreet   = street;
        lastPublish  = now;

        // ── 7. Buat NavState & publish ─────────────────────────────
        NavState nav = new NavState();
        nav.active       = true;
        nav.maneuver     = maneuver;
        nav.distance     = dist.value;
        nav.distUnit     = dist.unit;
        nav.street       = street;
        nav.eta          = eta.time;
        nav.remainDist   = eta.remainDist;
        nav.remainUnit   = eta.remainUnit;
        nav.nextManeuver = next.maneuver;
        nav.nextStreet   = next.street;
        nav.nextDistance = next.distance;

        NavListenerService.currentNav = nav;

        Log.d(TAG, "PUBLISH: " + maneuver + " " + dist.value + dist.unit
                 + " | " + street + " | ETA:" + eta.time
                 + " | Next:" + next.maneuver);

        mqttManager.publish(nav.toJson());

        Intent bc = new Intent(ACTION_NAV_UPDATE);
        bc.putExtra("json", nav.toJsonString());
        bc.putExtra("source", "accessibility");
        sendBroadcast(bc);
    }

    /* ─── NODE TREE COLLECTOR ───────────────────────────────────── */

    /**
     * Rekursif kumpulkan semua teks & content description dari node tree.
     * Dibatasi kedalaman 12 untuk performa.
     */
    private void collectNodeData(AccessibilityNodeInfo node,
                                  List<String> texts,
                                  List<String> descs) {
        collectNodeDataRecursive(node, texts, descs, 0);
    }

    private void collectNodeDataRecursive(AccessibilityNodeInfo node,
                                           List<String> texts,
                                           List<String> descs,
                                           int depth) {
        if (node == null || depth > 12) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (text != null && text.length() > 0) {
            texts.add(text.toString().trim());
        }
        if (desc != null && desc.length() > 0) {
            String d = desc.toString().trim();
            if (!texts.contains(d)) descs.add(d);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodeDataRecursive(child, texts, descs, depth + 1);
                child.recycle();
            }
        }
    }

    /* ─── DISTANCE FINDER ───────────────────────────────────────── */

    static class DistResult {
        float  value = 0;
        String unit  = "m";
        String raw   = "";
    }

    /**
     * Cari jarak navigasi dari daftar teks.
     *
     * Google Maps menampilkan jarak dalam format:
     *  - "200 m"  / "200m"
     *  - "1,2 km" / "1.2 km" / "1 km"
     *  - ContentDescription: "Dalam 200 meter", "In 200 meters", "200 m lagi"
     */
    private DistResult findDistance(List<String> texts, List<String> descs) {
        DistResult r = new DistResult();

        // Pattern: angka (dengan titik/koma) diikuti m atau km
        Pattern pShort = Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)\\s*(km|m)$",
            Pattern.CASE_INSENSITIVE
        );

        // Cek teks pendek dulu (kemungkinan field jarak langsung)
        for (String t : texts) {
            Matcher m = pShort.matcher(t.trim());
            if (m.matches()) {
                try {
                    r.value = Float.parseFloat(m.group(1).replace(',', '.'));
                    r.unit  = m.group(2).toLowerCase();
                    r.raw   = t;
                    return r;
                } catch (NumberFormatException ignored) {}
            }
        }

        // Cek descriptions dengan konteks navigasi
        Pattern pCtx = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?:\\s+(?:lagi|tersisa|remaining|meters?|kilometers?))?",
            Pattern.CASE_INSENSITIVE
        );

        List<String> allSources = new ArrayList<>(texts);
        allSources.addAll(descs);

        for (String t : allSources) {
            // Prioritas: teks yang mengandung kata navigasi
            String tl = t.toLowerCase();
            if (!tl.contains("dalam") && !tl.contains("in ")
                && !tl.contains("lagi") && !tl.contains("remaining")
                && !tl.contains("belok") && !tl.contains("turn")) continue;

            Matcher m = pCtx.matcher(t);
            if (m.find()) {
                try {
                    r.value = Float.parseFloat(m.group(1).replace(',', '.'));
                    r.unit  = m.group(2).toLowerCase();
                    r.raw   = m.group(0);
                    return r;
                } catch (NumberFormatException ignored) {}
            }
        }

        return r;
    }

    /* ─── MANEUVER FINDER ───────────────────────────────────────── */

    /**
     * Cari maneuver dari ContentDescription icon di Google Maps.
     * Maps menggunakan accessibility description untuk ikon arah seperti:
     *  - "Belok kanan" / "Turn right"
     *  - "Belok kiri" / "Turn left"
     *  - "Lurus" / "Continue straight" / "Head straight"
     *  - "Balik arah" / "Make a U-turn"
     *  - "Masuk bundaran" / "Enter the roundabout"
     *  - "Tiba di tujuan" / "Arrive at destination"
     */
    private String findManeuver(List<String> texts, List<String> descs) {
        List<String> allSources = new ArrayList<>(descs);
        allSources.addAll(texts);

        for (String s : allSources) {
            String maneuver = parseManeuverText(s);
            if (!maneuver.equals("straight") || isExplicitStraight(s)) {
                return maneuver;
            }
        }
        return "straight";
    }

    private boolean isExplicitStraight(String s) {
        String t = s.toLowerCase();
        return t.contains("lurus") || t.contains("straight") || t.contains("continue")
            || t.contains("head") || t.contains("terus");
    }

    private String parseManeuverText(String text) {
        if (text == null) return "straight";
        String t = text.toLowerCase();

        // Destination
        if (t.contains("tiba") || t.contains("arrive") || t.contains("destination")
                || t.contains("sampai") || t.contains("tujuan"))
            return "destination";

        // U-turn
        if (t.contains("balik arah") || t.contains("u-turn") || t.contains("putar balik")
                || t.contains("make a u"))
            return "uturn-left";

        // Roundabout
        if (t.contains("bundaran") || t.contains("roundabout") || t.contains("rotary")) {
            return (t.contains("kanan") || t.contains("right"))
                    ? "roundabout-right" : "roundabout-left";
        }

        // Slight
        if ((t.contains("sedikit") || t.contains("slight") || t.contains("agak") || t.contains("keep"))
                && (t.contains("kanan") || t.contains("right"))) return "turn-slight-right";
        if ((t.contains("sedikit") || t.contains("slight") || t.contains("agak") || t.contains("keep"))
                && (t.contains("kiri") || t.contains("left"))) return "turn-slight-left";

        // Sharp
        if ((t.contains("tajam") || t.contains("sharp"))
                && (t.contains("kanan") || t.contains("right"))) return "turn-sharp-right";
        if ((t.contains("tajam") || t.contains("sharp"))
                && (t.contains("kiri") || t.contains("left"))) return "turn-sharp-left";

        // Normal turn
        if (t.contains("kanan") || t.contains("right")) return "turn-right";
        if (t.contains("kiri")  || t.contains("left"))  return "turn-left";

        // Fork
        if (t.contains("percabangan") || t.contains("fork"))
            return t.contains("kanan") ? "fork-right" : "fork-left";

        // Merge / ramp / exit
        if (t.contains("gabung") || t.contains("merge")) return "merge";
        if (t.contains("keluar") || t.contains("exit") || t.contains("ramp")) return "ramp-right";

        return "straight";
    }

    /* ─── STREET NAME FINDER ────────────────────────────────────── */

    /**
     * Cari nama jalan dari daftar teks.
     * Nama jalan biasanya:
     *  - Mengandung "Jl.", "Jalan", "Jln.", "Jl "
     *  - Atau teks yang tidak mengandung keyword navigasi
     *  - Panjang antara 3-60 karakter
     */
    private String findStreetName(List<String> texts, List<String> descs,
                                   String maneuver, String distRaw) {
        Pattern jlPattern = Pattern.compile(
            "(?:jl\\.?|jalan|jln\\.?)\\s+.+",
            Pattern.CASE_INSENSITIVE
        );

        // Cari yang mengandung prefix jalan Indonesia
        for (String t : texts) {
            if (jlPattern.matcher(t).find()) {
                return t.length() > 50 ? t.substring(0, 50) : t;
            }
        }

        // Cari dari descriptions
        for (String d : descs) {
            if (jlPattern.matcher(d).find()) {
                return d.length() > 50 ? d.substring(0, 50) : d;
            }
        }

        // Fallback: teks yang bukan jarak, bukan keyword navigasi, panjang wajar
        for (String t : texts) {
            if (t.equals(distRaw)) continue;
            if (t.length() < 4 || t.length() > 60) continue;
            String tl = t.toLowerCase();
            if (tl.matches("\\d+.*")) continue; // skip angka
            if (hasNavKeywordStrict(tl)) continue; // skip keyword nav
            // Kemungkinan nama jalan
            return t;
        }

        return "---";
    }

    private boolean hasNavKeywordStrict(String t) {
        return t.contains("belok") || t.contains("turn") || t.contains("lurus")
            || t.contains("straight") || t.contains("tiba") || t.contains("arrive")
            || t.contains("bundaran") || t.contains("balik") || t.contains("menit")
            || t.contains("tersisa") || t.contains("remaining") || t.contains("pk ")
            || t.contains("pukul") || t.contains("eta") || t.contains("dalam ")
            || t.contains("in ") || t.contains("keluar") || t.contains("merge");
    }

    /* ─── ETA FINDER ────────────────────────────────────────────── */

    static class EtaResult {
        String time = "--:--"; float remainDist = 0; String remainUnit = "km";
    }

    /**
     * Cari ETA dan jarak sisa dari panel bawah Google Maps.
     * Format umum:
     *  - "Tiba pk 14:30 · 2,3 km" (Indonesia)
     *  - "Arrive by 2:30 PM · 1.4 mi" (English)
     *  - "14:30 · 2,3 km tersisa"
     *  - "10 menit · 2,3 km"
     */
    private EtaResult findEta(List<String> texts, List<String> descs) {
        EtaResult r = new EtaResult();

        List<String> allSources = new ArrayList<>(texts);
        allSources.addAll(descs);

        Pattern timeP = Pattern.compile(
            "(?:pk\\.?|pukul|by|arrive|tiba)?\\s*(\\d{1,2}[:.](\\d{2}))\\s*(?:pm|am|wib|wita|wit)?",
            Pattern.CASE_INSENSITIVE
        );

        Pattern minP = Pattern.compile("(\\d+)\\s*(?:menit|min(?:ute)?s?)");

        Pattern remP = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi)\\s*(?:tersisa|remaining|lagi)?",
            Pattern.CASE_INSENSITIVE
        );

        for (String s : allSources) {
            String sl = s.toLowerCase();
            boolean isEtaLine = sl.contains("tiba") || sl.contains("arrive")
                             || sl.contains("pk") || sl.contains("tersisa")
                             || sl.contains("remaining") || sl.contains("menit")
                             || (sl.contains("km") && sl.contains(":"));
            if (!isEtaLine) continue;

            // ETA time
            if (r.time.equals("--:--")) {
                Matcher tm = timeP.matcher(s);
                if (tm.find()) {
                    r.time = tm.group(1).replace('.', ':');
                }
            }

            // Dari menit
            if (r.time.equals("--:--")) {
                Matcher mm = minP.matcher(sl);
                if (mm.find()) {
                    try {
                        int mnt = Integer.parseInt(mm.group(1));
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.add(java.util.Calendar.MINUTE, mnt);
                        r.time = String.format("%02d:%02d",
                            cal.get(java.util.Calendar.HOUR_OF_DAY),
                            cal.get(java.util.Calendar.MINUTE));
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Jarak sisa
            if (r.remainDist == 0) {
                Matcher rm = remP.matcher(s);
                if (rm.find()) {
                    try {
                        r.remainDist = Float.parseFloat(rm.group(1).replace(',', '.'));
                        r.remainUnit = rm.group(2).toLowerCase().equals("mi") ? "km" : rm.group(2).toLowerCase();
                        // Konversi miles ke km jika perlu
                        if (rm.group(2).equalsIgnoreCase("mi")) r.remainDist *= 1.609f;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return r;
    }

    /* ─── NEXT MANEUVER FINDER ──────────────────────────────────── */

    static class NextResult {
        String maneuver = "straight";
        String street   = "";
        float  distance = 0;
    }

    /**
     * Cari maneuver berikutnya yang ditampilkan Google Maps.
     * Biasanya muncul di pojok kanan atas saat mendekati belokan,
     * atau di panel next step.
     *
     * Strategi: cari teks yang mengandung keyword navigasi TAPI
     * bukan yang sudah dipakai sebagai maneuver utama.
     */
    private NextResult findNextManeuver(List<String> texts, List<String> descs,
                                         String currentManeuver, String currentDistRaw) {
        NextResult r = new NextResult();

        List<String> allSources = new ArrayList<>(descs);
        allSources.addAll(texts);

        int foundCount = 0;
        for (String s : allSources) {
            String sManeuver = parseManeuverText(s);
            if (sManeuver.equals("straight") && !isExplicitStraight(s)) continue;
            if (s.equals(currentDistRaw)) continue;

            // Skip jika sama dengan maneuver utama (kemungkinan duplikat node)
            if (sManeuver.equals(currentManeuver) && foundCount == 0) {
                foundCount++;
                continue;
            }

            r.maneuver = sManeuver;

            // Cari nama jalan berikutnya dari teks yang sama
            Pattern streetP = Pattern.compile(
                "(?:ke|onto|on|menuju|toward)\\s+([\\w\\s\\.]{3,35}?)(?:\\s*$|,|\\.|\\n)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher sm = streetP.matcher(s);
            if (sm.find()) r.street = sm.group(1).trim();

            // Cari jarak next
            Pattern distP = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)");
            Matcher dm = distP.matcher(s);
            if (dm.find()) {
                try { r.distance = Float.parseFloat(dm.group(1).replace(',', '.')); }
                catch (NumberFormatException ignored) {}
            }

            break;
        }

        return r;
    }

    /* ─── NAV KEYWORD CHECKER ───────────────────────────────────── */

    private boolean hasNavKeyword(List<String> texts, List<String> descs) {
        List<String> all = new ArrayList<>(texts);
        all.addAll(descs);
        String[] kw = {"belok", "turn", "lurus", "straight", "tiba", "arrive",
                        "bundaran", "roundabout", "tersisa", "remaining", "menit", "min"};
        for (String s : all) {
            String sl = s.toLowerCase();
            for (String k : kw) if (sl.contains(k)) return true;
        }
        return false;
    }

    /* ─── TIMEOUT CHECKER ───────────────────────────────────────── */

    private void startTimeoutChecker() {
        timeoutChecker = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                if (lastDataTime > 0 && now - lastDataTime > NAV_TIMEOUT
                        && NavListenerService.currentNav.active) {
                    Log.d(TAG, "Nav timeout — kirim stop signal");
                    NavState stop = new NavState();
                    stop.active = false;
                    NavListenerService.currentNav = stop;
                    mqttManager.publish(stop.toJson());
                    sendBroadcast(new Intent(ACTION_NAV_STOPPED));
                    lastDataTime = 0;
                    lastDistStr  = "";
                    lastManeuver = "";
                    lastStreet   = "";
                }
                handler.postDelayed(this, 3000); // cek tiap 3 detik
            }
        };
        handler.post(timeoutChecker);
    }
}
