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

    // Tên bảng và các cột
    public static final String TABLE_DEVICES = "devices";
    public static final String COLUMN_ID = "_id";
    public static final String COLUMN_DEVICE_ID = "device_id";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_COMMAND_TOPIC = "command_topic";
    public static final String COLUMN_IS_LIGHT_ON = "is_light_on";
    public static final String COLUMN_IS_RGB_MODE = "is_rgb_mode";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private static DeviceDatabaseHelper instance;

    private static final String TAG = "DeviceDatabaseHelper";
    // Câu lệnh tạo bảng

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

        String[] parts = topic.split("/");
        if (parts.length < 3) {
            Log.w(TAG, "Invalid topic format: " + topic);
            return;
        }

        String deviceId = parts[2];
        Log.d(TAG, "Received topic: " + topic + ", message: " + message + ", deviceId: " + deviceId);

        handler.post(() -> {
            if ("deleteNVS".equals(message)) {
                removeDevice(deviceId);
                return;
            }

            ESPDevice device = getDeviceById(deviceId);
            if (device == null) {
                addDevice(deviceId, topic);
            }
        });
    }

    // Phương thức chèn một thiết bị vào cơ sở dữ liệu
    public void addDevice(String deviceId, String commandTopic) {
        if (deviceId == null || commandTopic == null) return;

        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COLUMN_DEVICE_ID, deviceId);
        values.put(COLUMN_COMMAND_TOPIC, commandTopic);
        values.put(COLUMN_NAME, "ESP Device"); // Default name
        values.put(COLUMN_IS_LIGHT_ON, 0);
        values.put(COLUMN_IS_RGB_MODE, 0);
        db.insertWithOnConflict(TABLE_DEVICES, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        db.close();
    }

    public ESPDevice getDeviceById(String deviceId) {
        SQLiteDatabase db = this.getReadableDatabase();
        ESPDevice device = null;

        Cursor cursor = db.query(TABLE_DEVICES,
                null, // select all columns
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
        db.close();

        return device;
    }

    public boolean deleteDeviceById(String deviceId) {
        SQLiteDatabase db = this.getWritableDatabase();
        int deletedRows = db.delete(TABLE_DEVICES, COLUMN_DEVICE_ID + " = ?", new String[]{deviceId});
        db.close();
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

    public void removeDevice(Context context, String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Attempted to remove device with null ID");
            return;
        }

        new Thread(() -> {
            DeviceDatabaseHelper dbHelper = DeviceDatabaseHelper.getInstance(context.getApplicationContext());
            boolean removed = dbHelper.deleteDeviceById(deviceId);

            if (removed) {
                Log.d(TAG, "Removed device from SQLite: " + deviceId);
            } else {
                Log.w(TAG, "Device not found in SQLite for removal: " + deviceId);
            }
        }).start();
    }

    public boolean updateDevice(ESPDevice device) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        values.put(COLUMN_NAME, device.getName());
        values.put(COLUMN_COMMAND_TOPIC, device.getCommandTopic());
        values.put(COLUMN_IS_LIGHT_ON, device.isLightOn() ? 1 : 0);
        values.put(COLUMN_IS_RGB_MODE, device.isRGBMode() ? 1 : 0);

        int rowsAffected = db.update(
                TABLE_DEVICES,                      // Tên bảng
                values,                            // Giá trị mới
                COLUMN_DEVICE_ID + " = ?",         // WHERE clause
                new String[]{device.getDeviceId()} // Tham số cho WHERE
        );

        db.close();
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
        db.close();
        return devices;
    }
}