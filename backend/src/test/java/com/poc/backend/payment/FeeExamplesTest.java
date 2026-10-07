package com.poc.backend.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.pack.SpecTables;
import com.poc.backend.payment.FeeSchedule.Charge;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Runs every service's fee examples through the backend's own fee code (docs/platform-api.md,
 * "Service examples"): what a case costs is pack data, so it is checked with the pack.
 *
 * <p>A service README with a {@code ## State fee} section has a {@code ### Fee examples} table: for
 * a tiered fee one column named after the tier variable (its value as a JSON literal, as the engine
 * may hold it: {@code `4999`}, {@code `"38,000"}, {@code `null`}) and one {@code Amount} column;
 * for a flat fee only {@code Amount}. The section's item table's {@code Recipient} and {@code
 * Currency} must be what the generated {@code payment/<service>.yaml} charges.
 */
@Tag("pack")
class FeeExamplesTest {

  private static final Path BACKEND =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/backend"));
  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final String PI = "pi-fee-example";

  @Test
  void everyFeeChargesWhatItsSpecsExamplesSay() throws IOException {
    FeeCatalog catalog = new FeeCatalog(new String[] {"classpath*:payment/*.yaml"});
    List<String> problems = new ArrayList<>();
    for (Path readme : readmes()) {
      String service = readme.getParent().getFileName().toString();
      String md = Files.readString(readme);
      Optional<SpecTables.Table> items = SpecTables.under(md, "State fee");
      if (items.isEmpty()) {
        continue;
      }
      String at = service + "/README.md";
      String process = processOf(service);
      if (process == null) {
        problems.add(at + ": has a State fee but no payment/" + service + ".yaml");
        continue;
      }
      SpecTables.Table examples = SpecTables.under(md, "Fee examples").orElse(null);
      if (examples == null || examples.rows().isEmpty()) {
        problems.add(at + ": has no ### Fee examples table");
        continue;
      }
      String variable =
          examples.header().stream().filter(h -> !h.equals("Amount")).findFirst().orElse(null);
      int row = 0;
      for (Map<String, String> example : examples.rows()) {
        row++;
        EngineClient engine = mock(EngineClient.class);
        try {
          if (variable != null) {
            when(engine.getRawVariable(PI, variable)).thenReturn(value(example.get(variable)));
          }
          Optional<Charge> charge =
              new FeeSchedule(engine, catalog).chargeFor(new ProcessInstanceRef(PI, process));
          BigDecimal expected = new BigDecimal(SpecTables.unquote(example.get("Amount")));
          if (charge.isEmpty()) {
            problems.add(at + " row " + row + ": no charge for " + process);
          } else if (charge.get().amount().compareTo(expected) != 0) {
            problems.add(
                at + " row " + row + ": " + charge.get().amount() + ", expected " + expected);
          } else if (row == 1) {
            checkItem(at, items.get(), "Recipient", charge.get().recipient(), problems);
            checkItem(at, items.get(), "Currency", charge.get().currency(), problems);
          }
        } catch (IOException | NumberFormatException e) {
          problems.add(at + " row " + row + ": " + e.getMessage());
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  private static void checkItem(
      String at, SpecTables.Table items, String item, String charged, List<String> problems) {
    String specified =
        items.rows().stream()
            .filter(r -> item.equals(r.get(items.header().get(0))))
            .map(r -> SpecTables.unquote(r.get(items.header().get(1))))
            .findFirst()
            .orElse(null);
    if (!charged.equals(specified)) {
      problems.add(
          at + ": " + item + " is '" + specified + "' but the fee charges '" + charged + "'");
    }
  }

  /** The process a service's fee belongs to, from its generated descriptor. */
  private static String processOf(String service) throws IOException {
    Path file = BACKEND.resolve("payment").resolve(service + ".yaml");
    if (!Files.exists(file)) {
      return null;
    }
    Map<?, ?> fee = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
    return String.valueOf(fee.get("process"));
  }

  /**
   * A cell as a JSON literal, the way the engine would hold the variable: {@code null}, an integer,
   * a decimal, or a string in double quotes.
   */
  private static Object value(String cell) throws IOException {
    String v = SpecTables.unquote(cell);
    if (v.equals("null")) {
      return null;
    }
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      return v.substring(1, v.length() - 1).replace("\\\"", "\"");
    }
    try {
      if (v.matches("-?\\d+")) {
        long n = Long.parseLong(v);
        return n == (int) n ? (Object) (int) n : (Object) n;
      }
      return Double.parseDouble(v);
    } catch (NumberFormatException e) {
      throw new IOException("not a JSON literal: " + cell);
    }
  }

  private static List<Path> readmes() throws IOException {
    if (!Files.isDirectory(SPECS)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(SPECS)) {
      return dirs.map(d -> d.resolve("README.md")).filter(Files::exists).sorted().toList();
    }
  }
}
