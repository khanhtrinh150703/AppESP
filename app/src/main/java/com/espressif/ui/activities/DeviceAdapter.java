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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.espressif.ui.Data.DeviceDatabaseHelper;
import com.espressif.ui.Services.MQTTService;
import com.espressif.ui.models.ESPDevice;
import com.espressif.wifi_provisioning.R;
import com.hivemq.client.mqtt.datatypes.MqttQos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> implements MQTTService.MQTTCallback {

    private static final String TAG = "DeviceAdapter";

    // MQTT and Menu Constants
    private static final String MQTT_DELETE_NVS = "deleteNVS";
    private static final String MQTT_ON_RGB = "onRGB";
    private static final String MQTT_OFF_RGB = "offRGB";
    private static final String MQTT_ON = "on";
    private static final String MQTT_OFF = "off";
    private static final String MENU_TOGGLE_MODE = "Toggle Mode";
    private static final String MENU_RENAME = "Rename";
    private static final String MENU_DELETE = "Delete";

    private final Context context; // Application context
    private List<ESPDevice> deviceList;
    private final MQTTService mqttService;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DeviceDatabaseHelper dbHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, ESPDevice> topicToDeviceMap = new HashMap<>();
    private final Set<String> locallyDeletedDevices = new HashSet<>();

    public DeviceAdapter(Context context, List<ESPDevice> devices, MQTTService mqttService) {
        if (mqttService == null) {
            throw new IllegalArgumentException("MQTTService cannot be null");
        }
        this.context = context.getApplicationContext(); // Use application context to avoid leaks
        this.deviceList = new ArrayList<>(devices);
        this.mqttService = mqttService;
        this.mqttService.setCallback(this);
        this.dbHelper = DeviceDatabaseHelper.getInstance(this.context);
        updateTopicMap(devices);
    }

    // Update device list with DiffUtil
    public void setDeviceList(List<ESPDevice> newDevices) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return deviceList.size();
            }

            @Override
            public int getNewListSize() {
                return newDevices.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return deviceList.get(oldItemPosition).getDeviceId()
                        .equals(newDevices.get(newItemPosition).getDeviceId());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                ESPDevice oldDevice = deviceList.get(oldItemPosition);
                ESPDevice newDevice = newDevices.get(newItemPosition);
                return oldDevice.isLightOn() == newDevice.isLightOn() &&
                        oldDevice.isRGBMode() == newDevice.isRGBMode() &&
                        oldDevice.getName().equals(newDevice.getName());
            }
        });
        deviceList = new ArrayList<>(newDevices);
        updateTopicMap(newDevices);
        diffResult.dispatchUpdatesTo(this);
    }

    private void updateTopicMap(List<ESPDevice> devices) {
        topicToDeviceMap.clear();
        for (ESPDevice device : devices) {
            topicToDeviceMap.put(device.getCommandTopic(), device);
        }
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        ESPDevice device = deviceList.get(position);
        mqttService.subscribe(device.getCommandTopic(),MqttQos.AT_LEAST_ONCE);
        Log.d(TAG, "Binding device: " + device.getDeviceId() + ", Name: " + device.getName() +
                ", LightOn: " + device.isLightOn() + ", RGBMode: " + device.isRGBMode());

        // Update initial UI
        updateDeviceUI(holder, device);

        // Light toggle event
        holder.lightImageView.setOnClickListener(v -> {
            Log.d(TAG, "Light clicked for device: " + device.getDeviceId());
            boolean newState = !device.isLightOn();
            device.setLightOn(newState);
            String topic = device.getCommandTopic();
            String message = device.isRGBMode() ? (newState ? MQTT_ON_RGB : MQTT_OFF_RGB) : (newState ? MQTT_ON : MQTT_OFF);

            executor.execute(() -> {
                try {
                    dbHelper.updateDevice(device);
                    handler.post(() -> {
                        updateDeviceUI(holder, device);
                        publishMqttMessage(topic, message);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error updating device: " + e.getMessage());
                    handler.post(() -> Toast.makeText(context, "Failed to update device", Toast.LENGTH_SHORT).show());
                }
            });
        });

        // Menu event
        holder.menuButton.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(holder.itemView.getContext(), holder.menuButton);
            popupMenu.getMenu().add(MENU_TOGGLE_MODE);
            popupMenu.getMenu().add(MENU_RENAME);
            popupMenu.getMenu().add(MENU_DELETE);
            popupMenu.setOnMenuItemClickListener(item -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return true;

                ESPDevice currentDevice = deviceList.get(pos);
                if (MENU_TOGGLE_MODE.equals(item.getTitle())) {
                    Log.d(TAG, "Toggle Mode clicked for device: " + currentDevice.getDeviceId());
                    boolean isRGB = !currentDevice.isRGBMode();
                    currentDevice.setRGBMode(isRGB);
                    String topic = currentDevice.getCommandTopic();
                    String message = isRGB ? (currentDevice.isLightOn() ? MQTT_ON_RGB : MQTT_OFF_RGB) :
                            (currentDevice.isLightOn() ? MQTT_ON : MQTT_OFF);

                    executor.execute(() -> {
                        try {
                            dbHelper.updateDevice(currentDevice);
                            handler.post(() -> {
                                updateDeviceUI(holder, currentDevice);
                                publishMqttMessage(topic, message);
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Error toggling mode: " + e.getMessage());
                            handler.post(() -> Toast.makeText(context, "Failed to toggle mode", Toast.LENGTH_SHORT).show());
                        }
                    });
                    return true;
                } else if (MENU_RENAME.equals(item.getTitle())) {
                    Log.d(TAG, "Rename clicked for device: " + currentDevice.getDeviceId());
                    AlertDialog.Builder builder = new AlertDialog.Builder(holder.itemView.getContext());
                    builder.setTitle("Rename Device");

                    final EditText input = new EditText(holder.itemView.getContext());
                    input.setText(currentDevice.getName());
                    builder.setView(input);

                    builder.setPositiveButton("OK", (dialog, which) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            Log.d(TAG, "Renaming device " + currentDevice.getDeviceId() + " to " + newName);
                            currentDevice.setName(newName);
                            executor.execute(() -> {
                                try {
                                    dbHelper.updateDevice(currentDevice);
                                    handler.post(() -> updateDeviceUI(holder, currentDevice));
                                } catch (Exception e) {
                                    Log.e(TAG, "Error renaming device: " + e.getMessage());
                                    handler.post(() -> Toast.makeText(context, "Failed to rename device", Toast.LENGTH_SHORT).show());
                                }
                            });
                        }
                    });
                    builder.setNegativeButton("Cancel", null);
                    builder.show();
                    return true;
                } else if (MENU_DELETE.equals(item.getTitle())) {
                    showDeleteConfirmationDialog(currentDevice, pos, holder.itemView.getContext());
                    return true;
                }
                return false;
            });
            popupMenu.show();
        });
    }

    private void showDeleteConfirmationDialog(ESPDevice device, int position, Context activityContext) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activityContext);
        builder.setTitle("Confirm Deletion");
        builder.setMessage("Are you sure you want to delete device \"" + device.getName() + "\"?");

        builder.setPositiveButton("Yes, Delete", (dialog, which) -> {
            Log.d(TAG, "Delete confirmed for device: " + device.getDeviceId());
            String topic = device.getCommandTopic();
            locallyDeletedDevices.add(device.getDeviceId());

            executor.execute(() -> {
                try {
                    dbHelper.removeDevice(device.getDeviceId());
                    handler.post(() -> {
                        publishMqttMessage(topic, MQTT_DELETE_NVS);
//                        mqttService.unsubscribe(topic);
                        if (position >= 0 && position < deviceList.size()) {
                            deviceList.remove(position);
                            topicToDeviceMap.remove(topic);
                            notifyItemRemoved(position);
                            notifyItemRangeChanged(position, deviceList.size());
                            Toast.makeText(context, "Device \"" + device.getName() + "\" deleted", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.w(TAG, "Invalid position: " + position + ", deviceList size: " + deviceList.size());
                        }
                        locallyDeletedDevices.remove(device.getDeviceId());
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error deleting device: " + e.getMessage());
                    locallyDeletedDevices.remove(device.getDeviceId());
                    handler.post(() -> Toast.makeText(context, "Failed to delete device", Toast.LENGTH_SHORT).show());
                }
            });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "Delete cancelled for device: " + device.getDeviceId());
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(ContextCompat.getColor(activityContext, android.R.color.holo_red_light));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(ContextCompat.getColor(activityContext, android.R.color.white));
        });
        dialog.show();
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    @Override
    public void onMessageReceived(String topic, String message) {
        Log.d(TAG, "Received: " + message + " from " + topic);
        executor.execute(() -> {
            try {


                // Cập nhật giao diện trên luồng chính
                handler.post(() -> {
                    ESPDevice device = topicToDeviceMap.get(topic);
                    if (device == null) {
                        Log.w(TAG, "No device found for topic: " + topic);
                        return;
                    }

                    int position = deviceList.indexOf(device);
                    if (position == -1) {
                        Log.w(TAG, "Device not found in list: " + device.getDeviceId());
                        return;
                    }

                    // Lấy thiết bị cập nhật từ cơ sở dữ liệu
                    ESPDevice updatedDevice = dbHelper.getDeviceById(device.getDeviceId());
                    if (updatedDevice == null) {
                        Log.e(TAG, "Updated device not found in database: " + device.getDeviceId());
                        Toast.makeText(context, "Device data not found", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Xử lý các lệnh MQTT
                    if (MQTT_DELETE_NVS.equals(message) && !locallyDeletedDevices.contains(device.getDeviceId())) {
                        deviceList.remove(position);
                        topicToDeviceMap.remove(topic);
//                        mqttService.unsubscribe(topic); // Gỡ đăng ký topic
                        notifyItemRemoved(position);
                        notifyItemRangeChanged(position, deviceList.size()); // Cập nhật chỉ số
                        Log.d(TAG, "Removed device from UI and unsubscribed: " + device.getDeviceId());
                        Toast.makeText(context, "Device \"" + device.getName() + "\" removed", Toast.LENGTH_SHORT).show();
                    } else if (MQTT_ON.equals(message) || MQTT_ON_RGB.equals(message)) {
                        updatedDevice.setLightOn(true);
                        deviceList.set(position, updatedDevice);
                        topicToDeviceMap.put(topic, updatedDevice);
                        notifyItemChanged(position);
                        Log.d(TAG, "Turned on device: " + device.getDeviceId());
                    } else if (MQTT_OFF.equals(message) || MQTT_OFF_RGB.equals(message)) {
                        updatedDevice.setLightOn(false);
                        deviceList.set(position, updatedDevice);
                        topicToDeviceMap.put(topic, updatedDevice);
                        notifyItemChanged(position);
                        Log.d(TAG, "Turned off device: " + device.getDeviceId());
                    } else {
                        // Cập nhật nếu có thay đổi
                        if (updatedDevice.isLightOn() != device.isLightOn() ||
                                updatedDevice.isRGBMode() != device.isRGBMode() ||
                                !updatedDevice.getName().equals(device.getName())) {
                            deviceList.set(position, updatedDevice);
                            topicToDeviceMap.put(topic, updatedDevice);
                            notifyItemChanged(position);
                            Log.d(TAG, "Updated UI for device: " + device.getDeviceId() +
                                    ", LightOn: " + updatedDevice.isLightOn() +
                                    ", RGBMode: " + updatedDevice.isRGBMode() +
                                    ", Name: " + updatedDevice.getName());
                        }
                    }
                    // Xử lý thông điệp trong cơ sở dữ liệu
                    dbHelper.handleMqttMessage(topic, message);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error processing MQTT message: " + e.getMessage());
                handler.post(() -> Toast.makeText(context, "Failed to process MQTT message", Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    public void onConnectionLost(Throwable cause) {
        Log.e(TAG, "Connection lost: " + cause.getMessage());
        handler.post(() -> Toast.makeText(context, "MQTT connection lost", Toast.LENGTH_SHORT).show());
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

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        for (ESPDevice device : deviceList) {
//            mqttService.unsubscribe(device.getCommandTopic());
            Log.d(TAG, "Unsubscribed from topic: " + device.getCommandTopic());
        }
        mqttService.setCallback(null);
        executor.shutdown();
    }

    private void publishMqttMessage(String topic, String message) {
        try {
            mqttService.publish(topic, message, MqttQos.AT_LEAST_ONCE);
            Log.d(TAG, "Published: " + message + " to " + topic);
        } catch (Exception e) {
            Log.e(TAG, "MQTT publish failed: " + e.getMessage());
            handler.post(() -> Toast.makeText(context, "Failed to send command", Toast.LENGTH_SHORT).show());
        }
    }

    public void updateAllDevices() {
        executor.execute(() -> {
            try {
                // Lấy danh sách thiết bị mới nhất từ cơ sở dữ liệu
                List<ESPDevice> updatedDevices = dbHelper.getAllDevices();

                // Cập nhật trên luồng chính (UI thread)
                handler.post(() -> {
                    // Cập nhật danh sách thiết bị và topic map
                    setDeviceList(updatedDevices);

                    // Thông báo cập nhật toàn bộ danh sách
                    notifyDataSetChanged();

                    // Ghi log
                    Log.d(TAG, "All devices updated, count: " + updatedDevices.size());

                    // Đảm bảo đăng ký lại các topic MQTT
                    for (ESPDevice device : updatedDevices) {
                        mqttService.subscribe(device.getCommandTopic(), MqttQos.AT_LEAST_ONCE);
                        Log.d(TAG, "Re-subscribed to topic: " + device.getCommandTopic());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error updating all devices: " + e.getMessage());
                handler.post(() -> Toast.makeText(context, "Failed to update devices", Toast.LENGTH_SHORT).show());
            }
        });
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

            if (cardView == null || lightImageView == null || deviceNameTextView == null || menuButton == null) {
                throw new IllegalStateException("Required views are missing in item_device layout");
            }
        }
    }
}