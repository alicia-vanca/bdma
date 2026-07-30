package com.app.common.modules.device.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.app.common.modules.device.dtos.DeviceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.app.common.definitions.AppConstants;
import com.app.common.exceptions.DeviceDisconnectedException;
import com.app.common.modules.device.configs.DeviceMediaLayout;
import com.app.common.modules.device.dtos.DeviceValidationResult;
import com.app.common.models.ModelWhitelist;
import com.app.common.models.ModelWhitelistRule;
import com.app.common.models.ValidatedDevice;
import com.app.common.repositories.ModelWhitelistRepository;
import com.app.common.repositories.ValidatedDeviceRepository;

@Service
@Lazy
public class DeviceValidationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceValidationService.class);
    private static final String LOG_TAG = "[DeviceValidation]";

    private static final Pattern ACCOUNT_USER_ID_PATTERN = Pattern
            .compile("account\\.user_id\\s*=\\s*\"([^\"\\r\\n]*)\"", Pattern.CASE_INSENSITIVE);

    private final AdbClient adbClient;
    private final ModelWhitelistRepository whitelistRepository;
    private final ValidatedDeviceRepository validatedDeviceRepository;
    private final DeviceSpecMonitor deviceSpecMonitor;

    private final String configCsonPath;
    private final DeviceMediaLayout deviceMediaLayout;

    public DeviceValidationService(AdbClient adbClient,
            ModelWhitelistRepository whitelistRepository,
            @Lazy ValidatedDeviceRepository validatedDeviceRepository,
            DeviceSpecMonitor deviceSpecMonitor,
            @Value("${device.validation.config-cson-path}") String configCsonPath,
            DeviceMediaLayout deviceMediaLayout) {
        this.adbClient = adbClient;
        this.whitelistRepository = whitelistRepository;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.deviceSpecMonitor = deviceSpecMonitor;
        this.configCsonPath = configCsonPath;
        this.deviceMediaLayout = deviceMediaLayout;
    }

    public DeviceValidationResult validateConnectedDevice(String adbSerial) {
        try {
            return validate(adbSerial);
        } catch (DeviceDisconnectedException e) {
            log.debug("{} Device disconnected during validation: adbSerial={}", LOG_TAG, adbSerial);
            return DeviceValidationResult.invalid(adbSerial, "Device disconnected during validation");
        }
    }

    public ValidatedDevice saveValidatedDevice(String cameraId, String hardwareId, Long whitelistId) {
        return validatedDeviceRepository.saveOrUpdate(cameraId, hardwareId, whitelistId);
    }

    /**
     * Finds the saved device metadata used to rebuild sync context from camera ID.
     *
     * @param cameraId validated camera ID assigned to the device
     * @return saved device metadata when the camera has been registered
     */
    public Optional<ValidatedDevice> findValidatedDevice(String cameraId) {
        return validatedDeviceRepository.findByCameraId(cameraId);
    }

    // Validate connected device and return metadata used by the UI flow.
    private DeviceValidationResult validate(String adbSerial) {
        if (adbSerial == null || adbSerial.isBlank()) {
            return failValidation(null, Map.of(), "ADB serial is required");
        }

        Map<String, String> props = adbClient.getProps(adbSerial);
        if (props.isEmpty()) {
            return failValidation(adbSerial, props, "Cannot read device properties");
        }

        String hardwareId = sanitizeValue(props.get(AppConstants.ADB_PROP_SERIAL));
        if (hardwareId.isBlank()) {
            hardwareId = sanitizeValue(adbSerial);
        }

        List<ModelWhitelist> whitelists = whitelistRepository.findAllActiveWithRules();
        Optional<ModelWhitelist> matchedWhitelist = findMatchedWhitelist(whitelists, props);
        if (matchedWhitelist.isEmpty()) {
            return failValidation(adbSerial, props, "Whitelist rule mismatch");
        }

        List<String> missingFiles = findMissingFiles(adbSerial, Set.of(configCsonPath));
        if (!adbClient.fileExists(adbSerial, deviceMediaLayout.legacyDeviceDataFolder())
                && !adbClient.fileExists(adbSerial, deviceMediaLayout.dcamDeviceDataFolder())) {
            missingFiles.add(deviceMediaLayout.legacyDeviceDataFolder() + " or "
                    + deviceMediaLayout.dcamDeviceDataFolder());
        }
        if (!missingFiles.isEmpty()) {
            return failValidation(adbSerial, props, "Required file not exist: " + String.join(", ", missingFiles));
        }

        String configContent = adbClient.readTextFile(adbSerial, configCsonPath);

        if (configContent == null || configContent.isBlank()) {
            return failValidation(adbSerial,
                    props,
                    "cameraId not exist in file: config missing or unreadable " + configCsonPath);
        }
        String cameraId = extractAccountUserId(configContent);
        if (cameraId.isBlank()) {
            return failValidation(adbSerial,
                    props,
                    "cameraId not exist in file: account.user_id is blank or invalid " + configCsonPath);
        }

        ModelWhitelist whitelist = matchedWhitelist.get();
        Optional<ValidatedDevice> existingDevice = validatedDeviceRepository.findByCameraId(cameraId);

        // Get device name and active state from database if device already saved,
        // otherwise use defaults for a new transient device.
        String deviceName = existingDevice
                .map(ValidatedDevice::getDeviceName)
                .orElse(cameraId);
        boolean active = existingDevice
                .map(ValidatedDevice::isActive)
                .orElse(true);

        DeviceSpec specInfo = deviceSpecMonitor.getDeviceSpecInfo(hardwareId);

        log.info(
                "{} Validated device cameraId={} hardwareId={} whitelistId={} whitelistModel={} alreadySaved={} active={}",
                LOG_TAG,
                cameraId,
                hardwareId,
                whitelist.getId(),
                whitelist.getModelName(),
                existingDevice.isPresent(),
                active);

        return DeviceValidationResult.valid(
                hardwareId,
                whitelist.getId(),
                whitelist.getModelName(),
                cameraId,
                existingDevice.isPresent(),
                deviceName,
                active,
                specInfo);
    }

    private Optional<ModelWhitelist> findMatchedWhitelist(List<ModelWhitelist> whitelists,
            Map<String, String> props) {
        for (ModelWhitelist whitelist : whitelists) {
            if (isWhitelistMatched(whitelist, props)) {
                return Optional.of(whitelist);
            }
        }
        return Optional.empty();
    }

    private boolean isWhitelistMatched(ModelWhitelist whitelist, Map<String, String> props) {
        List<ModelWhitelistRule> rules = whitelist.getRules();
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        for (ModelWhitelistRule rule : rules) {
            String actual = sanitizeValue(props.get(rule.getPropKey()));
            String expected = sanitizeValue(rule.getExpectedValue());
            if (!expected.equals(actual)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Log failed validation with stable hardware props needed to diagnose whitelist
     * and required-file issues from production logs.
     *
     * @param adbSerial ADB serial being validated
     * @param props     device getprop values captured before failure
     * @param reason    validation failure reason suitable for logs and UI messages
     * @return invalid validation result carrying the same reason
     */
    private DeviceValidationResult failValidation(String adbSerial, Map<String, String> props, String reason) {
        if (log.isWarnEnabled()) {
            log.warn(
                    "{} Failed to verify device adbSerial={} {}={} {}={} {}={} reason={}",
                    LOG_TAG,
                    adbSerial,
                    AppConstants.ADB_PROP_PRODUCT_MODEL,
                    prop(props, AppConstants.ADB_PROP_PRODUCT_MODEL),
                    AppConstants.ADB_PROP_PRODUCT_DEVICE,
                    prop(props, AppConstants.ADB_PROP_PRODUCT_DEVICE),
                    AppConstants.ADB_PROP_BOARD_PLATFORM,
                    prop(props, AppConstants.ADB_PROP_BOARD_PLATFORM),
                    reason);
        }
        return DeviceValidationResult.invalid(adbSerial, reason);
    }

    private String prop(Map<String, String> props, String key) {
        if (props == null) {
            return "";
        }
        return sanitizeValue(props.get(key));
    }

    private List<String> findMissingFiles(String adbSerial, Set<String> requiredFiles) {
        List<String> missing = new ArrayList<>();
        for (String path : requiredFiles) {
            if (!adbClient.fileExists(adbSerial, path)) {
                missing.add(path);
            }
        }
        return missing;
    }

    private String extractAccountUserId(String configContent) {
        if (configContent == null || configContent.isBlank()) {
            return "";
        }
        Matcher matcher = ACCOUNT_USER_ID_PATTERN.matcher(configContent);
        if (matcher.find()) {
            return sanitizeValue(matcher.group(1));
        }
        return "";
    }

    private String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value
                .trim()
                .replace("\"", "")
                .replace("'", "");
    }

    public Long resolveDeviceId(String cameraId) {
        ValidatedDevice validatedDevice = validatedDeviceRepository.findByCameraId(cameraId).orElse(null);
        if (validatedDevice != null) {
            return validatedDevice.getId();
        }
        return createDefaultDevice(cameraId);
    }

    private Long createDefaultDevice(String cameraId) {
        ValidatedDevice validatedDevice = new ValidatedDevice();
        validatedDevice.setDeviceName(cameraId);
        validatedDevice.setCameraId(cameraId);
        log.info("Auto-created sync device '{}'", cameraId);
        return validatedDeviceRepository.insert(validatedDevice);
    }

}
