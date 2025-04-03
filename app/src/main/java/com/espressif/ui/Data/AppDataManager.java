package com.espressif.ui.Data;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.espressif.ui.models.ESPDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppDataManager {
    private static AppDataManager instance;
    private final List<ESPDevice> espDevices;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final String TAG = "AppDataManager";

    private AppDataManager() {
        espDevices = Collections.synchronizedList(new ArrayList<>());
    }

    public static synchronized AppDataManager getInstance() {
        if (instance == null) {
            instance = new AppDataManager();
        }
        return instance;
    }

    public void removeDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Attempted to remove device with null ID");
            return;
        }

        handler.post(() -> {
            synchronized (espDevices) {
                boolean removed = espDevices.removeIf(device ->
                        device != null && deviceId.equals(device.getDeviceId()));

                if (removed) {
                    Log.d(TAG, "Removed device: " + deviceId);
                } else {
                    Log.w(TAG, "Device not found for removal: " + deviceId);
                }
                logDeviceList();
            }
        });
    }

    public void addDevice(String deviceId, String commandTopic) {
        if (deviceId == null || commandTopic == null) {
            Log.w(TAG, "Invalid device parameters: deviceId=" + deviceId + ", topic=" + commandTopic);
            return;
        }

        handler.post(() -> {
            synchronized (espDevices) {
                if (getDeviceById(deviceId) == null) {
                    ESPDevice newDevice = new ESPDevice(deviceId, commandTopic);
                    espDevices.add(newDevice);
                    Log.d(TAG, "Added device: " + deviceId + " with topic: " + commandTopic);
                }
            }
        });
    }

    public void updateDeviceName(String deviceId, String newName) {
        if (deviceId == null || newName == null || newName.trim().isEmpty()) {
            Log.w(TAG, "Invalid parameters for renaming: deviceId=" + deviceId + ", newName=" + newName);
            return;
        }

        handler.post(() -> {
            synchronized (espDevices) {
                ESPDevice device = getDeviceById(deviceId);
                if (device != null) {
                    device.setName(newName);
                    Log.d(TAG, "Updated name for device " + deviceId + " to: " + newName);
                    logDeviceList();
                } else {
                    Log.w(TAG, "Device not found for renaming: " + deviceId);
                }
            }
        });
    }

    public ESPDevice getFirstDevice() {
        synchronized (espDevices) {
            return espDevices.isEmpty() ? null : espDevices.get(0);
        }
    }

    public List<ESPDevice> getAllDevices() {
        synchronized (espDevices) {
            return new ArrayList<>(espDevices); // Return a copy to prevent external modification
        }
    }

    public ESPDevice getDeviceById(String deviceId) {
        if (deviceId == null) return null;

        synchronized (espDevices) {
            for (ESPDevice device : espDevices) {
                if (deviceId.equals(device.getDeviceId())) {
                    return device;
                }
            }
            return null;
        }
    }

    public void setDeviceLightState(String deviceId, boolean isLightOn) {
        handler.post(() -> {
            ESPDevice device = getDeviceById(deviceId);
            if (device != null) {
                device.setLightOn(isLightOn);
                Log.d(TAG, "Set " + deviceId + " light state to: " + isLightOn);
            }
        });
    }

    public void handleMqttMessage(String topic, String message) {
        if (topic == null || message == null) {
            Log.w(TAG, "Received null topic or message");
            return;
        }

        String[] parts = topic.split("/");
        if (parts.length < 3) {
            Log.w(TAG, "Invalid topic format: " + topic);
            return;
        }

        String deviceId = parts[2];
        Log.d(TAG, "Received topic: " + topic + ", message: " + message + ", deviceId: " + deviceId);

        handler.post(() -> {
            synchronized (espDevices) {
                ESPDevice device = getDeviceById(deviceId);

                if ("deleteNVS".equals(message)) {
                    removeDevice(deviceId);
                    return;
                }

                if (device == null) {
                    addDevice(deviceId, topic);
                    device = getDeviceById(deviceId);
                }

                updateDeviceState(device, message);
                logDeviceList();
            }
        });
    }

    private void updateDeviceState(ESPDevice device, String message) {
        switch (message) {
            case "on":
                device.setLightOn(true);
                device.setRGBMode(false);
                Log.d(TAG, "Set " + device.getDeviceId() + " to ON (Single mode)");
                break;
            case "off":
                device.setLightOn(false);
                device.setRGBMode(false);
                Log.d(TAG, "Set " + device.getDeviceId() + " to OFF (Single mode)");
                break;
            case "onRGB":
                device.setLightOn(true);
                device.setRGBMode(true);
                Log.d(TAG, "Set " + device.getDeviceId() + " to ON (RGB mode)");
                break;
            case "offRGB":
                device.setLightOn(false);
                device.setRGBMode(true);
                Log.d(TAG, "Set " + device.getDeviceId() + " to OFF (RGB mode)");
                break;
            case "deleteNVS":
                removeDevice(device.getDeviceId());
                Log.d(TAG, "Delete " + device.getDeviceId());
                break;
            default:
                Log.w(TAG, "Unhandled message type: " + message);
                break;
        }
    }

    private void logDeviceList() {
        Log.d(TAG, "Current device count: " + espDevices.size());
        for (ESPDevice d : espDevices) {
            Log.d(TAG, "Device: " + d.getDeviceId() +
                    ", Name: " + d.getName() +
                    ", Topic: " + d.getCommandTopic() +
                    ", State: " + d.isLightOn() +
                    ", RGB: " + d.isRGBMode());
        }
    }
}