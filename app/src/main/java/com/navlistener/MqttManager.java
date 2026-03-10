package com.navlistener;

import android.util.Log;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MqttManager — Singleton untuk koneksi MQTT menggunakan HiveMQ client.
 * Broker default: broker.emqx.io:1883
 * Topic: navmqtt/{channelID}/data
 */
public class MqttManager {

    private static final String TAG = "MqttManager";

    public static String BROKER_HOST = "broker.emqx.io";
    public static int    BROKER_PORT = 1883;
    public static String CHANNEL_ID  = "NAVKU001";

    private static MqttManager  instance;
    private        Mqtt3AsyncClient mqttClient;
    private        boolean       connected = false;
    private        int           txCount   = 0;

    public interface StatusListener {
        void onConnected();
        void onDisconnected();
        void onPublished(int txCount);
        void onError(String error);
    }

    private StatusListener statusListener;

    public static synchronized MqttManager getInstance() {
        if (instance == null) {
            instance = new MqttManager();
        }
        return instance;
    }

    private MqttManager() {}

    public void setStatusListener(StatusListener l) {
        this.statusListener = l;
    }

    public void connect(String brokerUri, String channelId) {
        CHANNEL_ID  = channelId;
        BROKER_HOST = parseBrokerHost(brokerUri);
        BROKER_PORT = parseBrokerPort(brokerUri);
        connect();
    }

    public void connect() {
        if (mqttClient != null && mqttClient.getState().isConnected()) {
            Log.d(TAG, "Sudah terhubung");
            return;
        }

        String clientId = "navandroid_" + UUID.randomUUID().toString().substring(0, 8);

        mqttClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .serverHost(BROKER_HOST)
            .serverPort(BROKER_PORT)
            .buildAsync();

        mqttClient.connectWith()
            .cleanSession(true)
            .keepAlive(30)
            .send()
            .whenComplete((ack, err) -> {
                if (err != null) {
                    connected = false;
                    Log.e(TAG, "Connect gagal: " + err.getMessage());
                    if (statusListener != null) statusListener.onError(err.getMessage());
                } else {
                    connected = true;
                    Log.d(TAG, "MQTT terhubung ke " + BROKER_HOST + ":" + BROKER_PORT);
                    if (statusListener != null) statusListener.onConnected();
                }
            });
    }

    public void publish(org.json.JSONObject json) {
        publish(json.toString());
    }

    public void publish(String payload) {
        if (mqttClient == null || !mqttClient.getState().isConnected()) {
            Log.w(TAG, "Tidak terhubung, reconnect...");
            connect();
            return;
        }

        String topic = "navmqtt/" + CHANNEL_ID + "/data";

        mqttClient.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_MOST_ONCE)
            .payload(payload.getBytes(StandardCharsets.UTF_8))
            .send()
            .whenComplete((pubAck, err) -> {
                if (err != null) {
                    Log.e(TAG, "Publish gagal: " + err.getMessage());
                } else {
                    txCount++;
                    Log.d(TAG, "TX #" + txCount + " → " + topic);
                    if (statusListener != null) statusListener.onPublished(txCount);
                }
            });
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.getState().isConnected();
    }

    public int getTxCount() { return txCount; }

    public void disconnect() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
        connected = false;
    }

    private String parseBrokerHost(String uri) {
        try {
            String s = uri.replaceAll("tcp://", "").replaceAll("ssl://", "");
            return s.contains(":") ? s.split(":")[0] : s;
        } catch (Exception e) { return "broker.emqx.io"; }
    }

    private int parseBrokerPort(String uri) {
        try {
            String s = uri.replaceAll("tcp://", "").replaceAll("ssl://", "");
            return s.contains(":") ? Integer.parseInt(s.split(":")[1]) : 1883;
        } catch (Exception e) { return 1883; }
    }
}
