package com.app.common.services;

import com.app.common.dtos.DeviceValidationResult;
import com.app.common.models.ModelWhitelist;
import com.app.common.models.ModelWhitelistRule;
import com.app.common.models.ValidatedDevice;
import com.app.common.repositories.ModelWhitelistRepository;
import com.app.common.repositories.ValidatedDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeviceValidationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceValidationService.class);

    private static final String SERIAL_PROPERTY = "ro.serialno";
    private static final Pattern ACCOUNT_USER_ID_PATTERN = Pattern
            .compile("account\\.user_id\\s*=\\s*\"([^\"\\r\\n]*)\"", Pattern.CASE_INSENSITIVE);

    private final AdbClient adbClient;
    private final ModelWhitelistRepository whitelistRepository;
    private final ValidatedDeviceRepository validatedDeviceRepository;

    private final String configCsonPath;
    private final String requiredDeviceDataFolder;

    public DeviceValidationService(AdbClient adbClient,
            ModelWhitelistRepository whitelistRepository,
            ValidatedDeviceRepository validatedDeviceRepository,
            @Value("${device.validation.config-cson-path}") String configCsonPath,
            @Value("${device.validation.required-device-data-folder}") String requiredDeviceDataFolder) {
        this.adbClient = adbClient;
        this.whitelistRepository = whitelistRepository;
        this.validatedDeviceRepository = validatedDeviceRepository;
        this.configCsonPath = configCsonPath;
        this.requiredDeviceDataFolder = requiredDeviceDataFolder;
    }

    public List<String> listConnectedSerials() {
        return adbClient.listConnectedSerials();
    }

    public DeviceValidationResult validateFirstConnected() {
        List<String> connected = adbClient.listConnectedSerials();
        if (connected.isEmpty()) {
            return DeviceValidationResult.invalid(null, "No connected ADB device");
        }
        return validateConnectedDevice(connected.getFirst());
    }

    public DeviceValidationResult validateConnectedDevice(String serial) {
        return validate(serial);
    }

    public ValidatedDevice saveValidatedDevice(String deviceName, String hardwareId, String whitelistId) {
        return validatedDeviceRepository.saveOrUpdate(deviceName, hardwareId, whitelistId);
    }

    // Validate connected device and return metadata used by the UI flow.
    private DeviceValidationResult validate(String serial) {
        if (serial == null || serial.isBlank()) {
            return DeviceValidationResult.invalid(null, "Serial is required");
        }

        Map<String, String> props = adbClient.getProps(serial);
        if (props.isEmpty()) {
            return DeviceValidationResult.invalid(serial, "Cannot read device properties");
        }

        String hardwareId = sanitizeValue(props.get(SERIAL_PROPERTY));
        if (hardwareId.isBlank()) {
            hardwareId = sanitizeValue(serial);
        }

        List<ModelWhitelist> whitelists = whitelistRepository.findAllActiveWithRules();
        Optional<ModelWhitelist> matchedWhitelist = findMatchedWhitelist(whitelists, props);
        if (matchedWhitelist.isEmpty()) {
            return DeviceValidationResult.invalid(serial, "Device does not match any active whitelist");
        }

        List<String> missingFiles = findMissingFiles(serial, Set.of(configCsonPath, requiredDeviceDataFolder));
        if (!missingFiles.isEmpty()) {
            return DeviceValidationResult.invalidWithMissingFiles(
                    serial,
                    "Required files are missing",
                    missingFiles);
        }

        String configContent = adbClient.readTextFile(serial, configCsonPath);

        if (configContent == null || configContent.isBlank()) {
            return DeviceValidationResult.invalid(serial, "account.user_id not found: config missing or unreadable");
        }
        String accountUserId = extractAccountUserId(configContent);
        if (accountUserId.isBlank()) {
            return DeviceValidationResult.invalid(serial, "account.user_id is blank or invalid");
        }

        ModelWhitelist whitelist = matchedWhitelist.get();
        Optional<ValidatedDevice> existingDevice = validatedDeviceRepository.findByHardwareId(hardwareId);

        log.info("Validated device serial={} hardwareId={} whitelistId={} accountUserId={} alreadySaved={}",
                serial,
                hardwareId,
                whitelist.getId(),
                accountUserId,
                existingDevice.isPresent());

        return DeviceValidationResult.valid(
                serial,
                hardwareId,
                whitelist.getId(),
                whitelist.getModelName(),
                accountUserId,
                existingDevice.isPresent(),
                existingDevice.orElse(null));
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

    private List<String> findMissingFiles(String serial, Set<String> requiredFiles) {
        List<String> missing = new ArrayList<>();
        for (String path : requiredFiles) {
            if (!adbClient.fileExists(serial, path)) {
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
}
