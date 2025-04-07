package com.espressif.ui.activities;

import android.content.Context;
import android.nfc.Tag;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.espressif.ui.Data.DeviceDatabaseHelper;
import com.espressif.ui.Services.MQTTService;
import com.espressif.ui.models.ESPDevice;
import com.espressif.wifi_provisioning.R;
import com.hivemq.client.mqtt.datatypes.MqttQos;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> implements MQTTService.MQTTCallback {

    private static final String TAG = "DeviceAdapter";
    private Context context;
    private List<ESPDevice> deviceList;
    private MQTTService mqttService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DeviceDatabaseHelper dbHelper;

    public DeviceAdapter(Context context, List<ESPDevice> devices, MQTTService mqttService) {
        this.context = context;
        this.deviceList = devices;
        this.mqttService = mqttService;
        this.dbHelper = DeviceDatabaseHelper.getInstance(context);
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        mqttService.setCallback(this);
        View view = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        ESPDevice device = deviceList.get(position);
        Log.d(TAG, "Binding device: " + device.getDeviceId() + ", Name: " + device.getName() +
                ", LightOn: " + device.isLightOn() + ", RGBMode: " + device.isRGBMode());

        // Cập nhật UI ban đầu
        updateDeviceUI(holder, device);

        // Sự kiện bật/tắt đèn
        holder.lightImageView.setOnClickListener(v -> {
            Log.d(TAG, "Light clicked for device: " + device.getDeviceId());
            boolean newState = !device.isLightOn();
            device.setLightOn(newState);
            String topic = device.getCommandTopic();
            String message = device.isRGBMode() ? (newState ? "onRGB" : "offRGB") : (newState ? "on" : "off");

            // Cập nhật database trước, sau đó cập nhật UI
            dbHelper.updateDevice(device);
            updateDeviceUI(holder, device);
            Log.d(TAG, "Publishing: " + message + " to " + topic);
            publishMqttMessage(topic, message);
        });

        // Sự kiện menu
        holder.menuButton.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, holder.menuButton);
            popupMenu.getMenu().add("Toggle Mode");
            popupMenu.getMenu().add("Rename");
            popupMenu.getMenu().add("Delete");
            popupMenu.setOnMenuItemClickListener(item -> {
                if ("Toggle Mode".equals(item.getTitle())) {
                    Log.d(TAG, "Toggle Mode clicked for device: " + device.getDeviceId());
                    boolean isRGB = !device.isRGBMode();
                    device.setRGBMode(isRGB);
                    String topic = device.getCommandTopic();
                    String message = isRGB ? (device.isLightOn() ? "onRGB" : "offRGB") : (device.isLightOn() ? "on" : "off");

                    // Cập nhật database trước, sau đó cập nhật UI
                    dbHelper.updateDevice(device);
                    updateDeviceUI(holder, device);
                    Log.d(TAG, "Publishing after toggle: " + message + " to " + topic);
                    publishMqttMessage(topic, message);
                    return true;
                }else if ("Rename".equals(item.getTitle())) {
                    Log.d(TAG, "Rename clicked for device: " + device.getDeviceId());
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                    builder.setTitle("Rename Device");

                    final EditText input = new EditText(context);
                    input.setText(device.getName());
                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            Log.d(TAG, "Renaming device " + device.getDeviceId() + " to " + newName);
                            device.setName(newName);
                            dbHelper.updateDevice(device);
                            updateDeviceUI(holder, device);
                        }
                    });
                    builder.setNegativeButton("Cancel", null);
                    builder.show();
                    return true;
                } else if ("Delete".equals(item.getTitle())) {
                    int pos = holder.getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return true;

                    ESPDevice deviceTmp = deviceList.get(pos);
                    String deviceId = deviceTmp.getDeviceId();
                    Log.d(TAG, "Delete clicked for device: " + deviceId);

                    dbHelper.removeDevice(deviceId);
                    String topic = deviceTmp.getCommandTopic();
                    String message = "deleteNVS";
                    publishMqttMessage(topic, message);
                    handler.post(() -> {
                        if (pos < deviceList.size()) {
                            deviceList.remove(pos);
                            notifyItemRemoved(pos);
                            notifyItemRangeChanged(pos, deviceList.size());
                        }
                    });
                    return true;
                }
                return false;
            });
            popupMenu.show();
        });
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        Log.d(TAG, "Received: " + message + " from " + topic);
        dbHelper.handleMqttMessage(topic, message);

        handler.post(() -> {
            for (int i = 0; i < deviceList.size(); i++) {
                ESPDevice device = deviceList.get(i);
                if (topic.equals(device.getCommandTopic())) {
                    ESPDevice updatedDevice = dbHelper.getDeviceById(device.getDeviceId());
                    if (updatedDevice != null) {
                        // Chỉ cập nhật nếu trạng thái thay đổi
                        if (updatedDevice.isLightOn() != device.isLightOn() ||
                                updatedDevice.isRGBMode() != device.isRGBMode()) {
                            deviceList.set(i, updatedDevice);
                            notifyItemChanged(i);
                            Log.d(TAG, "Updated UI for device: " + device.getDeviceId() +
                                    ", LightOn: " + updatedDevice.isLightOn() +
                                    ", RGBMode: " + updatedDevice.isRGBMode());
                        }
                    }
                    break;
                }
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
                Log.d(TAG, "Subscribed to topic: " + device.getCommandTopic());
            }
        }
    }

    private void publishMqttMessage(String topic, String message) {
        if (mqttService != null) {
            try {
                mqttService.publish(topic, message, MqttQos.AT_LEAST_ONCE);
                Log.d(TAG, "Published: " + message + " to " + topic);
            } catch (Exception e) {
                Log.e(TAG, "MQTT publish failed: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "MQTTService is null");
        }
    }

    private void updateOneUI(String deviceId) {
        handler.post(() -> {
            for (int i = 0; i < deviceList.size(); i++) {
                if (deviceList.get(i).getDeviceId().equals(deviceId)) {
                    ESPDevice updatedDevice = dbHelper.getDeviceById(deviceId);
                    if (updatedDevice != null) {
                        deviceList.set(i, updatedDevice);
                        notifyItemChanged(i);
                        Log.d(TAG, "UI updated for device: " + deviceId);
                    }
                    break;
                }
            }
        });
    }

    private void updateDeviceUI(DeviceViewHolder holder, ESPDevice device) {
        // Cập nhật hình ảnh đèn dựa trên trạng thái
        holder.lightImageView.setImageResource(device.isLightOn() ?
                R.drawable.ic_light_on : R.drawable.ic_light_off);
        holder.lightImageView.clearColorFilter(); // Không áp dụng filter màu

        // Cập nhật tên thiết bị và màu nền
        holder.deviceNameTextView.setText(device.getName());
        holder.cardView.setSelected(device.isRGBMode()); // Kích hoạt selector dựa trên RGB mode

        // Log thông tin debug
        Log.d(TAG, "UI updated for device: " + device.getDeviceId() +
                ", LightOn: " + device.isLightOn() +
                ", RGBMode: " + device.isRGBMode());
    }
    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        CardView cardView;
        ImageView lightImageView;
        TextView deviceNameTextView;
        ImageButton menuButton;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            lightImageView = itemView.findViewById(R.id.lightImageView);
            deviceNameTextView = itemView.findViewById(R.id.deviceNameTextView);
            menuButton = itemView.findViewById(R.id.menuButton);

            if (cardView == null) Log.e(TAG, "cardView is null");
            if (lightImageView == null) Log.e(TAG, "lightImageView is null");
            if (deviceNameTextView == null) Log.e(TAG, "deviceNameTextView is null");
            if (menuButton == null) Log.e(TAG, "menuButton is null");
        }
    }
}