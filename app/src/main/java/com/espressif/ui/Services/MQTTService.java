package com.espressif.ui.Services;

import android.util.Log;

import com.espressif.ui.Data.AppDataManager;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

public class MQTTService {
    private Mqtt3AsyncClient client;
    private static final String TAG = "MQTTService";
    private static final String BROKER_URL = "172.20.10.6";
    private static final int BROKER_PORT = 1883;
    private final String clientId;
    private MQTTCallback callback;

    // Interface để gửi dữ liệu về Activity
    public interface MQTTCallback {
        void onMessageReceived(String topic, String message);
        void onConnectionLost(Throwable cause);
        void onConnected();
    }

    public MQTTService(MQTTCallback callback) {
        this.callback = callback;
        this.clientId = "AndroidClient_" + System.currentTimeMillis();
        client = MqttClient.builder()
                .useMqttVersion3()
                .serverHost(BROKER_URL)
                .serverPort(BROKER_PORT)
                .identifier(clientId)
                .buildAsync();
    }

    public void connect() {
        client.connectWith()
                .send()
                .whenComplete((connAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Failed to connect: " + throwable.getMessage());
                        if (callback != null) {
                            callback.onConnectionLost(throwable);
                        }
                    } else {
                        Log.d(TAG, "Connected to MQTT broker");
                        if (callback != null) {
                            callback.onConnected();
                        }
                    }
                });
    }

    public void subscribe(String topic, MqttQos qos) {
        client.subscribeWith()
                .topicFilter(topic)
                .qos(qos)
                .callback(publish -> {
                    String message = new String(publish.getPayloadAsBytes());
                    Log.d(TAG, "Received message: " + message + " from topic: " + topic);
                    AppDataManager.getInstance().handleMqttMessage(message, topic);
                    if (callback != null) {
                        callback.onMessageReceived(topic, message);
                    }
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Failed to subscribe to " + topic + ": " + throwable.getMessage());
                    } else {
                        Log.d(TAG, "Subscribed to topic: " + topic);
                    }
                });
    }

    public void publish(String topic, String message, MqttQos qos) {
        client.publishWith()
                .topic(topic)
                .qos(qos)
                .payload(message.getBytes())
                .send()
                .whenComplete((publish, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Failed to publish to " + topic + ": " + throwable.getMessage());
                    } else {
                        Log.d(TAG, "Published: " + message + " to " + topic);
                    }
                });
    }

    public void disconnect() {
        client.disconnect()
                .whenComplete((voidResult, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Failed to disconnect: " + throwable.getMessage());
                    } else {
                        Log.d(TAG, "Disconnected from MQTT broker");
                    }
                });
    }

    public boolean isConnected() {
        return client.getState().isConnected();
    }
}