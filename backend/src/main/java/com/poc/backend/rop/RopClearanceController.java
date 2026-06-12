package com.poc.backend.rop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clearance lookups for the two ROP ITS demo services — the stand-in for the external linkages the
 * demo document lists under "Service Link with Other Parties" (technical inspection systems,
 * insurance companies, approved optical shops, the license registry, and the fines/circulars
 * restrictions check).
 *
 * <p>Both endpoints return 200 for ANY id: unknown ids come back all-clear so any ad-hoc demo input
 * sails through, while the rows seeded by {@link RopSeedData} trigger the rejection / medical
 * branches deterministically. Unauthenticated under {@code /api/public/**} — the engine's
 * http-connector calls these without credentials, same posture as the vehicle registry stand-in.
 */
@RestController
@RequestMapping("/api/public/rop")
public class RopClearanceController {

  private final VehicleClearanceRepository vehicleClearances;
  private final DriverClearanceRepository driverClearances;

  public RopClearanceController(
      VehicleClearanceRepository vehicleClearances, DriverClearanceRepository driverClearances) {
    this.vehicleClearances = vehicleClearances;
    this.driverClearances = driverClearances;
  }

  @GetMapping("/vehicle-clearance/{vin}")
  public VehicleClearanceResponse vehicleClearance(@PathVariable String vin) {
    return vehicleClearances
        .findById(vin.toUpperCase())
        .map(
            c ->
                new VehicleClearanceResponse(
                    c.getVin(),
                    c.isInspectionPassed(),
                    c.isInsured(),
                    c.isRestrictionsCleared()))
        .orElseGet(() -> new VehicleClearanceResponse(vin.toUpperCase(), true, true, true));
  }

  @GetMapping("/driver-clearance/{civilId}")
  public DriverClearanceResponse driverClearance(@PathVariable String civilId) {
    return driverClearances
        .findById(civilId)
        .map(
            c ->
                new DriverClearanceResponse(
                    c.getCivilId(),
                    c.getEyeTestResult(),
                    c.isHasValidTemporaryLicense(),
                    c.isRestrictionsCleared()))
        .orElseGet(() -> new DriverClearanceResponse(civilId, "pass", false, true));
  }

  public record VehicleClearanceResponse(
      String vin, boolean inspectionPassed, boolean insured, boolean restrictionsCleared) {}

  public record DriverClearanceResponse(
      String civilId,
      String eyeTestResult,
      boolean hasValidTemporaryLicense,
      boolean restrictionsCleared) {}
}
