package com.noq.service;

import com.noq.entity.CustomerOrder;
import com.noq.exception.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates and checks the 6-digit pickup OTP.
 * <ul>
 *   <li>SecureRandom (not Math.random) so codes cannot be predicted.</li>
 *   <li>Independent of the order reference - knowing NQ-2026-XXXXX tells you nothing about the OTP.</li>
 *   <li>Only a BCrypt hash is stored, like a password. The OTP is never logged.</li>
 * </ul>
 */
@Service
public class PickupCodeService {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwordEncoder;

    // ponytail: in-memory per-server counter (resets on restart); move to a DB column if NoQ ever runs on several servers.
    private final Map<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();

    public PickupCodeService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public String newOtp() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    public String hash(String otp) {
        return passwordEncoder.encode(otp);
    }

    public boolean matches(String otp, String otpHash) {
        boolean looksLikeAnOtp = otp != null && otp.matches("\\d{6}");
        if (!looksLikeAnOtp) {
            return false; // skip the slow BCrypt check for garbage input
        }
        return passwordEncoder.matches(otp, otpHash);
    }

    /**
     * Customers have no login, so "order reference + OTP" proves the order is theirs.
     * After 5 wrong guesses the public endpoints stop answering for that order (staff can still help at the counter).
     */
    public void checkCustomerAccess(CustomerOrder order, String otp) {
        String ref = order.getOrderReference();
        // Count the attempt BEFORE the slow BCrypt check, atomically. Otherwise 100 parallel guesses could all
        // read "0 failures so far" and slip past the limit together.
        int attempt = failedAttempts.computeIfAbsent(ref, k -> new AtomicInteger()).incrementAndGet();
        if (attempt > MAX_FAILED_ATTEMPTS) {
            throw ApiException.forbidden("Too many wrong pickup codes for this order. Please ask at the counter.");
        }
        if (!matches(otp, order.getPickupOtpHash())) {
            throw ApiException.forbidden("Invalid pickup code for this order");
        }
        failedAttempts.remove(ref); // correct code: start counting from zero again
    }
}
