package com.espressif.ui.activities;

import android.content.Context;
import android.content.Intent;
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

import com.espressif.ui.Data.AppDataManager;
import com.espressif.ui.Services.MQTTService;
import com.espressif.ui.models.ESPDevice;
import com.espressif.wifi_provisioning.R;
import com.hivemq.client.mqtt.datatypes.MqttQos;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private static final String TAG = "DeviceAdapter";
    private Context context;
    private List<ESPDevice> deviceList;
    private MQTTService mqttService;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public DeviceAdapter(Context context, List<ESPDevice> devices, MQTTService mqttService) {
        this.context = context;
        this.deviceList = devices;
        this.mqttService = mqttService;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        ESPDevice device = deviceList.get(position);
        Log.d(TAG, "Binding device: " + device.getDeviceId() + ", Name: " + device.getName() + ", LightOn: " + device.isLightOn() + ", RGBMode: " + device.isRGBMode());

        holder.lightImageView.setImageResource(device.isLightOn() ? R.drawable.ic_light_on : R.drawable.ic_light_off);
        holder.deviceNameTextView.setText(device.getName());

        // Đổi màu nền của CardView dựa trên chế độ RGB
        if (device.isRGBMode()) {
            holder.cardView.setCardBackgroundColor(0xFFADD8E6); // Light Blue cho RGB
        } else {
            holder.cardView.setCardBackgroundColor(0xFFFFFFFF); // White cho Single
        }

        holder.lightImageView.setOnClickListener(v -> {
            Log.d(TAG, "Light clicked for device: " + device.getDeviceId());
            boolean newState = !device.isLightOn();
            device.setLightOn(newState);
            String topic = device.getCommandTopic();
            String message = device.isRGBMode() ? (newState ? "onRGB" : "offRGB") : (newState ? "on" : "off");
            Log.d(TAG, "Publishing: " + message + " to " + topic);
            publishMqttMessage(topic, message);
            handler.post(() -> {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(currentPosition);
                }
            });
        });

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
                    Log.d(TAG, "Publishing: " + message + " to " + topic);
                    publishMqttMessage(topic, message);
                    handler.post(() -> {
                        int currentPosition = holder.getAdapterPosition();
                        if (currentPosition != RecyclerView.NO_POSITION) {
                            notifyItemChanged(currentPosition);
                        }
                    });
                    return true;
                } else if ("Rename".equals(item.getTitle())) {
                    Log.d(TAG, "Rename clicked for device: " + device.getDeviceId());
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                    builder.setTitle("Rename Device");

                    final EditText input = new EditText(context);
                    input.setText(device.getName());
                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            Log.d(TAG, "Renaming device " + device.getDeviceId() + " from " + device.getName() + " to " + newName);
                            device.setName(newName);
                            AppDataManager.getInstance().updateDeviceName(device.getDeviceId(), newName);
                            handler.post(() -> {
                                int currentPosition = holder.getAdapterPosition();
                                if (currentPosition != RecyclerView.NO_POSITION) {
                                    notifyItemChanged(currentPosition);
                                }
                            });
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

                    AppDataManager.getInstance().removeDevice(deviceId);
                    String topic = deviceTmp.getCommandTopic();
                    String message = "deleteNVS";
                    Log.d(TAG, "Publishing: " + message + " to " + topic);
                    publishMqttMessage(topic, message);
                    handler.post(() -> {
                        if (pos < deviceList.size()) {
                            deviceList.remove(pos);
                            notifyItemRemoved(pos);
                            notifyItemRangeChanged(pos, deviceList.size());
                            Intent intent = new Intent(context, NewScreenActivity.class);
                            context.startActivity(intent);
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

    private void publishMqttMessage(String topic, String message) {
        if (mqttService != null) {
            try {
                mqttService.publish(topic, message, MqttQos.AT_LEAST_ONCE);
            } catch (Exception e) {
                Log.e(TAG, "MQTT publish failed: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "MQTTService is null");
        }
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