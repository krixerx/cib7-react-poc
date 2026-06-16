package com.poc.backend.transport;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plate allocation for {@code transport-vehicle-registration.bpmn} — called by {@code
 * Task_TransportAllocatePlate} strictly after the fee payment is correlated. Persists the
 * registration record (the demo's "integration to database" requirement) and returns the plate: a
 * generated generic {@code NNNNN AB} number for {@code plateOption=random}, or the caller's
 * reserved number verbatim ("previously reserved number plates" in the demo's service path).
 */
@RestController
@RequestMapping("/api/public/transport/plates")
public class TransportPlateController {

  /** Letters used on vehicle plates (Latin series, simplified for the POC). */
  private static final String PLATE_LETTERS = "ABDHMRSTWY";

  private final PlateRegistrationRepository plates;

  public TransportPlateController(PlateRegistrationRepository plates) {
    this.plates = plates;
  }

  @PostMapping("/allocate")
  public ResponseEntity<?> allocate(@RequestBody AllocateRequest req) {
    if (isBlank(req.processInstanceId())
        || isBlank(req.vin())
        || isBlank(req.ownerName())
        || isBlank(req.vehicleCategory())) {
      return ResponseEntity.badRequest()
          .body(
              new ErrorResponse(
                  "missing_fields",
                  "processInstanceId, vin, ownerName, and vehicleCategory are required."));
    }

    String plateNumber;
    if ("reserved".equalsIgnoreCase(req.plateOption()) && !isBlank(req.reservedPlateNumber())) {
      plateNumber = req.reservedPlateNumber().trim().toUpperCase();
      if (plates.existsByPlateNumber(plateNumber)) {
        return ResponseEntity.status(409)
            .body(
                new ErrorResponse(
                    "plate_taken", "The reserved plate number is already registered."));
      }
    } else {
      plateNumber = nextFreePlate();
    }

    PlateRegistration saved =
        plates.save(
            new PlateRegistration(
                plateNumber,
                req.vin().trim().toUpperCase(),
                req.ownerName().trim(),
                req.vehicleCategory().trim(),
                req.processInstanceId().trim(),
                Instant.now()));

    return ResponseEntity.ok(
        new AllocateResponse(saved.getPlateNumber(), saved.getId(), saved.getIssuedAt()));
  }

  private String nextFreePlate() {
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    for (int attempt = 0; attempt < 50; attempt++) {
      String candidate =
          "%d %c%c"
              .formatted(
                  rnd.nextInt(1000, 100000),
                  PLATE_LETTERS.charAt(rnd.nextInt(PLATE_LETTERS.length())),
                  PLATE_LETTERS.charAt(rnd.nextInt(PLATE_LETTERS.length())));
      if (!plates.existsByPlateNumber(candidate)) {
        return candidate;
      }
    }
    // 50 collisions in a 9.6M space means something is broken — fail loudly.
    throw new IllegalStateException("Could not allocate a free plate number.");
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  public record AllocateRequest(
      String processInstanceId,
      String vin,
      String ownerName,
      String vehicleCategory,
      String plateOption,
      String reservedPlateNumber) {}

  public record AllocateResponse(String plateNumber, Long registrationId, Instant issuedAt) {}

  public record ErrorResponse(String code, String message) {}
}
