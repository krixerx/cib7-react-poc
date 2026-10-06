package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What a case owes, computed on the server from engine state. The checkout charges this amount and
 * the provider callback must report exactly it (docs/security.md rule 4); nothing the browser sends
 * changes it.
 *
 * <pre>
 *   businessRegistration         → EUR 265 flat (Estonian fast-track OÜ fee)
 *   vehicleRegistration          → EUR 25 / 75 / 150 tiered by vehicle value
 *                                  (mirrors the tiers in the state-fee-invoice PDF template)
 * </pre>
 */
@Component
public class FeeSchedule {

  static final String VEHICLE_KEY = "vehicleRegistration";
  static final String OU_KEY = "businessRegistration";

  /** Process definitions that have a payment step. */
  public static final Set<String> PAYABLE = Set.of(VEHICLE_KEY, OU_KEY);

  private final EngineClient engine;

  public FeeSchedule(EngineClient engine) {
    this.engine = engine;
  }

  /** One fee: what it is for, who receives it, how much. */
  public record Charge(
      String processDefinitionKey,
      String serviceName,
      String recipient,
      BigDecimal amount,
      String currency) {}

  /** The fee for an active instance, or empty when its process has no payment step. */
  public Optional<Charge> chargeFor(ProcessInstanceRef instance) {
    String pi = instance.id();
    String key = instance.definitionKey();
    if (VEHICLE_KEY.equals(key)) {
      return Optional.of(
          charge(key, "Vehicle registration state fee", "Transpordiamet", vehicleFee(pi)));
    }
    if (OU_KEY.equals(key)) {
      return Optional.of(
          charge(key, "OÜ registration state fee", "Äriregister (Justiitsministeerium)", 265.0));
    }
    return Optional.empty();
  }

  private static Charge charge(String key, String service, String recipient, double amount) {
    return new Charge(
        key,
        service,
        recipient,
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP),
        "EUR");
  }

  /** Tiered vehicle fee — mirrors approval-pdf.json.ftl's if/elseif/else. */
  private double vehicleFee(String pi) {
    double value = parseAmount(engine.getRawVariable(pi, "price"));
    if (value < 5000) return 25.0;
    if (value < 20000) return 75.0;
    return 150.0;
  }

  /**
   * Coerces a process variable that should be numeric into a double. Some engine→JUEL paths surface
   * what should be a Double as a locale-formatted String like "38,000".
   */
  static double parseAmount(Object raw) {
    if (raw instanceof Number n) return n.doubleValue();
    if (raw instanceof String s) {
      String stripped =
          s.replace(",", "").replace(" ", "").replace(" ", "").replace("$", "").replace("€", "");
      try {
        return Double.parseDouble(stripped);
      } catch (NumberFormatException e) {
        return 0.0;
      }
    }
    return 0.0;
  }
}
