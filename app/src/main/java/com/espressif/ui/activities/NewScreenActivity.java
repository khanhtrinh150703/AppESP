package com.espressif.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.core.splashscreen.SplashScreen;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.espressif.ui.Data.AppDataManager;
import com.espressif.ui.Services.MQTTService;
import com.espressif.ui.models.ESPDevice;
import com.espressif.wifi_provisioning.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import java.util.List;

public class NewScreenActivity extends AppCompatActivity implements MQTTService.MQTTCallback {

    private static final String TAG = "NewScreenActivity";

    private RecyclerView deviceRecyclerView;
    private MQTTService mqttService;
    private DeviceAdapter deviceAdapter;
    private List<ESPDevice> deviceList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Thêm Splash Screen
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        Log.d(TAG, "Splash Screen installed");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_screen);
        Log.d(TAG, "Layout set");

        // Khởi tạo MQTT
        mqttService = new MQTTService(this);
        mqttService.connect();
        Log.d(TAG, "MQTT initialized");

        // Lấy danh sách thiết bị
        deviceList = AppDataManager.getInstance().getAllDevices();
        if (deviceList == null || deviceList.isEmpty()) {
            Log.w(TAG, "No devices found, showing empty state");
            Toast.makeText(this, "No devices available. Add a new device!", Toast.LENGTH_LONG).show();
            // Không finish(), để người dùng có thể nhấn FAB
        }

        // Thiết lập RecyclerView
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView);
        deviceRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        if (deviceList != null && !deviceList.isEmpty()) {
            deviceAdapter = new DeviceAdapter(this, deviceList, mqttService);
            deviceRecyclerView.setAdapter(deviceAdapter);
        } else {
            // Nếu không có thiết bị, RecyclerView sẽ trống
            deviceRecyclerView.setAdapter(null);
        }
        Log.d(TAG, "RecyclerView initialized");

        // Thiết lập FAB
        FloatingActionButton fabAdd = findViewById(R.id.fabAdd);
        fabAdd.setOnClickListener(v -> {
            Toast.makeText(this, "Nút thêm được nhấn!", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, AddDeviceActivity.class);
            startActivity(intent);
        });
        Log.d(TAG, "FAB initialized");

        // Giữ Splash Screen đến khi giao diện sẵn sàng
        splashScreen.setKeepOnScreenCondition(() -> {
            Log.d(TAG, "Splash Screen still visible");
            return deviceRecyclerView.getAdapter() == null && (deviceList != null && !deviceList.isEmpty());
        });
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        runOnUiThread(() -> {
            AppDataManager.getInstance().handleMqttMessage(topic, message);
            if (deviceAdapter != null) {
                deviceAdapter.notifyDataSetChanged();
            }
        });
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        Log.e(TAG, "Connection lost: " + cause.getMessage());
    }

    @Override
    public void onConnected() {
        if (deviceList != null && !deviceList.isEmpty()) {
            for (ESPDevice device : deviceList) {
                mqttService.subscribe(device.getCommandTopic(), MqttQos.AT_LEAST_ONCE);
            }
            Log.d(TAG, "MQTT subscribed to topics");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mqttService != null) {
            mqttService.disconnect();
        }
        Log.d(TAG, "Activity destroyed");
    }
}