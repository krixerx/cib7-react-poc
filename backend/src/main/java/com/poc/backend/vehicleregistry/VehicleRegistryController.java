package com.poc.backend.vehicleregistry;

import java.time.Year;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for the Estonian vehicle registry (Liiklusregister).
 *
 * <p>Two endpoints under {@code /api/public/vehicle-registry/**}: a list used by the PartA form to
 * populate its vehicle dropdown, and a single-vehicle lookup the engine hits from {@code
 * Task_GetPrice} via the http-connector. Both unauthenticated — the matcher in {@link
 * com.poc.backend.security.SecurityConfig} already opens {@code /api/public/**}.
 *
 * <p>From the engine's point of view this is exactly the external REST service the http-connector
 * exists for — it just happens to be served by this microservice instead of a real government
 * registry.
 *
 * <p>The catalog is hard-coded — POC only. Each entry carries enough to drive the demo:
 *
 * <ul>
 *   <li>{@code vin}, {@code make}, {@code model}, {@code year}, {@code value}, {@code fuelType} so
 *       the SPA dropdown reads like a real listing,
 *   <li>{@code ageYears} computed server-side at request time so the BPMN doesn't have to compute
 *       it in JUEL,
 *   <li>span of fee tiers (&lt;€5k, &lt;€20k, &lt;€50k, &ge;€50k) and DMN paths (under-age owner /
 *       luxury / cheap-old-cheap auto- approve / default-review) — picking any of the ten will hit
 *       a different combination of branches.
 * </ul>
 */
@RestController
@RequestMapping("/api/public/vehicle-registry")
public class VehicleRegistryController {

  @GetMapping("/vehicles")
  public List<VehicleResponse> listVehicles() {
    int currentYear = Year.now().getValue();
    return CATALOG.stream().map(v -> VehicleResponse.of(v, currentYear)).toList();
  }

  @GetMapping("/vehicles/{vin}")
  public ResponseEntity<VehicleResponse> getVehicle(@PathVariable String vin) {
    int currentYear = Year.now().getValue();
    Optional<Vehicle> match =
        CATALOG.stream().filter(v -> v.vin().equalsIgnoreCase(vin)).findFirst();
    return match
        .map(v -> ResponseEntity.ok(VehicleResponse.of(v, currentYear)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** A vehicle in the catalog. */
  private record Vehicle(
      String vin, String make, String model, int year, double value, String fuelType) {}

  /** Response shape — the catalog entry plus the derived ageYears. */
  public record VehicleResponse(
      String vin,
      String make,
      String model,
      int year,
      int ageYears,
      double value,
      String fuelType) {
    static VehicleResponse of(Vehicle v, int currentYear) {
      return new VehicleResponse(
          v.vin(),
          v.make(),
          v.model(),
          v.year(),
          Math.max(0, currentYear - v.year()),
          v.value(),
          v.fuelType());
    }
  }

  /**
   * Hand-curated catalog of believable Estonian-market vehicles. Span is intentional — see the
   * class javadoc for the fee/DMN-tier coverage. VINs are realistic-looking 17-character strings;
   * they don't decode to any real production records.
   */
  private static final List<Vehicle> CATALOG =
      List.of(
          new Vehicle("WVWZZZ1KZAW123001", "VW", "Golf 1.4 TSI", 2018, 8400.0, "Petrol"),
          new Vehicle("TMBJC23456789012X", "Skoda", "Octavia 1.5 TSI", 2020, 14500.0, "Petrol"),
          new Vehicle(
              "5YJ3E1EA1JF000123", "Tesla", "Model 3 Long Range", 2022, 38000.0, "Electric"),
          new Vehicle("YV1UZA8VCK1234001", "Volvo", "XC60 2.0 D4", 2019, 22000.0, "Diesel"),
          new Vehicle("WBA5R7C50KAA00789", "BMW", "X3 xDrive20d", 2021, 34000.0, "Diesel"),
          new Vehicle("WAUZZZ8K6HA001234", "Audi", "A4 2.0 TDI", 2017, 15500.0, "Diesel"),
          new Vehicle("JTNK4RBE60J123456", "Toyota", "Corolla 1.8 Hybrid", 2023, 19800.0, "Hybrid"),
          new Vehicle("VF15RBA0H55012345", "Renault", "Clio 1.2", 2015, 4200.0, "Petrol"),
          new Vehicle("WDD2130421A123456", "Mercedes-Benz", "E 220d", 2020, 42500.0, "Diesel"),
          new Vehicle("WP0AB2A91KS123456", "Porsche", "911 Carrera", 2019, 88000.0, "Petrol"));
}
