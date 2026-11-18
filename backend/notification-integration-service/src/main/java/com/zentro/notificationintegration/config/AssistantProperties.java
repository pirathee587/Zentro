package com.zentro.notificationintegration.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("zentro.assistant")
public record AssistantProperties(String apiKey, @NotBlank String baseUrl, @NotBlank String model,
    @Min(1) @Max(1000) int connectTimeoutMs, @Min(1) @Max(15000) int readTimeoutMs,
    @Min(1) @Max(3000) int toolTimeoutMs) {
  @Override public String toString() { return "AssistantProperties[credentials redacted]"; }
}
