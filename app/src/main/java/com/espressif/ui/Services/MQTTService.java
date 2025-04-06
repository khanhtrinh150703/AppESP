package com.espressif.ui.Services;

import android.content.Context;
import android.util.Log;
import com.espressif.ui.Data.DeviceDatabaseHelper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

public class MQTTService {
    private static final String TAG = "MQTTService";
    private static final String BROKER_URL = "192.168.1.34";
    private static final int BROKER_PORT = 1883;
    private static final String DEFAULT_TOPIC_1 = "/phone/notification"; // Topic mặc định 1
    private static final String DEFAULT_TOPIC_2 = "/speech/command";    // Topic mặc định 2
    private static MQTTService instance;
    private final Mqtt3AsyncClient client;
    private final String clientId;
    private MQTTCallback callback;
    private final DeviceDatabaseHelper dbHelper;
    private final Context context;

    // Interface để gửi dữ liệu về Activity
    public interface MQTTCallback {
        void onMessageReceived(String topic, String message);
        void onConnectionLost(Throwable cause);
        void onConnected();
    }

    // Constructor riêng tư cho Singleton
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

        // Kết nối ngay khi khởi tạo
        connect();
    }

    // Phương thức tĩnh để lấy instance
    public static synchronized MQTTService getInstance(Context context) {
        if (instance == null) {
            instance = new MQTTService(context);
        }
        return instance;
    }

    // Gán callback
    public void setCallback(MQTTCallback callback) {
        this.callback = callback;
    }

    // Kết nối MQTT và subscribe 2 topic mặc định
    public void connect() {
        if (!isConnected()) {
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
                            // Subscribe 2 topic mặc định sau khi kết nối thành công
                            subscribe(DEFAULT_TOPIC_1, MqttQos.AT_LEAST_ONCE);
                            subscribe(DEFAULT_TOPIC_2, MqttQos.AT_LEAST_ONCE);
                        }
                    });
        } else {
            Log.d(TAG, "Already connected to MQTT broker");
            if (callback != null) {
                callback.onConnected();
            }
            // Đảm bảo subscribe 2 topic mặc định nếu đã kết nối
            subscribe(DEFAULT_TOPIC_1, MqttQos.AT_LEAST_ONCE);
            subscribe(DEFAULT_TOPIC_2, MqttQos.AT_LEAST_ONCE);
        }
    }

    // Subscribe vào topic
    public void subscribe(String topic, MqttQos qos) {
        if (isConnected()) {
            client.subscribeWith()
                    .topicFilter(topic)
                    .qos(qos)
                    .callback(publish -> {
                        String message = new String(publish.getPayloadAsBytes());
                        Log.d(TAG, "Received message: " + message + " from topic: " + topic);
                        dbHelper.handleMqttMessage(message, topic);
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
        } else {
            Log.w(TAG, "Cannot subscribe to " + topic + ": MQTT not connected");
        }
    }

    // Publish tin nhắn
    public void publish(String topic, String message, MqttQos qos) {
        if (isConnected()) {
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
        } else {
            Log.w(TAG, "Cannot publish to " + topic + ": MQTT not connected");
        }
    }

    // Ngắt kết nối
    public void disconnect() {
        if (isConnected()) {
            client.disconnect()
                    .whenComplete((voidResult, throwable) -> {
                        if (throwable != null) {
                            Log.e(TAG, "Failed to disconnect: " + throwable.getMessage());
                        } else {
                            Log.d(TAG, "Disconnected from MQTT broker");
                        }
                    });
        } else {
            Log.d(TAG, "Already disconnected from MQTT broker");
        }
    }

    // Kiểm tra trạng thái kết nối
    public boolean isConnected() {
        return client.getState().isConnected();
    }
}