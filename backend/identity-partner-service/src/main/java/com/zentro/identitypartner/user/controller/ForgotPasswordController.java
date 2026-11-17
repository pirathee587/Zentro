package com.zentro.identitypartner.user.controller;

import com.zentro.identitypartner.user.dto.ResetPasswordRequest;
import com.zentro.identitypartner.user.dto.SendOtpRequest;
import com.zentro.identitypartner.user.dto.VerifyResetOtpRequest;
import com.zentro.identitypartner.user.service.RegistrationOtpService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/forgot-password")
public class ForgotPasswordController {
  private final RegistrationOtpService otpService;

  public ForgotPasswordController(RegistrationOtpService otpService) {
    this.otpService = otpService;
  }

  @PostMapping("/send-otp")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> sendOtp(@Valid @RequestBody SendOtpRequest request) {
    return otpService.sendForgotPasswordOtp(request);
  }

  @PostMapping("/verify-otp")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> verifyOtp(@Valid @RequestBody VerifyResetOtpRequest request) {
    return otpService.verifyForgotPasswordOtp(request.email(), request.code());
  }

  @PostMapping("/reset")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    return otpService.resetPasswordWithOtp(request);
  }
}

