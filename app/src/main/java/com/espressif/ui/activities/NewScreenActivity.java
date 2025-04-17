package com.espressif.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.splashscreen.SplashScreen;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.espressif.ui.Data.AppDataManager;
import com.espressif.ui.Data.DeviceDatabaseHelper;
import com.espressif.ui.Services.MQTTService;
import com.espressif.ui.models.ESPDevice;
import com.espressif.wifi_provisioning.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import java.util.List;

public class NewScreenActivity extends AppCompatActivity implements MQTTService.MQTTCallback {

    private static final String TAG = "NewScreenActivity";

    private DeviceDatabaseHelper dbHelper;
    private RecyclerView deviceRecyclerView;
    private static MQTTService mqttService;
    private DeviceAdapter deviceAdapter;
    private List<ESPDevice> deviceList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            DeviceDatabaseHelper.getInstance(this);
            // Add Splash Screen
            SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
            Log.d(TAG, "Splash Screen installed");

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_new_screen);
            Log.d(TAG, "Layout set");

            mqttService = MQTTService.getInstance(this);
            mqttService.setCallback(new MQTTService.MQTTCallback() {
                @Override
                public void onMessageReceived(String topic, String message) {
                    // Handle incoming messages if needed
                    Log.d(TAG, "Message received on topic: " + topic + ", message: " + message);
                }

                @Override
                public void onConnectionLost(Throwable cause) {
                    Log.e(TAG, "MQTT connection lost: " + cause.getMessage());

                }

                @Override
                public void onConnected() {
                    Log.d(TAG, "MQTT connected successfully");
                    // Optionally update UI or notify user of successful connection
                }
            });

            if (!MQTTService.init) {
                showMQTTConnectionErrorDialog();
            }

            // Get device list
            DeviceDatabaseHelper dbHelper = new DeviceDatabaseHelper(this);
            deviceList = dbHelper.getAllDevices();

            if (deviceList == null || deviceList.isEmpty()) {
                Log.w(TAG, "No devices found, showing empty state");
                Toast.makeText(this, "No devices available. Add a new device!", Toast.LENGTH_LONG).show();
                // Not calling finish(), allowing user to press FAB
            } else {
                // Handle when device list is available
            }

            // Setup RecyclerView
            deviceRecyclerView = findViewById(R.id.deviceRecyclerView);
            deviceRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
            if (deviceList != null && !deviceList.isEmpty()) {
                deviceAdapter = new DeviceAdapter(this, deviceList, mqttService);
                deviceRecyclerView.setAdapter(deviceAdapter);
            } else {
                // If no devices, RecyclerView will be empty
                deviceRecyclerView.setAdapter(null);
            }
            Log.d(TAG, "RecyclerView initialized");

            // Setup FAB
            FloatingActionButton fabAdd = findViewById(R.id.fabAdd);
            fabAdd.setOnClickListener(v -> {
                Toast.makeText(this, "Add button pressed!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(this, AddDeviceActivity.class);
                startActivity(intent);
            });
            Log.d(TAG, "FAB initialized");

            // Keep Splash Screen until UI is ready
            splashScreen.setKeepOnScreenCondition(() -> {
                Log.d(TAG, "Splash Screen still visible");
                return deviceRecyclerView.getAdapter() == null && (deviceList != null && !deviceList.isEmpty());
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
            showMQTTConnectionErrorDialog();
        }
    }

    // Method to show MQTT connection error dialog
    private void showMQTTConnectionErrorDialog() {
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Cannot show dialog: Activity is finishing or destroyed");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Connection Error")
                .setMessage("Failed to connect to MQTT server. Please check your network and try again.")
                .setPositiveButton("Retry", (dialog, which) -> {
                    try {
                        mqttService.reconnect();
                        if (mqttService.isConnected()) {
                            Toast.makeText(this, "MQTT connected!", Toast.LENGTH_SHORT).show();
                        } else {
                            // Delay retry to avoid rapid dialog re-display
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                    this::showMQTTConnectionErrorDialog,
                                    1000
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Reconnection failed: " + e.getMessage(), e);
                        showMQTTConnectionErrorDialog();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }
    @Override
    public void onMessageReceived(String topic, String message) {
        runOnUiThread(() -> {
//            mqttService.subscribe(topic, MqttQos.AT_LEAST_ONCE);
//            AppDataManager.getInstance().handleMqttMessage(topic, message);
//            Log.d(TAG, "Received: AA " + message + " from " + topic);
        });
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        Log.e(TAG, "Connection lost: " + cause.getMessage());
        runOnUiThread(this::showMQTTConnectionErrorDialog);
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