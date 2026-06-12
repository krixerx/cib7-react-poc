package com.poc.cib7;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the CIB seven 2.1 POC backend.
 *
 * <p>The CIB seven Spring Boot starter embeds the process engine and the {@code /engine-rest} REST
 * API. Any {@code *.bpmn} file on the classpath is auto-deployed on startup (see {@code
 * deployment-resource-pattern} in {@code application.yaml}), so {@code
 * processes/vehicle-registration.bpmn} is deployed automatically — no code needed.
 */
@SpringBootApplication
public class Cib7PocApplication {

  public static void main(String[] args) {
    SpringApplication.run(Cib7PocApplication.class, args);
  }
}
