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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the generated {@code variable-policy.json} files against the BPMN they protect and the MCP
 * manifests generated from the same spec. No Spring context: these are file checks, so a new
 * service without a policy fails here before it fails closed at runtime.
 */
class VariablePolicyFilesTest {

  private static final Path PROCESSES =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"), "processes");
  private static final Path SERVICE_SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern PROCESS = Pattern.compile("<bpmn:process\\s[^>]*id=\"([^\"]+)\"");
  private static final Pattern FORM_KEY = Pattern.compile("camunda:formKey=\"([^\"]+)\"");
  private static final Pattern USER_TASK =
      Pattern.compile("<bpmn:userTask\\s([^>]*)>", Pattern.DOTALL);
  private static final Pattern SYSTEM_OUTPUT =
      Pattern.compile("(?:resultVariable|camunda:outputParameter name)=\"([^\"]+)\"");
  private static final Pattern DMN_OUTPUT =
      Pattern.compile("<(?:dmn:)?output\\s[^>]*name=\"([^\"]+)\"");

  /** The pack's co-signing descriptors, beside its engine/ folder. */
  private static final Path CONSENT = PROCESSES.getParent().resolveSibling("backend/consent");

  /**
   * The co-signing state the backend's service account writes for a pack: every variable a consent
   * descriptor names except the parties list, which the applicant's form submits (docs/security.md
   * rule 3). Core-owned state (round, party, payment) is in {@link
   * VariablePolicyRegistry#NEVER_WRITABLE}, which the engine enforces at startup.
   */
  private static Set<String> consentOwned() throws IOException {
    Set<String> names = new TreeSet<>();
    if (!Files.isDirectory(CONSENT)) {
      return names;
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    try (Stream<Path> files = Files.list(CONSENT)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
        Map<?, ?> descriptor = yaml.load(Files.readString(file));
        if (descriptor.get("variables") instanceof Map<?, ?> variables) {
          variables.forEach(
              (role, name) -> {
                if (!"parties".equals(role)) {
                  names.add(String.valueOf(name));
                }
              });
        }
      }
    }
    return names;
  }

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
      Set<String> forbidden = consentOwned();
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

  /**
   * Each form's policy equals what the MCP manifest offers plus what it declares as not offered
   * (fields only the portal form writes: identity fields it resubmits, a cleared banner text, parts
   * of the form the agent surface leaves out). A field offered but not writable makes complete_task
   * fail with 403; a writable field the manifest neither offers nor declares is an unreviewed open
   * write.
   */
  @Tag("pack")
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
          union(manifest.path("start")),
          service.getFileName() + ": start policy must equal the MCP start fields and notOffered");

      for (JsonNode task : manifest.path("userTasks")) {
        String form = VariablePolicy.formId(task.path("formKey").asText());
        Set<String> offered = fieldNames(task.path("fields"));
        for (JsonNode name : task.path("notOffered")) {
          assertFalse(
              offered.contains(name.asText()),
              service.getFileName()
                  + " "
                  + form
                  + ": '"
                  + name.asText()
                  + "' is both offered and not");
        }
        assertEquals(
            union(task),
            new TreeSet<>(policy.form(form).orElse(Set.of())),
            service.getFileName()
                + " "
                + form
                + ": policy must equal the MCP fields plus the manifest's notOffered");
      }
    }
  }

  private static Set<String> union(JsonNode part) {
    Set<String> names = fieldNames(part.path("fields"));
    part.path("notOffered").forEach(n -> names.add(n.asText()));
    return names;
  }

  @Test
  void registryRefusesReservedNames() {
    for (String reserved :
        List.of(
            "busBaseUrl",
            "frontendBaseUrl",
            "pdf",
            "initiator",
            "consentRound",
            "partyId",
            "paymentReceived",
            "paidAmount")) {
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

  private static Set<String> fieldNames(JsonNode fields) {
    Set<String> names = new TreeSet<>();
    fields.fieldNames().forEachRemaining(names::add);
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
