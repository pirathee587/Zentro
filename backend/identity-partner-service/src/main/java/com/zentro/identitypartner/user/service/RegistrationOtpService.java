package com.zentro.identitypartner.user.service;

import com.zentro.identitypartner.email.EmailSenderService;
import com.zentro.identitypartner.user.dto.DirectRegisterRequest;
import com.zentro.identitypartner.user.dto.LoginRequest;
import com.zentro.identitypartner.user.dto.SendOtpRequest;
import com.zentro.identitypartner.user.dto.VerifyOtpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationOtpService {
  private static final Logger log = LoggerFactory.getLogger(RegistrationOtpService.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final JdbcTemplate jdbc;
  private final KeycloakAdminService keycloakAdmin;
  private final EmailSenderService emailSender;

  public RegistrationOtpService(
      JdbcTemplate jdbc,
      KeycloakAdminService keycloakAdmin,
      EmailSenderService emailSender) {
    this.jdbc = jdbc;
    this.keycloakAdmin = keycloakAdmin;
    this.emailSender = emailSender;
  }

  @Transactional
  public Map<String, Object> sendOtp(SendOtpRequest request) {
    String email = request.email().trim().toLowerCase();
    String entryPoint = request.entryPoint() != null ? request.entryPoint().trim() : "customer";

    // 1. Check if user already exists in DB
    List<UUID> existingInDb = jdbc.queryForList(
        "SELECT id FROM identity.app_user WHERE LOWER(email) = ?",
        UUID.class,
        email);
    if (!existingInDb.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered. Please log in.");
    }

    // 2. Check Keycloak
    if (keycloakAdmin.userExistsByEmail(email)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered. Please log in.");
    }

    // 3. Cooldown check: 60 seconds rate limit between OTP requests
    List<java.time.OffsetDateTime> recentOtps = jdbc.queryForList(
        "SELECT created_at FROM identity.registration_otp WHERE LOWER(email) = ? AND created_at > now() - INTERVAL '60 seconds' ORDER BY created_at DESC LIMIT 1",
        java.time.OffsetDateTime.class,
        email);
    if (!recentOtps.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Please wait 60 seconds before requesting another verification code.");
    }

    // 4. Generate 6-digit numeric OTP code
    String otpCode = String.format("%06d", SECURE_RANDOM.nextInt(1000000));
    String codeHash = sha256(otpCode);

    // 5. Invalidate prior unverified OTPs for this email
    jdbc.update("UPDATE identity.registration_otp SET expires_at = now() WHERE LOWER(email) = ? AND verified_at IS NULL", email);

    // 6. Store OTP record valid for 5 minutes
    jdbc.update("""
        INSERT INTO identity.registration_otp (email, code_hash, entry_point, expires_at)
        VALUES (?, ?, ?, now() + INTERVAL '5 minutes')
        """, email, codeHash, entryPoint);

    // 7. Send OTP email
    emailSender.sendRegistrationOtp(email, otpCode);
    log.info("Sent registration OTP code to email={}", email);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Verification code sent to " + email);
  }

  @Transactional
  public Map<String, Object> verifyOtp(VerifyOtpRequest request) {
    String email = request.email().trim().toLowerCase();
    String code = request.code().trim();

    List<Map<String, Object>> records = jdbc.queryForList("""
        SELECT id, code_hash, attempts, expires_at, verified_at
          FROM identity.registration_otp
         WHERE LOWER(email) = ? AND verified_at IS NULL
         ORDER BY created_at DESC
         LIMIT 1
        """, email);

    if (records.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No verification request found for this email. Please request a new code.");
    }

    Map<String, Object> row = records.getFirst();
    UUID otpId = (UUID) row.get("id");
    String storedHash = (String) row.get("code_hash");
    int attempts = ((Number) row.get("attempts")).intValue();

    Object expObj = row.get("expires_at");
    Instant expiresAt;
    if (expObj instanceof java.time.OffsetDateTime odt) {
      expiresAt = odt.toInstant();
    } else if (expObj instanceof java.sql.Timestamp ts) {
      expiresAt = ts.toInstant();
    } else if (expObj instanceof java.time.Instant inst) {
      expiresAt = inst;
    } else {
      expiresAt = Instant.now().plusSeconds(600);
    }

    if (attempts >= 5) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Please request a new code.");
    }

    if (expiresAt.isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The verification code has expired. Please request a new code.");
    }

    String candidateHash = sha256(code);
    if (!storedHash.equalsIgnoreCase(candidateHash)) {
      jdbc.update("UPDATE identity.registration_otp SET attempts = attempts + 1 WHERE id = ?", otpId);
      int remaining = 4 - attempts;
      String attemptsMsg = remaining > 0 ? " (" + remaining + " attempts remaining)" : "";
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code" + attemptsMsg + ".");
    }

    // Mark OTP verified
    jdbc.update("UPDATE identity.registration_otp SET verified_at = now() WHERE id = ?", otpId);

    // Provision user in Keycloak (CUSTOMER role, permanent password)
    String keycloakSubject = keycloakAdmin.createVerifiedCustomerUser(email, request.name(), request.password());

    // Insert user into identity.app_user
    jdbc.update("""
        INSERT INTO identity.app_user (keycloak_subject, email, display_name, account_status)
        VALUES (?, ?, ?, 'ACTIVE')
        ON CONFLICT (keycloak_subject) DO UPDATE SET
          email = EXCLUDED.email,
          display_name = EXCLUDED.display_name,
          updated_at = now()
        """, keycloakSubject, email, request.name().trim());

    log.info("Verified registration OTP and created user: email={} subject={}", email, keycloakSubject);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Account created and verified successfully.");
  }

  @Transactional
  public Map<String, Object> sendForgotPasswordOtp(SendOtpRequest request) {
    String email = request.email().trim().toLowerCase();

    List<UUID> existingInDb = jdbc.queryForList(
        "SELECT id FROM identity.app_user WHERE LOWER(email) = ?",
        UUID.class,
        email);
    boolean existsInKeycloak = keycloakAdmin.userExistsByEmail(email);

    if (existingInDb.isEmpty() && !existsInKeycloak) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No account found with this email address.");
    }

    String otpCode = String.format("%06d", SECURE_RANDOM.nextInt(1000000));
    String codeHash = sha256(otpCode);

    jdbc.update("UPDATE identity.registration_otp SET expires_at = now() WHERE LOWER(email) = ? AND verified_at IS NULL AND entry_point = 'password_reset'", email);

    jdbc.update("""
        INSERT INTO identity.registration_otp (email, code_hash, entry_point, expires_at)
        VALUES (?, ?, 'password_reset', now() + INTERVAL '10 minutes')
        """, email, codeHash);

    emailSender.sendPasswordResetOtp(email, otpCode);
    log.info("Sent password reset OTP code to email={}", email);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Password reset code sent to " + email);
  }

  @Transactional
  public Map<String, Object> verifyForgotPasswordOtp(String emailParam, String codeParam) {
    String email = emailParam.trim().toLowerCase();
    String code = codeParam.trim();

    List<Map<String, Object>> records = jdbc.queryForList("""
        SELECT id, code_hash, attempts, expires_at, verified_at
          FROM identity.registration_otp
         WHERE LOWER(email) = ? AND entry_point = 'password_reset'
         ORDER BY created_at DESC
         LIMIT 1
        """, email);

    if (records.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active password reset request found for this email. Please request a new code.");
    }

    Map<String, Object> row = records.getFirst();
    UUID otpId = (UUID) row.get("id");
    String storedHash = (String) row.get("code_hash");
    int attempts = ((Number) row.get("attempts")).intValue();

    Object expObj = row.get("expires_at");
    Instant expiresAt;
    if (expObj instanceof java.time.OffsetDateTime odt) {
      expiresAt = odt.toInstant();
    } else if (expObj instanceof java.sql.Timestamp ts) {
      expiresAt = ts.toInstant();
    } else if (expObj instanceof java.time.Instant inst) {
      expiresAt = inst;
    } else {
      expiresAt = Instant.now().plusSeconds(600);
    }

    if (attempts >= 5) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Please request a new code.");
    }

    if (expiresAt.isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The verification code has expired. Please request a new code.");
    }

    String candidateHash = sha256(code);
    if (!storedHash.equalsIgnoreCase(candidateHash)) {
      jdbc.update("UPDATE identity.registration_otp SET attempts = attempts + 1 WHERE id = ?", otpId);
      int remaining = 4 - attempts;
      String attemptsMsg = remaining > 0 ? " (" + remaining + " attempts remaining)" : "";
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code" + attemptsMsg + ".");
    }

    jdbc.update("UPDATE identity.registration_otp SET verified_at = now() WHERE id = ?", otpId);
    log.info("Verified forgot-password OTP for email={}", email);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Verification code verified successfully.");
  }

  @Transactional
  public Map<String, Object> resetPasswordWithOtp(com.zentro.identitypartner.user.dto.ResetPasswordRequest request) {
    String email = request.email().trim().toLowerCase();
    String code = request.code().trim();
    String newPassword = request.newPassword();

    List<Map<String, Object>> records = jdbc.queryForList("""
        SELECT id, code_hash, attempts, expires_at, verified_at
          FROM identity.registration_otp
         WHERE LOWER(email) = ? AND entry_point = 'password_reset'
         ORDER BY created_at DESC
         LIMIT 1
        """, email);

    if (records.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active password reset request found for this email. Please request a new code.");
    }

    Map<String, Object> row = records.getFirst();
    UUID otpId = (UUID) row.get("id");
    String storedHash = (String) row.get("code_hash");
    int attempts = ((Number) row.get("attempts")).intValue();

    Object expObj = row.get("expires_at");
    Instant expiresAt;
    if (expObj instanceof java.time.OffsetDateTime odt) {
      expiresAt = odt.toInstant();
    } else if (expObj instanceof java.sql.Timestamp ts) {
      expiresAt = ts.toInstant();
    } else if (expObj instanceof java.time.Instant inst) {
      expiresAt = inst;
    } else {
      expiresAt = Instant.now().plusSeconds(600);
    }

    if (attempts >= 5) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Please request a new code.");
    }

    if (expiresAt.isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The verification code has expired. Please request a new code.");
    }

    String candidateHash = sha256(code);
    if (!storedHash.equalsIgnoreCase(candidateHash)) {
      jdbc.update("UPDATE identity.registration_otp SET attempts = attempts + 1 WHERE id = ?", otpId);
      int remaining = 4 - attempts;
      String attemptsMsg = remaining > 0 ? " (" + remaining + " attempts remaining)" : "";
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification code" + attemptsMsg + ".");
    }

    // Invalidate the OTP record so it cannot be used again
    jdbc.update("UPDATE identity.registration_otp SET verified_at = COALESCE(verified_at, now()), expires_at = now() WHERE id = ?", otpId);

    String subject = keycloakAdmin.findUserIdByEmail(email);
    if (subject == null || subject.isBlank()) {
      List<String> subjects = jdbc.queryForList(
          "SELECT keycloak_subject FROM identity.app_user WHERE LOWER(email) = ?",
          String.class,
          email);
      if (!subjects.isEmpty() && subjects.getFirst() != null) {
        subject = subjects.getFirst();
      }
    }

    if (subject == null || subject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User account could not be found to update password.");
    }

    keycloakAdmin.resetPassword(subject, newPassword);
    log.info("Successfully reset password with OTP for email={}", email);
    log.info("Successfully reset password with OTP for email={}", email);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Your password has been reset successfully. You can now sign in.");
  }

  @Transactional
  public Map<String, Object> directRegister(DirectRegisterRequest request) {
    String email = request.email().trim().toLowerCase();
    String fullName = (request.firstName().trim() + " " + request.lastName().trim()).trim();

    // 1. Check if user already exists in DB
    List<UUID> existingInDb = jdbc.queryForList(
        "SELECT id FROM identity.app_user WHERE LOWER(email) = ?",
        UUID.class,
        email);
    if (!existingInDb.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered. Please log in.");
    }

    // 2. Check Keycloak
    if (keycloakAdmin.userExistsByEmail(email)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email is already registered. Please log in.");
    }

    // 3. Provision user in Keycloak (CUSTOMER role, permanent password)
    String keycloakSubject = keycloakAdmin.createVerifiedCustomerUser(email, fullName, request.password());

    // 4. Insert user into identity.app_user
    jdbc.update("""
        INSERT INTO identity.app_user (keycloak_subject, email, display_name, account_status)
        VALUES (?, ?, ?, 'ACTIVE')
        ON CONFLICT (keycloak_subject) DO UPDATE SET
          email = EXCLUDED.email,
          display_name = EXCLUDED.display_name,
          updated_at = now()
        """, keycloakSubject, email, fullName);

    log.info("Direct registration created user: email={} subject={}", email, keycloakSubject);

    return Map.of(
        "success", true,
        "email", email,
        "message", "Account created successfully. Please sign in with your credentials.");
  }

  public Map<String, Object> login(LoginRequest request) {
    String email = request.email().trim().toLowerCase();
    return keycloakAdmin.authenticate(email, request.password());
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }
}
