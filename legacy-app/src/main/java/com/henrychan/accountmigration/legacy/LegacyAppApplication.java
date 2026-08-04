package com.henrychan.accountmigration.legacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the legacy account-processing application. */
@SpringBootApplication
public class LegacyAppApplication {

  /** Runs the Spring Boot application. */
  public static void main(String[] args) {
    SpringApplication.run(LegacyAppApplication.class, args);
  }
}
