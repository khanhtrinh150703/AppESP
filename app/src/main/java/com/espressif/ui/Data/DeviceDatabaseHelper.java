package com.espressif.ui.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.espressif.ui.models.ESPDevice;

import java.util.ArrayList;
import java.util.List;

public class DeviceDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "devices.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_DEVICES = "devices";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_DEVICE_ID = "device_id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_COMMAND_TOPIC = "command_topic";
    public static final String COLUMN_IS_LIGHT_ON = "is_light_on";
    public static final String COLUMN_IS_RGB_MODE = "is_rgb_mode";

    private static final String DEFAULT_TOPIC_1 = "/phone/notification"; // Topic mặc định 1
    private static final String DEFAULT_TOPIC_2 = "/speech/command";    // Topic mặc định 2
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static DeviceDatabaseHelper instance;
    private static final String TAG = "DeviceDatabaseHelper";

    private static final String TABLE_CREATE =
            "CREATE TABLE " + TABLE_DEVICES + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_DEVICE_ID + " TEXT, " +
                    COLUMN_NAME + " TEXT, " +
                    COLUMN_COMMAND_TOPIC + " TEXT, " +
                    COLUMN_IS_LIGHT_ON + " INTEGER, " +
                    COLUMN_IS_RGB_MODE + " INTEGER" +
                    ");";

    public static synchronized DeviceDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized DeviceDatabaseHelper getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DeviceDatabaseHelper is not initialized. Call initInstance(context) first.");
        }
        return instance;
    }

    public DeviceDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(TABLE_CREATE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_DEVICES);
        onCreate(db);
    }

    public void handleMqttMessage(String topic, String message) {
        if (topic == null || message == null) {
            Log.w(TAG, "Received null topic or message");
            return;
        }

        Log.d(TAG, "Received topic: " + topic + ", message: " + message);

        handler.post(() -> {
            if ("deleteNVS".equals(message)) {
                // Trường hợp xóa thiết bị, lấy deviceId từ topic
                String[] parts = topic.split("/");
                if (parts.length >= 3) {
                    String deviceId = parts[2];
                    removeDevice(deviceId);
                } else {
                    Log.w(TAG, "Invalid topic format for deleteNVS: " + topic);
                }
                return;
            }

            if (DEFAULT_TOPIC_1.equals(topic)) {
                String[] msgParts = message.split("/");
                if (msgParts.length >= 3) {
                    String deviceId = msgParts[2];
                    ESPDevice device = getDeviceById(deviceId);
                    if (device == null) {
                        addDevice(deviceId, message);
                    }
                } else {
                    Log.w(TAG, "Invalid message format in /phone/notification: " + message);
                }
                return;
            }

            if (DEFAULT_TOPIC_2.equals(topic)) {
                if ("turn on".equals(message)) {
                    updateStateLight(true);
                } else if ("turn off".equals(message)) {
                    updateStateLight(false);
                }
            }
        });
    }


    // Cập nhật trạng thái đèn cho tất cả thiết bị (ít dùng hơn)
    public void updateStateLight(boolean isLightOn) {
        SQLiteDatabase db = this.getWritableDatabase();
        List<ESPDevice> espDevices = getAllDevices();

        if (espDevices.isEmpty()) {
            Log.w(TAG, "No devices found in database to update");
            return; // Không đóng db
        }

        ContentValues values = new ContentValues();
        values.put(COLUMN_IS_LIGHT_ON, isLightOn ? 1 : 0);

        for (ESPDevice device : espDevices) {
            int rowsAffected = db.update(
                    TABLE_DEVICES,
                    values,
                    COLUMN_DEVICE_ID + " = ?",
                    new String[]{device.getDeviceId()}
            );

            if (rowsAffected > 0) {
                Log.d(TAG, "Updated state for device " + device.getName() + " (ID: " + device.getDeviceId() + ") to " + (isLightOn ? "ON" : "OFF"));
            } else {
                Log.w(TAG, "Failed to update state for device " + device.getName() + " (ID: " + device.getDeviceId() + ")");
            }
        }
        // Không đóng db ở đây
    }

    // Cập nhật trạng thái đèn cho một thiết bị cụ thể
    public void updateStateLight(String deviceId, int newState) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_IS_LIGHT_ON, newState);

        int rowsAffected = db.update(
                TABLE_DEVICES,
                values,
                COLUMN_DEVICE_ID + " = ?",
                new String[]{deviceId}
        );

        if (rowsAffected > 0) {
            Log.d(TAG, "Updated state for device ID " + deviceId + " to " + (newState == 1 ? "ON" : "OFF"));
        } else {
            Log.w(TAG, "Failed to update state for device ID " + deviceId);
        }
        // Không đóng db ở đây
    }

    public void addDevice(String deviceId, String commandTopic) {
        if (deviceId == null || commandTopic == null) return;

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_DEVICE_ID, deviceId);
        values.put(COLUMN_COMMAND_TOPIC, commandTopic);
        values.put(COLUMN_NAME, "ESP Device");
        values.put(COLUMN_IS_LIGHT_ON, 0);
        values.put(COLUMN_IS_RGB_MODE, 0);
        db.insertWithOnConflict(TABLE_DEVICES, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        // Không đóng db
    }

    public ESPDevice getDeviceById(String deviceId) {
        SQLiteDatabase db = this.getReadableDatabase();
        ESPDevice device = null;

        Cursor cursor = db.query(TABLE_DEVICES,
                null,
                COLUMN_DEVICE_ID + " = ?",
                new String[]{deviceId},
                null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            String id = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_ID));
            String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
            String topic = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_COMMAND_TOPIC));
            boolean isLightOn = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_LIGHT_ON)) == 1;
            boolean isRGBMode = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_RGB_MODE)) == 1;

            device = new ESPDevice(id, topic);
            device.setName(name);
            device.setLightOn(isLightOn);
            device.setRGBMode(isRGBMode);
        }

        if (cursor != null) {
            cursor.close();
        }
        // Không đóng db
        return device;
    }

    public boolean deleteDeviceById(String deviceId) {
        SQLiteDatabase db = this.getWritableDatabase();
        int deletedRows = db.delete(TABLE_DEVICES, COLUMN_DEVICE_ID + " = ?", new String[]{deviceId});
        // Không đóng db
        return deletedRows > 0;
    }

    public void removeDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Attempted to remove device with null ID");
            return;
        }

        boolean removed = deleteDeviceById(deviceId);
        if (removed) {
            Log.d(TAG, "Removed device from SQLite: " + deviceId);
        } else {
            Log.w(TAG, "Device not found in SQLite for removal: " + deviceId);
        }
    }

    public boolean updateDevice(ESPDevice device) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(COLUMN_NAME, device.getName());
        values.put(COLUMN_COMMAND_TOPIC, device.getCommandTopic());
        values.put(COLUMN_IS_LIGHT_ON, device.isLightOn() ? 1 : 0);
        values.put(COLUMN_IS_RGB_MODE, device.isRGBMode() ? 1 : 0);

        int rowsAffected = db.update(
                TABLE_DEVICES,
                values,
                COLUMN_DEVICE_ID + " = ?",
                new String[]{device.getDeviceId()}
        );
        // Không đóng db
        return rowsAffected > 0;
    }

    public List<ESPDevice> getAllDevices() {
        List<ESPDevice> devices = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_DEVICES, null, null, null, null, null, null);

        while (cursor.moveToNext()) {
            String deviceId = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DEVICE_ID));
            String name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
            String topic = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_COMMAND_TOPIC));
            boolean isLightOn = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_LIGHT_ON)) == 1;
            boolean isRGBMode = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_IS_RGB_MODE)) == 1;

            ESPDevice device = new ESPDevice(deviceId, topic);
            device.setName(name);
            device.setLightOn(isLightOn);
            device.setRGBMode(isRGBMode);
            devices.add(device);
        }
        cursor.close();
        // Không đóng db
        return devices;
    }
}