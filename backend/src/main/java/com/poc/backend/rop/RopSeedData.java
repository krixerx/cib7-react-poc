package com.poc.backend.rop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the deterministic demo rows for the two ROP ITS demo scenarios. Everything NOT listed here
 * is all-clear (the controllers default unknown ids to pass), so these rows are the only way to
 * reach the rejection / medical branches — they are documented in the service specs and rendered as
 * helper text in the application forms.
 *
 * <p>Idempotent: keyed inserts, re-running overwrites the same rows.
 */
@Component
public class RopSeedData {

  private static final Logger LOG = LoggerFactory.getLogger(RopSeedData.class);

  private final VehicleClearanceRepository vehicleClearances;
  private final DriverClearanceRepository driverClearances;

  public RopSeedData(
      VehicleClearanceRepository vehicleClearances, DriverClearanceRepository driverClearances) {
    this.vehicleClearances = vehicleClearances;
    this.driverClearances = driverClearances;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void seed() {
    // Demo Scenario 1 — vehicle registration rejection branches (keyed by VIN).
    vehicleClearances.save(new VehicleClearance("ROPDEMOFAILINSP01", false, true, true));
    vehicleClearances.save(new VehicleClearance("ROPDEMONOINSURE02", true, false, true));
    vehicleClearances.save(new VehicleClearance("ROPDEMOFINESDUE03", true, true, false));

    // Demo Scenario 2 — learning-permit branches (keyed by civil number).
    driverClearances.save(new DriverClearance("90000001", "weak", false, true));
    driverClearances.save(new DriverClearance("90000002", "fail", false, true));
    driverClearances.save(new DriverClearance("90000003", "missing", false, true));
    driverClearances.save(new DriverClearance("90000004", "pass", true, true));
    driverClearances.save(new DriverClearance("90000005", "pass", false, false));

    LOG.info("Seeded ROP demo clearance rows (3 vehicle VINs, 5 driver civil IDs).");
  }
}
