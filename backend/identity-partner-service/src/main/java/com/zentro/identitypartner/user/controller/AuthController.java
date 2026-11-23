package com.zentro.identitypartner.user.controller;

import com.zentro.identitypartner.user.dto.DirectRegisterRequest;
import com.zentro.identitypartner.user.dto.LoginRequest;
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
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final RegistrationOtpService otpService;

  public AuthController(RegistrationOtpService otpService) {
    this.otpService = otpService;
  }

  @PostMapping("/login")
  @ResponseStatus(HttpStatus.OK)
  public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
    return otpService.login(request);
  }
}
