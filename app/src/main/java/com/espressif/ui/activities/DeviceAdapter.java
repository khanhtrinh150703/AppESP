package com.espressif.ui.activities;

import android.content.Context;
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
import androidx.appcompat.app.AlertDialog;
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

                    dbHelper.updateDevice(device);
                    updateDeviceUI(holder, device);
                    Log.d(TAG, "Publishing after toggle: " + message + " to " + topic);
                    publishMqttMessage(topic, message);
                    return true;
                } else if ("Rename".equals(item.getTitle())) {
                    Log.d(TAG, "Rename clicked for device: " + device.getDeviceId());
                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setTitle("Rename Device");

                    final EditText input = new EditText(context);
                    input.setText(device.getName());
                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            Log.d(TAG, "Renaming device " + device.getDeviceId() + " to newName");
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

                    ESPDevice deviceToDelete = deviceList.get(pos);
                    showDeleteConfirmationDialog(deviceToDelete, pos);
                    return true;
                }
                return false;
            });
            popupMenu.show();
        });
    }

    private void showDeleteConfirmationDialog(ESPDevice device, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Confirm Deletion");
        builder.setMessage("Are you sure you want to delete device \"" + device.getName() + "\"?");
        builder.setPositiveButton("Yes, Delete", (dialog, which) -> {
            Log.d(TAG, "Delete confirmed for device: " + device.getDeviceId());
            String topic = device.getCommandTopic();
            String message = "deleteNVS";

            // Xóa khỏi database và gửi MQTT message
            dbHelper.removeDevice(device.getDeviceId());
            publishMqttMessage(topic, message);

            // Cập nhật UI
            handler.post(() -> {
                if (position < deviceList.size()) {
                    deviceList.remove(position);
                    notifyItemRemoved(position);
                    notifyItemRangeChanged(position, deviceList.size());
                }
            });
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "Delete cancelled for device: " + device.getDeviceId());
            dialog.dismiss();
        });
        AlertDialog dialog = builder.create();
        dialog.show();

        // Tùy chỉnh giao diện dialog (tùy chọn)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(context.getResources().getColor(android.R.color.holo_red_light));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(context.getResources().getColor(android.R.color.white));
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
                    if ("deleteNVS".equals(message)) {
                        deviceList.remove(i);
                        notifyItemRemoved(i);
                        Log.d(TAG, "Removed device from UI: " + device.getDeviceId());
                    } else {
                        ESPDevice updatedDevice = dbHelper.getDeviceById(device.getDeviceId());
                        if (updatedDevice != null) {
                            if (updatedDevice.isLightOn() != device.isLightOn() ||
                                    updatedDevice.isRGBMode() != device.isRGBMode() ||
                                    !updatedDevice.getName().equals(device.getName())) {
                                deviceList.set(i, updatedDevice);
                                notifyItemChanged(i);
                                Log.d(TAG, "Updated UI for device: " + device.getDeviceId() +
                                        ", LightOn: " + updatedDevice.isLightOn() +
                                        ", RGBMode: " + updatedDevice.isRGBMode() +
                                        ", Name: " + updatedDevice.getName());
                            }
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

    private void updateDeviceUI(DeviceViewHolder holder, ESPDevice device) {
        holder.lightImageView.setImageResource(device.isLightOn() ?
                R.drawable.ic_light_on : R.drawable.ic_light_off);
        holder.lightImageView.clearColorFilter();
        holder.deviceNameTextView.setText(device.getName());
        holder.cardView.setSelected(device.isRGBMode());

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