package com.espressif.ui.models;

public class ESPDevice {
    private String deviceId;     // ID duy nhất của thiết bị (ví dụ: từ MAC)
    private String name;         // Tên hiển thị của thiết bị (có thể đổi)
    private String commandTopic; // Topic để gửi lệnh (ví dụ: /devices/esp_device_XXXXXX/command)
    private boolean isLightOn;   // Trạng thái đèn
    private boolean isRGBMode;   // Chế độ Single hay RGB

    public ESPDevice(String deviceId, String commandTopic) {
        this.deviceId = deviceId;
        this.name = deviceId;    // Mặc định tên hiển thị là deviceId
        this.commandTopic = commandTopic;
        this.isLightOn = false;  // Mặc định là tắt
        this.isRGBMode = false;  // Mặc định là chế độ Single
    }

    public ESPDevice(String deviceId, String name, String commandTopic, boolean isLightOn, boolean isRGBMode){
        this.deviceId = deviceId;
        this.name = deviceId;    // Mặc định tên hiển thị là deviceId
        this.commandTopic = commandTopic;
        this.isLightOn = isLightOn;
        this.isRGBMode = isRGBMode;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommandTopic() {
        return commandTopic;
    }

    public boolean isLightOn() {
        return isLightOn;
    }

    public void setLightOn(boolean lightOn) {
        this.isLightOn = lightOn;
    }

    public boolean isRGBMode() {
        return isRGBMode;
    }

    public void setRGBMode(boolean isRGBMode) {
        this.isRGBMode = isRGBMode;
    }
}