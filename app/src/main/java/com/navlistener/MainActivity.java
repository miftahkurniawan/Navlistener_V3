package com.navlistener;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME  = "NavListenerPrefs";
    private static final String KEY_CHANNEL = "channelId";
    private static final String KEY_BROKER  = "brokerUri";

    private EditText  etChannel, etBroker;
    private Button    btnConnect, btnPermNotif, btnPermAccess;
    private TextView  tvMqttStatus, tvNotifStatus, tvAccessStatus;
    private TextView  tvManeuver, tvDistance, tvStreet, tvEta, tvRemain, tvTxCount;
    private TextView  tvNextManeuver, tvNextStreet, tvSource;
    private View      dotMqtt, dotNotif, dotAccess, dotNav;
    private ImageView imgArrow, imgNextArrow;
    private View      cardNav;

    private MqttManager       mqttManager;
    private SharedPreferences prefs;

    // Terima broadcast dari kedua service
    private final BroadcastReceiver navReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (NavListenerService.ACTION_NAV_UPDATE.equals(action)
                    || NavAccessibilityService.ACTION_NAV_UPDATE.equals(action)) {
                String source = intent.getStringExtra("source");
                onNavUpdate(intent.getStringExtra("json"), source);
            } else if (NavListenerService.ACTION_NAV_STOPPED.equals(action)
                    || NavAccessibilityService.ACTION_NAV_STOPPED.equals(action)) {
                onNavStopped();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bindViews();
        loadPrefs();
        setupMqtt();
        setupButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(NavListenerService.ACTION_NAV_UPDATE);
        filter.addAction(NavListenerService.ACTION_NAV_STOPPED);
        filter.addAction(NavAccessibilityService.ACTION_NAV_UPDATE);
        filter.addAction(NavAccessibilityService.ACTION_NAV_STOPPED);
        registerReceiver(navReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        updateAllStatus();
        if (NavListenerService.currentNav.active) {
            onNavUpdate(NavListenerService.currentNav.toJsonString(), "cached");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(navReceiver); } catch (Exception ignored) {}
    }

    private void bindViews() {
        etChannel       = findViewById(R.id.etChannel);
        etBroker        = findViewById(R.id.etBroker);
        btnConnect      = findViewById(R.id.btnConnect);
        btnPermNotif    = findViewById(R.id.btnPermission);
        btnPermAccess   = findViewById(R.id.btnPermAccess);
        tvMqttStatus    = findViewById(R.id.tvMqttStatus);
        tvNotifStatus   = findViewById(R.id.tvListenerStatus);
        tvAccessStatus  = findViewById(R.id.tvAccessStatus);
        tvManeuver      = findViewById(R.id.tvManeuver);
        tvDistance      = findViewById(R.id.tvDistance);
        tvStreet        = findViewById(R.id.tvStreet);
        tvEta           = findViewById(R.id.tvEta);
        tvRemain        = findViewById(R.id.tvRemain);
        tvTxCount       = findViewById(R.id.tvTxCount);
        tvNextManeuver  = findViewById(R.id.tvNextManeuver);
        tvNextStreet    = findViewById(R.id.tvNextStreet);
        tvSource        = findViewById(R.id.tvSource);
        dotMqtt         = findViewById(R.id.dotMqtt);
        dotNotif        = findViewById(R.id.dotListener);
        dotAccess       = findViewById(R.id.dotAccess);
        dotNav          = findViewById(R.id.dotNav);
        imgArrow        = findViewById(R.id.imgArrow);
        imgNextArrow    = findViewById(R.id.imgNextArrow);
        cardNav         = findViewById(R.id.cardNav);
    }

    private void loadPrefs() {
        etChannel.setText(prefs.getString(KEY_CHANNEL, "NAVKU001"));
        etBroker.setText(prefs.getString(KEY_BROKER, "tcp://broker.emqx.io:1883"));
    }

    private void setupMqtt() {
        mqttManager = MqttManager.getInstance();
        mqttManager.setStatusListener(new MqttManager.StatusListener() {
            @Override public void onConnected()    { runOnUiThread(() -> updateAllStatus()); }
            @Override public void onDisconnected() { runOnUiThread(() -> updateAllStatus()); }
            @Override public void onPublished(int count) {
                runOnUiThread(() -> { if (tvTxCount != null) tvTxCount.setText(String.valueOf(count)); });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    updateAllStatus();
                    Toast.makeText(MainActivity.this, "MQTT: " + err, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setupButtons() {
        btnPermNotif.setOnClickListener(v -> {
            if (!isNotificationListenerEnabled()) showNotifPermDialog();
            else Toast.makeText(this, "✓ Izin notifikasi sudah aktif", Toast.LENGTH_SHORT).show();
        });

        btnPermAccess.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) showAccessibilityDialog();
            else Toast.makeText(this, "✓ Accessibility sudah aktif", Toast.LENGTH_SHORT).show();
        });

        btnConnect.setOnClickListener(v -> {
            String ch = etChannel.getText().toString().trim().toUpperCase();
            String br = etBroker.getText().toString().trim();
            if (ch.isEmpty()) { etChannel.setError("Wajib diisi"); return; }
            if (br.isEmpty()) { etBroker.setError("Wajib diisi"); return; }
            prefs.edit().putString(KEY_CHANNEL, ch).putString(KEY_BROKER, br).apply();
            MqttManager.CHANNEL_ID = ch;
            if (mqttManager.isConnected()) {
                mqttManager.disconnect();
            } else {
                mqttManager.connect(br, ch);
            }
            updateAllStatus();
        });
    }

    private void updateAllStatus() {
        // MQTT
        boolean mqttOk = mqttManager.isConnected();
        tvMqttStatus.setText(mqttOk ? "Terhubung" : "Tidak terhubung");
        tvMqttStatus.setTextColor(ContextCompat.getColor(this, mqttOk ? R.color.green : R.color.red));
        dotMqtt.setBackgroundResource(mqttOk ? R.drawable.dot_green : R.drawable.dot_red);
        btnConnect.setText(mqttOk ? "DISCONNECT" : "CONNECT");

        // Notification listener
        boolean notifOk = isNotificationListenerEnabled();
        tvNotifStatus.setText(notifOk ? "Aktif (backup)" : "Tidak aktif");
        tvNotifStatus.setTextColor(ContextCompat.getColor(this, notifOk ? R.color.green : R.color.red));
        dotNotif.setBackgroundResource(notifOk ? R.drawable.dot_green : R.drawable.dot_red);
        btnPermNotif.setText(notifOk ? "✓ Izin Notifikasi" : "⚠ Beri Izin Notifikasi");

        // Accessibility
        boolean accessOk = isAccessibilityEnabled();
        tvAccessStatus.setText(accessOk ? "Aktif (real-time)" : "Tidak aktif — WAJIB untuk real-time");
        tvAccessStatus.setTextColor(ContextCompat.getColor(this, accessOk ? R.color.green : R.color.red));
        dotAccess.setBackgroundResource(accessOk ? R.drawable.dot_green : R.drawable.dot_red);
        btnPermAccess.setText(accessOk ? "✓ Accessibility" : "⚠ Aktifkan Accessibility");
    }

    private void onNavUpdate(String jsonStr, String source) {
        if (jsonStr == null) return;
        try {
            JSONObject j = new JSONObject(jsonStr);
            boolean active = j.optInt("a", 0) == 1;
            cardNav.setVisibility(active ? View.VISIBLE : View.GONE);
            dotNav.setBackgroundResource(active ? R.drawable.dot_green : R.drawable.dot_gray);
            if (!active) return;

            String maneuver    = j.optString("m", "straight");
            float  dist        = (float) j.optDouble("d", 0);
            String unit        = j.optString("u", "m");
            String street      = j.optString("s", "---");
            String eta         = j.optString("e", "--:--");
            float  remain      = (float) j.optDouble("r", 0);
            String remUnit     = j.optString("ru", "km");
            String nextMan     = j.optString("nm", "straight");
            String nextStreet  = j.optString("ns", "");

            NavState ns = new NavState();
            ns.maneuver = maneuver;
            ns.nextManeuver = nextMan;

            tvManeuver.setText(ns.maneuverLabel());
            tvDistance.setText("km".equals(unit)
                ? String.format("%.1f km", dist)
                : String.format("%.0f m", dist));
            tvStreet.setText(street);
            tvEta.setText("ETA " + eta);
            tvRemain.setText(("km".equals(remUnit)
                ? String.format("%.1f km", remain)
                : String.format("%.0f m", remain)) + " lagi");

            // Next maneuver
            boolean hasNext = !nextMan.equals("straight") && !nextMan.isEmpty();
            View nextCard = findViewById(R.id.cardNext);
            if (nextCard != null) nextCard.setVisibility(hasNext ? View.VISIBLE : View.GONE);
            if (tvNextManeuver != null) tvNextManeuver.setText("Lalu: " + ns.nextManeuverLabel());
            if (tvNextStreet  != null) tvNextStreet.setText(nextStreet.isEmpty() ? "---" : nextStreet);

            // Source indicator
            if (tvSource != null) {
                String src = "accessibility".equals(source) ? "🔴 LIVE" : "📬 Notif";
                tvSource.setText(src);
            }

            updateArrow(maneuver, imgArrow);
            if (imgNextArrow != null) updateArrow(nextMan, imgNextArrow);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onNavStopped() {
        cardNav.setVisibility(View.GONE);
        dotNav.setBackgroundResource(R.drawable.dot_gray);
    }

    private void updateArrow(String maneuver, ImageView iv) {
        if (iv == null) return;
        int resId;
        if (maneuver == null) maneuver = "straight";
        switch (maneuver) {
            case "turn-right":
            case "turn-sharp-right":
            case "fork-right":        resId = R.drawable.ic_arrow_right; break;
            case "turn-left":
            case "turn-sharp-left":
            case "fork-left":         resId = R.drawable.ic_arrow_left; break;
            case "turn-slight-right": resId = R.drawable.ic_arrow_slight_right; break;
            case "turn-slight-left":  resId = R.drawable.ic_arrow_slight_left; break;
            case "uturn-left":
            case "uturn-right":       resId = R.drawable.ic_arrow_uturn; break;
            case "roundabout-left":
            case "roundabout-right":  resId = R.drawable.ic_arrow_roundabout; break;
            case "destination":       resId = R.drawable.ic_destination; break;
            default:                  resId = R.drawable.ic_arrow_up; break;
        }
        iv.setImageResource(resId);
    }

    /* ─── PERMISSION CHECKS ──────────────────────────────────────── */

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName() + "/." + "NavAccessibilityService");
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }
    }

    private void showNotifPermDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Izin Baca Notifikasi")
            .setMessage("Diperlukan untuk menerima data navigasi saat Google Maps di background.\n\n"
                + "Cari \"NavListener\" dan aktifkan.")
            .setPositiveButton("Buka Pengaturan", (d, w) ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .setNegativeButton("Batal", null)
            .show();
    }

    private void showAccessibilityDialog() {
        new AlertDialog.Builder(this)
            .setTitle("⭐ Aktifkan Accessibility (Real-Time)")
            .setMessage("Ini adalah fitur UTAMA yang memungkinkan app membaca data navigasi "
                + "secara real-time langsung dari layar Google Maps.\n\n"
                + "Jarak akan diupdate setiap 1-3 detik, sama seperti yang tampil di Maps.\n\n"
                + "Caranya:\n"
                + "1. Buka Pengaturan Aksesibilitas\n"
                + "2. Cari \"NavListener\"\n"
                + "3. Aktifkan\n\n"
                + "⚠ App hanya membaca data dari Google Maps, tidak dari app lain.")
            .setPositiveButton("Buka Pengaturan", (d, w) ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Batal", null)
            .show();
    }
}
