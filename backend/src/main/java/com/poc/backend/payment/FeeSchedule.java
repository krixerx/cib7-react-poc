package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.payment.FeeCatalog.Fee;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * What a case owes, computed on the server from the pack's fee rules ({@link FeeCatalog}) and
 * engine state. The checkout charges this amount, the provider callback must report exactly it
 * (docs/security.md rule 4), and the engine's invoice prints it (through the internal quote);
 * nothing the browser sends changes it.
 */
@Component
public class FeeSchedule {

  private final EngineClient engine;
  private final FeeCatalog catalog;

  public FeeSchedule(EngineClient engine, FeeCatalog catalog) {
    this.engine = engine;
    this.catalog = catalog;
  }

  /** One fee: what it is for, who receives it, how much. */
  public record Charge(
      String processDefinitionKey,
      String serviceName,
      String recipient,
      BigDecimal amount,
      String currency) {}

  /** Whether cases of this process definition charge a fee at all. */
  public boolean isPayable(String processDefinitionKey) {
    return catalog.forProcess(processDefinitionKey).isPresent();
  }

  /** The fee for an active instance, or empty when its process charges none. */
  public Optional<Charge> chargeFor(ProcessInstanceRef instance) {
    return catalog
        .forProcess(instance.definitionKey())
        .map(fee -> charge(fee, amountFor(fee, instance.id())));
  }

  private BigDecimal amountFor(Fee fee, String pi) {
    String variable = fee.amount().variable();
    double value = variable == null ? 0 : parseAmount(engine.getRawVariable(pi, variable));
    return fee.amount().forValue(value);
  }

  private static Charge charge(Fee fee, BigDecimal amount) {
    return new Charge(
        fee.process(),
        fee.service(),
        fee.recipient(),
        amount.setScale(2, RoundingMode.HALF_UP),
        fee.currency());
  }

  /**
   * Coerces a process variable that should be numeric into a double. Some engine paths surface what
   * should be a Double as a locale-formatted String like "38,000".
   */
  static double parseAmount(Object raw) {
    if (raw instanceof Number n) return n.doubleValue();
    if (raw instanceof String s) {
      String stripped = s.replace(",", "").replace(" ", "").replace("$", "").replace("€", "");
      try {
        return Double.parseDouble(stripped);
      } catch (NumberFormatException e) {
        return 0.0;
      }
    }
    return 0.0;
  }
}
