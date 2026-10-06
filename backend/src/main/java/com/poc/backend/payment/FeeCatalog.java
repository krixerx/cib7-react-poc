package com.poc.backend.payment;

import com.poc.backend.pack.PackManifest;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads the service pack's state fees ({@code payment/<service>.yaml}): which processes charge a
 * fee, what it is called, who receives it and how the amount is set. The backend is the only place
 * that computes a fee: the checkout charges it, the provider callback must report exactly it
 * (docs/security.md rule 4), and the engine asks for it through {@code
 * /api/internal/payments/quote/<id>} before it renders the invoice, so invoice and checkout cannot
 * disagree.
 *
 * <p>An amount is flat, or tiered by one numeric case variable: the first tier whose {@code limit}
 * the value is below, else {@code otherwise}. The variable must be one the engine sets (a connector
 * output), never one a client may write; the engine's pack checks hold the pack to that. Strict
 * like every pack format: an unknown key or value stops the start.
 */
@Component
public class FeeCatalog {

  private static final Logger LOG = LoggerFactory.getLogger(FeeCatalog.class);

  static final int PLATFORM = PackManifest.PLATFORM_MAJOR;

  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final int MAX_TEXT = 120;

  /** One process's fee as the pack declares it. */
  public record Fee(
      String process, String service, String recipient, String currency, Amount amount) {}

  /** How the amount is set: {@code flat}, or {@code tiers} over {@code variable}. */
  public record Amount(BigDecimal flat, String variable, List<Tier> tiers, BigDecimal otherwise) {

    /** The amount for a case whose tier variable has {@code value} (ignored when flat). */
    public BigDecimal forValue(double value) {
      if (flat != null) {
        return flat;
      }
      for (Tier tier : tiers) {
        if (value < tier.limit().doubleValue()) {
          return tier.amount();
        }
      }
      return otherwise;
    }
  }

  /** Values below {@code limit} pay {@code amount}. */
  public record Tier(BigDecimal limit, BigDecimal amount) {}

  private final Map<String, Fee> fees;

  public FeeCatalog(@Value("${app.payment.locations:classpath*:payment/*.yaml}") String[] locations)
      throws IOException {
    this.fees = load(locations);
    LOG.info("State fees for: {}", fees.keySet());
  }

  /** The fee of a process definition, if it charges one. */
  public Optional<Fee> forProcess(String processDefinitionKey) {
    return Optional.ofNullable(fees.get(processDefinitionKey));
  }

  static Map<String, Fee> load(String... locations) throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Fee> loaded = new LinkedHashMap<>();
    for (String location : locations) {
      for (Resource resource : resolver.getResources(location.trim())) {
        Fee fee;
        try (InputStream in = resource.getInputStream()) {
          fee = parse(yaml.load(in));
        } catch (RuntimeException e) {
          throw new IllegalStateException(
              "Fee descriptor " + resource + " is invalid: " + e.getMessage(), e);
        }
        if (loaded.putIfAbsent(fee.process(), fee) != null) {
          throw new IllegalStateException(
              "Two fee descriptors for '" + fee.process() + "' (second: " + resource + ")");
        }
      }
    }
    return Map.copyOf(loaded);
  }

  static Fee parse(Object document) {
    Map<String, Object> root =
        map(
            document,
            "the document",
            Set.of("platform", "process", "service", "recipient", "currency", "amount"));
    Object platform = root.get("platform");
    if (!(platform instanceof Integer p) || p != PLATFORM) {
      throw new IllegalArgumentException("platform must be " + PLATFORM + ", was " + platform);
    }
    return new Fee(
        checked(root.get("process"), NAME, "process"),
        text(root.get("service"), "service"),
        text(root.get("recipient"), "recipient"),
        checked(root.get("currency"), CURRENCY, "currency"),
        amount(map(root.get("amount"), "amount", Set.of("flat", "tiers"))));
  }

  private static Amount amount(Map<String, Object> amount) {
    if (amount.size() != 1) {
      throw new IllegalArgumentException("amount must be exactly one of flat, tiers");
    }
    if (amount.containsKey("flat")) {
      return new Amount(money(amount.get("flat"), "amount.flat"), null, List.of(), null);
    }
    Map<String, Object> tiers =
        map(amount.get("tiers"), "amount.tiers", Set.of("variable", "below", "otherwise"));
    if (!(tiers.get("below") instanceof List<?> below) || below.isEmpty()) {
      throw new IllegalArgumentException("amount.tiers.below must be a non-empty list");
    }
    List<Tier> list = new ArrayList<>();
    for (int i = 0; i < below.size(); i++) {
      Map<String, Object> tier =
          map(below.get(i), "amount.tiers.below[" + i + "]", Set.of("limit", "amount"));
      Tier parsed =
          new Tier(
              money(tier.get("limit"), "amount.tiers.below[" + i + "].limit"),
              money(tier.get("amount"), "amount.tiers.below[" + i + "].amount"));
      if (!list.isEmpty() && parsed.limit().compareTo(list.get(list.size() - 1).limit()) <= 0) {
        throw new IllegalArgumentException("amount.tiers.below limits must ascend");
      }
      list.add(parsed);
    }
    return new Amount(
        null,
        checked(tiers.get("variable"), NAME, "amount.tiers.variable"),
        List.copyOf(list),
        money(tiers.get("otherwise"), "amount.tiers.otherwise"));
  }

  private static BigDecimal money(Object value, String what) {
    if (!(value instanceof Number n)) {
      throw new IllegalArgumentException(what + " must be a number");
    }
    BigDecimal amount = new BigDecimal(n.toString());
    if (amount.signum() <= 0 || amount.scale() > 2) {
      throw new IllegalArgumentException(what + " must be positive with at most 2 decimals");
    }
    return amount;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object node, String what, Set<String> allowed) {
    if (!(node instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException(what + " must be a mapping");
    }
    for (Object key : m.keySet()) {
      if (!allowed.contains(String.valueOf(key))) {
        throw new IllegalArgumentException(what + " has an unknown key '" + key + "'");
      }
    }
    return (Map<String, Object>) m;
  }

  private static String checked(Object value, Pattern pattern, String what) {
    if (!(value instanceof String s) || !pattern.matcher(s).matches()) {
      throw new IllegalArgumentException(what + " must match " + pattern + ", was " + value);
    }
    return s;
  }

  private static String text(Object value, String what) {
    if (!(value instanceof String s) || s.isBlank() || s.length() > MAX_TEXT) {
      throw new IllegalArgumentException(what + " must be a text of 1 to " + MAX_TEXT);
    }
    return s;
  }
}
