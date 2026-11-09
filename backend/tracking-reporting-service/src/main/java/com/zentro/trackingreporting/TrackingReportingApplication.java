package com.zentro.trackingreporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TrackingReportingApplication {
  public static void main(String[] args) {
    SpringApplication.run(TrackingReportingApplication.class, args);
  }
}
