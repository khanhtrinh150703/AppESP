package com.espressif.ui.Services;

import android.content.Context;
import android.util.Log;
import com.espressif.ui.Data.DeviceDatabaseHelper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

public class MQTTService {
    private static final String TAG = "MQTTService";
    private static final String BROKER_URL = "172.28.31.152";
    private static final int BROKER_PORT = 1883;
    private static final String NOTIFICATION_TOPIC = "/devices/notification";
    private static final String COMMAND_TOPIC = "/speech/command";
    private static volatile MQTTService instance;
    private final Mqtt3AsyncClient client;
    private final String clientId;
    private final Context context;
    private final DeviceDatabaseHelper dbHelper;
    private MQTTCallback callback;
    public static boolean init;

    public interface MQTTCallback {
        void onMessageReceived(String topic, String message);
        void onConnectionLost(Throwable cause);
        void onConnected();
    }

    private MQTTService(Context context) {
        this.context = context.getApplicationContext();
        this.clientId = "AndroidClient_" + System.currentTimeMillis();
        this.dbHelper = DeviceDatabaseHelper.getInstance(context);
        this.client = MqttClient.builder()
                .useMqttVersion3()
                .serverHost(BROKER_URL)
                .serverPort(BROKER_PORT)
                .identifier(clientId)
                .buildAsync();
        connect();
    }

    public static MQTTService getInstance(Context context) {
        if (instance == null) {
            synchronized (MQTTService.class) {
                if (instance == null) {
                    instance = new MQTTService(context);
                }
            }
        }
        return instance;
    }

    public void setCallback(MQTTCallback callback) {
        this.callback = callback;
    }

    public void connect() {
        if (isConnected()) {
            Log.d(TAG, "Already connected to MQTT broker");
            subscribeToDefaultTopics();
            if (callback != null) {
                callback.onConnected();
            }
            return;
        }

        client.connectWith()
                .send()
                .whenComplete((connAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Connection failed: " + throwable.getMessage());
                        if (callback != null) {
                            callback.onConnectionLost(throwable);
                        }
                    } else {
                        Log.d(TAG, "Connected to MQTT broker");
                        subscribeToDefaultTopics();
                        init = true;
                        if (callback != null) {
                            callback.onConnected();
                        }
                    }
                });
    }

    public void reconnect() {
        if (!isConnected()) {
            Log.d(TAG, "Attempting to reconnect to MQTT broker");
            connect();
        }
    }

    public void subscribe(String topic, MqttQos qos) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot subscribe to " + topic + ": MQTT not connected");
            return;
        }

        client.subscribeWith()
                .topicFilter(topic)
                .qos(qos)
                .callback(publish -> {
                    String message = new String(publish.getPayloadAsBytes());
                    Log.d(TAG, "Received message: " + message + " from topic: " + topic);
                    dbHelper.handleMqttMessage(topic, message);
                    if (callback != null) {
                        callback.onMessageReceived(topic, message);
                    }
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Subscription failed for " + topic + ": " + throwable.getMessage());
                    } else {
                        Log.d(TAG, "Subscribed to topic: " + topic);
                    }
                });
    }

    public void publish(String topic, String message, MqttQos qos) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot publish to " + topic + ": MQTT not connected");
            return;
        }

        client.publishWith()
                .topic(topic)
                .qos(qos)
                .payload(message.getBytes())
                .send()
                .whenComplete((publish, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Publish failed to " + topic + ": " + throwable.getMessage());
                    } else {
                        Log.d(TAG, "Published: " + message + " to " + topic);
                    }
                });
    }

    public void disconnect() {
        if (isConnected()) {
            client.disconnect()
                    .whenComplete((voidResult, throwable) -> {
                        if (throwable != null) {
                            Log.e(TAG, "Disconnect failed: " + throwable.getMessage());
                        } else {
                            Log.d(TAG, "Disconnected from MQTT broker");
                        }
                    });
        } else {
            Log.d(TAG, "Already disconnected from MQTT broker");
        }
    }

    public boolean isConnected() {
        return client.getState().isConnected();
    }

    private void subscribeToDefaultTopics() {
        subscribe(NOTIFICATION_TOPIC, MqttQos.AT_LEAST_ONCE);
        subscribe(COMMAND_TOPIC, MqttQos.AT_LEAST_ONCE);
    }
}