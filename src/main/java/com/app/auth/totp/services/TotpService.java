package com.app.auth.totp.services;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.app.common.configs.AppContext;

@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final String DEV_VERSION = "dev";
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final Clock clock;
    private final String secret;
    private final boolean acceptAnyOtp;
    private long lastAcceptedStep = Long.MIN_VALUE;

    @Autowired
    public TotpService(
            @Value("${app.dev.totp.secret}") String secret,
            @Value("${app.dev.totp.accept-any:false}") boolean acceptAnyOtp) {
        this(Clock.systemUTC(), secret, acceptAnyOtp);
    }

    TotpService(Clock clock, String secret, boolean acceptAnyOtp) {
        this.clock = clock;
        this.secret = secret;
        this.acceptAnyOtp = acceptAnyOtp;
    }

    /**
     * Verifies a developer TOTP code and consumes the matching time step so the
     * same real TOTP code cannot unlock multiple protected actions inside its
     * validity window.
     *
     * @param code six-digit TOTP code entered by the user
     * @return {@code true} when the code is valid and has not been consumed before
     */
    public synchronized boolean verify(String code) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("\\d{6}")) {
            return false;
        }

        long currentStep = clock.instant().getEpochSecond() / TIME_STEP_SECONDS;
        if (acceptAnyOtp) {
            return acceptAnyOtpForDevVersion();
        }

        byte[] decodedSecret;
        try {
            decodedSecret = decodeBase32(secret);
        } catch (IllegalArgumentException e) {
            log.error("Invalid app.dev.totp.secret: {}", e.getMessage());
            return false;
        }

        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            long candidateStep = currentStep + offset;
            if (candidateStep <= lastAcceptedStep) {
                continue;
            }
            if (constantTimeEquals(normalized, generate(decodedSecret, candidateStep))) {
                lastAcceptedStep = candidateStep;
                return true;
            }
        }
        return false;
    }

    private boolean acceptAnyOtpForDevVersion() {
        String version = AppContext.getVersion();
        if (DEV_VERSION.equalsIgnoreCase(version == null ? "" : version.trim())) {
            log.warn("Accepting any OTP because app.dev.totp.accept-any is enabled for dev version");
            return true;
        }

        log.error("Ignoring app.dev.totp.accept-any because app version is not dev: {}", version);
        return false;
    }

    private String generate(byte[] decodedSecret, long timeStep) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(decodedSecret, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate TOTP code", e);
        }
    }

    private static byte[] decodeBase32(String value) {
        String normalized = value == null ? ""
                : value.replace("=", "")
                        .replace(" ", "")
                        .replace("-", "")
                        .toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("TOTP secret must not be blank");
        }

        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[normalized.length() * 5 / 8];
        int index = 0;

        for (char c : normalized.toCharArray()) {
            int valueIndex = indexOfBase32(c);
            if (valueIndex < 0) {
                throw new IllegalArgumentException("TOTP secret contains invalid Base32 character");
            }

            buffer = (buffer << 5) | valueIndex;
            bitsLeft += 5;

            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }

        if (index == result.length) {
            return result;
        }

        byte[] truncated = new byte[index];
        System.arraycopy(result, 0, truncated, 0, index);
        return truncated;
    }

    private static int indexOfBase32(char c) {
        for (int i = 0; i < BASE32_ALPHABET.length; i++) {
            if (BASE32_ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }
}
