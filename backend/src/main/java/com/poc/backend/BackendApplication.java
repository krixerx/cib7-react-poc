package com.poc.backend;

import com.poc.backend.pack.PackManifest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Business microservice for the CIB seven React POC.
 *
 * <p>Owns every {@code /api/**} surface that used to live inside the engine module: owner
 * confirmations, founder signatures, state-fee payments, the curated vehicle registry, and document
 * storage. The engine (cib7 module) is reached exclusively through {@code /engine-rest} — see
 * {@link com.poc.backend.engine.EngineClient} — so the engine stays a clean CIB seven distribution
 * of plugins and connectors with no business code.
 */
@SpringBootApplication
public class BackendApplication {

  public static void main(String[] args) {
    // An incompatible service pack stops the start before any of its descriptors is read.
    PackManifest.load().requireCompatible();
    SpringApplication.run(BackendApplication.class, args);
  }
}
