package com.poc.cib7.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the generated {@code variable-policy.json} files against the BPMN they protect and the MCP
 * manifests generated from the same spec. No Spring context: these are file checks, so a new
 * service without a policy fails here before it fails closed at runtime.
 */
class VariablePolicyFilesTest {

  private static final Path PROCESSES =
      Path.of(System.getProperty("services.pack.dir", "../packs/reference/engine"), "processes");
  private static final Path SERVICE_SPECS =
      Path.of("..", "packs", "reference", "docs", "business", "services");
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern PROCESS = Pattern.compile("<bpmn:process\\s[^>]*id=\"([^\"]+)\"");
  private static final Pattern FORM_KEY = Pattern.compile("camunda:formKey=\"([^\"]+)\"");
  private static final Pattern USER_TASK =
      Pattern.compile("<bpmn:userTask\\s([^>]*)>", Pattern.DOTALL);
  private static final Pattern SYSTEM_OUTPUT =
      Pattern.compile("(?:resultVariable|camunda:outputParameter name)=\"([^\"]+)\"");
  private static final Pattern DMN_OUTPUT =
      Pattern.compile("<(?:dmn:)?output\\s[^>]*name=\"([^\"]+)\"");

  /**
   * State that only the engine, a connector or the backend's service account may write: consent and
   * payment state (docs/security.md rules 3 and 4) and the gateway flags of the consent loops.
   */
  private static final Set<String> SYSTEM_OWNED =
      Set.of(
          "applicantToken",
          "ownerConfirmations",
          "founderSignatures",
          "consentRound",
          "partyId",
          "rejectedByOwner",
          "sentToProcess",
          "rejectedByFounder",
          "sentToRegister",
          "paymentReceived",
          "paymentReference",
          "paidAmount",
          "autoDecision");

  /**
   * Variables the SPA form writes but the MCP schema leaves out, per form id. Everything else must
   * match the manifest exactly.
   *
   * <ul>
   *   <li>Identity fields ({@code firstName}, {@code applicantName}, {@code applicantEmail}, ...):
   *       the SPA resubmits the prefilled value, the MCP path omits it and keeps the value {@code
   *       IdentityPopulationListener} set at start. Either way {@code IdentityValidationListener}
   *       rejects a value that differs from the Keycloak profile.
   *   <li>{@code sendBackReason} on applicant forms: the SPA clears the reviewer's banner text on
   *       resubmit; it drives no gateway.
   *   <li>Business registration: the SPA collects residency, co-founders and the articles of
   *       association document, which the MCP surface deliberately does not offer.
   * </ul>
   */
  private static final Map<String, Set<String>> SPA_ONLY =
      Map.of(
          "owner-vehicle", Set.of("firstName", "lastName", "applicantEmail", "sendBackReason"),
          "business-details",
              Set.of(
                  "applicantFirstName",
                  "applicantLastName",
                  "applicantEmail",
                  "applicantResidency",
                  "additionalFounders",
                  "pendingAoaDocument"));

  @Tag("pack")
  @Test
  void everyProcessAndFormKeyHasAPolicyEntry() throws IOException {
    for (Path service : services()) {
      VariablePolicy policy = policy(service).orElse(null);
      if (policy == null) {
        fail(service.getFileName() + " has BPMN but no variable-policy.json");
      }
      Set<String> formIds = new TreeSet<>();
      for (Path bpmn : files(service, ".bpmn")) {
        String xml = Files.readString(bpmn);
        Matcher process = PROCESS.matcher(xml);
        while (process.find()) {
          assertEquals(
              process.group(1),
              policy.processDefinitionKey(),
              bpmn + ": policy is for a different process definition key");
        }
        Matcher formKey = FORM_KEY.matcher(xml);
        while (formKey.find()) {
          formIds.add(VariablePolicy.formId(formKey.group(1)));
        }
      }
      assertEquals(
          formIds,
          new TreeSet<>(policy.forms().keySet()),
          service.getFileName() + ": policy forms must match the BPMN formKeys exactly");
    }
  }

  @Tag("pack")
  @Test
  void noPolicyListsSystemOwnedOrEngineSetVariables() throws IOException {
    for (Path service : services()) {
      VariablePolicy policy = policy(service).orElseThrow();
      Set<String> forbidden = new TreeSet<>(SYSTEM_OWNED);
      forbidden.addAll(VariablePolicyRegistry.NEVER_WRITABLE);
      for (Path bpmn : files(service, ".bpmn")) {
        collect(SYSTEM_OUTPUT, Files.readString(bpmn), forbidden);
      }
      for (Path dmn : files(service, ".dmn")) {
        collect(DMN_OUTPUT, Files.readString(dmn), forbidden);
      }
      Map<String, Set<String>> lists = new LinkedHashMap<>(policy.forms());
      lists.put("(start)", policy.start());
      lists.forEach(
          (form, names) -> {
            for (String name : names) {
              assertFalse(
                  forbidden.contains(name),
                  service.getFileName() + " " + form + ": '" + name + "' is system-owned");
            }
          });
    }
  }

  @Tag("pack")
  @Test
  void onlyReviewerFormsMayWriteADecision() throws IOException {
    for (Path service : services()) {
      VariablePolicy policy = policy(service).orElseThrow();
      Map<String, Boolean> applicantForm = new HashMap<>();
      for (Path bpmn : files(service, ".bpmn")) {
        Matcher task = USER_TASK.matcher(Files.readString(bpmn));
        while (task.find()) {
          Matcher formKey = FORM_KEY.matcher(task.group(1));
          if (formKey.find()) {
            applicantForm.put(
                VariablePolicy.formId(formKey.group(1)),
                task.group(1).contains("camunda:assignee=\"${initiator}\""));
          }
        }
      }
      assertFalse(
          policy.start().contains("decision"), service.getFileName() + ": start sets decision");
      policy
          .forms()
          .forEach(
              (form, names) -> {
                if (applicantForm.getOrDefault(form, true)) {
                  assertFalse(
                      names.contains("decision"),
                      service.getFileName() + ": applicant form " + form + " writes decision");
                }
              });
    }
  }

  @Test
  void policiesMatchTheMcpManifests() throws IOException {
    for (Path service : services()) {
      VariablePolicy policy = policy(service).orElseThrow();
      Path manifestFile =
          SERVICE_SPECS.resolve(service.getFileName()).resolve("build/mcp-service.json");
      assertTrue(Files.exists(manifestFile), manifestFile + " missing");
      JsonNode manifest = JSON.readTree(manifestFile.toFile());

      assertEquals(
          policy.start(),
          properties(manifest.path("variables")),
          service.getFileName() + ": start policy must equal the MCP start variables");

      for (JsonNode task : manifest.path("userTasks")) {
        String form = VariablePolicy.formId(task.path("formKey").asText());
        Set<String> mcp = properties(task.path("schema"));
        Set<String> allowed = policy.form(form).orElse(Set.of());
        Set<String> expected = new TreeSet<>(mcp);
        expected.addAll(SPA_ONLY.getOrDefault(form, Set.of()));
        assertEquals(
            expected,
            new TreeSet<>(allowed),
            service.getFileName()
                + " "
                + form
                + ": policy must equal the MCP schema plus the documented SPA-only fields");
      }
    }
  }

  @Test
  void registryRefusesReservedNames() {
    for (String reserved : List.of("busBaseUrl", "frontendBaseUrl", "pdf", "initiator")) {
      JsonNode policy =
          JSON.createObjectNode()
              .put("processDefinitionKey", "x")
              .set("start", JSON.createArrayNode().add(reserved));
      assertThrows(
          IllegalStateException.class,
          () -> VariablePolicyRegistry.parse(policy, "test"),
          reserved);
    }
  }

  @Test
  void registryWithoutPolicyAllowsNothing() {
    VariablePolicyRegistry registry = new VariablePolicyRegistry(Map.of());
    assertTrue(registry.forProcess("someNewService").isEmpty());
  }

  private static Set<String> properties(JsonNode schema) {
    Set<String> names = new TreeSet<>();
    schema.path("properties").fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static void collect(Pattern pattern, String text, Set<String> into) {
    Matcher m = pattern.matcher(text);
    while (m.find()) {
      into.add(m.group(1));
    }
  }

  private static List<Path> services() throws IOException {
    try (Stream<Path> dirs = Files.list(PROCESSES)) {
      List<Path> services = dirs.filter(Files::isDirectory).sorted().toList();
      assertFalse(services.isEmpty(), "no services under " + PROCESSES.toAbsolutePath());
      return services;
    }
  }

  private static List<Path> files(Path service, String suffix) throws IOException {
    try (Stream<Path> files = Files.list(service)) {
      return files.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().toList();
    }
  }

  private static Optional<VariablePolicy> policy(Path service) throws IOException {
    Path file = service.resolve("variable-policy.json");
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    return Optional.of(VariablePolicyRegistry.parse(JSON.readTree(file.toFile()), file.toString()));
  }
}
