package com.zentro.identitypartner.user.controller;

import com.zentro.identitypartner.user.dto.SendOtpRequest;
import com.zentro.identitypartner.user.dto.VerifyOtpRequest;
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
@RequestMapping("/api/v1/auth/register")
public class RegistrationOtpController {
  private final RegistrationOtpService otpService;

  public RegistrationOtpController(RegistrationOtpService otpService) {
    this.otpService = otpService;
  }

  @PostMapping("/send-otp")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> sendOtp(@Valid @RequestBody SendOtpRequest request) {
    return otpService.sendOtp(request);
  }

  @PostMapping("/verify-otp")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
    return otpService.verifyOtp(request);
  }
}
