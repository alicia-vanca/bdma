package com.app.common.modules.device.dtos;

import com.app.common.modules.i18n.I18n;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSpec {

    private String batteryLevel;
    private String batteryStatus;
    private String storageTotal;
    private String storageUsed;
    private String storageUsePercent;

    public static DeviceSpec empty() {
        return new DeviceSpec();
    }

    public String batteryValueText() {
        return isBlank(batteryLevel) ? I18n.get("device.spec.unknown") : batteryLevel;
    }

    public String batterySubText() {
        return isBlank(batteryStatus) ? "" : "(" + localizeBatteryStatus(batteryStatus) + ")";
    }

    public String storageValueText() {
        return isBlank(storageUsePercent) ? I18n.get("device.spec.unknown") : storageUsePercent;
    }

    public String storageSubText() {
        if (!isBlank(storageUsed) && !isBlank(storageTotal)) {
            return storageUsed + " / " + storageTotal;
        }
        return "";
    }

    public boolean isLowBattery() {
        Integer level = parsePercent(batteryLevel);
        return level != null && level < 20;
    }

    public boolean isMaxUsage() {
        Integer usage = parsePercent(storageUsePercent);
        return usage != null && usage > 80;
    }

    private static String localizeBatteryStatus(String value) {
        if (isBlank(value)) {
            return "";
        }
        String key = "device.spec.battery.status." + value.trim();
        String localized = I18n.get(key);
        return localized.equals(key) ? value.trim() : localized;
    }

    public static Integer parsePercent(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}