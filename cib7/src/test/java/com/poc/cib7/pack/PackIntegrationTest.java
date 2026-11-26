package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What a pack may call from its BPMN and what its own Patchbay packages may hold
 * (docs/platform-api.md, "Integrations offered to a pack"; openspec change
 * replace-esb-with-patchbay, capability pack-integration-boundary). The engine reaches outside only
 * through Patchbay integrations and the backend's own paths, and adds each credential itself; these
 * rules keep a pack's BPMN on those destinations and keep a pack's packages from replacing a core
 * integration, naming an address or carrying a secret. Tagged {@code pack}, so {@code
 * scripts/pack-check.sh} runs it against any pack directory. Every rule has a negative case on an
 * inline fixture below, so a rule that stopped firing fails here rather than passing silently.
 */
@Tag("pack")
class PackIntegrationTest {

  private static final String CAMUNDA = "http://camunda.org/schema/1.0/bpmn";

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/reference/engine"));

  /** The pack's other parts sit beside its {@code engine/} folder. */
  private static final Path PACK = ENGINE.toAbsolutePath().normalize().getParent();

  private static final String BACKEND = "${backendBaseUrl}";

  private static final String PATCHBAY = "${patchbayBaseUrl}";

  private static final String INTEGRATIONS = PATCHBAY + "/api/v1/integrations/";

  /** The integrations of the core's own packages (patchbay/packages/); a pack may call these. */
  private static final Set<String> CORE_SLUGS = Set.of("mail-send", "pdf-render");

  private static final Pattern ZIP_COMMAND = Pattern.compile("(^|[\s;&(|])zip\s+-");

  private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]*");

  private static final Set<String> FIXED_PATHS =
      Set.of(
          "/api/internal/documents/move-pending",
          "/api/internal/documents/server-upload",
          "/api/internal/payments/quote/${execution.processInstanceId}");

  private static final Pattern REGISTRY =
      Pattern.compile("^/api/(public|internal)/registry/([a-z][a-z0-9-]*)(/.*)?$");

  private static final ObjectMapper JSON = new ObjectMapper();

  // ---------------------------------------------------------------------------------------------
  // Connector URLs in the pack's BPMN
  // ---------------------------------------------------------------------------------------------

  /**
   * Why a connector URL is refused, or empty when the platform API offers that destination to a
   * pack. {@code registries} maps each declared registry entity to the access classes its
   * operations use; {@code packSlugs} are the integrations the pack's own packages declare.
   */
  static Optional<String> connectorUrlProblem(
      String url, Map<String, Set<String>> registries, Set<String> packSlugs) {
    if (url.contains("${busBaseUrl}")) {
      return Optional.of(
          "uses ${busBaseUrl}, which platform API 3.0 removed: the pack needs platform API 3.0"
              + " (call "
              + INTEGRATIONS
              + "<slug> or "
              + BACKEND
              + "/api/...)");
    }
    String lower = url.toLowerCase(Locale.ROOT);
    if (url.contains("..")
        || url.contains("?")
        || url.contains("#")
        || lower.contains("%2e")
        || lower.contains("%2f")
        || url.contains("\\")) {
      return Optional.of("must be a plain path, without '..', a query, a fragment or escapes");
    }
    if (url.startsWith(INTEGRATIONS)) {
      String slug = url.substring(INTEGRATIONS.length());
      if (!SLUG.matcher(slug).matches()) {
        return Optional.of("integration slug '" + slug + "' must be one plain segment");
      }
      if (!CORE_SLUGS.contains(slug) && !packSlugs.contains(slug)) {
        return Optional.of(
            "integration '"
                + slug
                + "' is not a core integration and no package of the pack declares it");
      }
      return Optional.empty();
    }
    if (url.startsWith(BACKEND + "/")) {
      String path = url.substring(BACKEND.length());
      if (FIXED_PATHS.contains(path)) {
        return Optional.empty();
      }
      Matcher registry = REGISTRY.matcher(path);
      if (registry.matches()) {
        Set<String> access = registries.get(registry.group(2));
        if (access == null) {
          return Optional.of("registry '" + registry.group(2) + "' is not declared in the pack");
        }
        return access.contains(registry.group(1))
            ? Optional.empty()
            : Optional.of(
                "registry '" + registry.group(2) + "' has no " + registry.group(1) + " operation");
      }
      return Optional.of("is not a backend path the platform API offers a pack");
    }
    return Optional.of(
        "must start with "
            + INTEGRATIONS
            + " or "
            + BACKEND
            + "/: a connector calls no other host");
  }

  /** Every connector rule a BPMN document breaks, each line naming the file's connector. */
  static List<String> connectorProblems(
      String file, String bpmnXml, Map<String, Set<String>> registries, Set<String> packSlugs) {
    List<String> problems = new ArrayList<>();
    NodeList connectors;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      connectors =
          factory
              .newDocumentBuilder()
              .parse(new InputSource(new StringReader(bpmnXml)))
              .getElementsByTagNameNS(CAMUNDA, "connector");
    } catch (Exception e) {
      return List.of(file + ": not readable BPMN: " + e.getMessage());
    }
    for (int i = 0; i < connectors.getLength(); i++) {
      Element connector = (Element) connectors.item(i);
      String at =
          file + " " + ((Element) connector.getParentNode().getParentNode()).getAttribute("id");
      for (String header : CREDENTIAL_HEADERS) {
        if (allText(connector).toLowerCase(Locale.ROOT).contains(header)) {
          problems.add(
              at + ": sets " + header + "; the engine adds credentials, a pack never does");
        }
      }
      Element url = urlParameter(connector);
      if (url == null) {
        problems.add(at + ": connector without a url input parameter");
        continue;
      }
      if (url.getElementsByTagName("*").getLength() > 0) {
        problems.add(at + ": url must be a plain value, not a script or a map");
        continue;
      }
      String value = url.getTextContent().strip();
      connectorUrlProblem(value, registries, packSlugs)
          .ifPresent(p -> problems.add(at + ": " + value + " " + p));
    }
    return problems;
  }

  private static final Set<String> CREDENTIAL_HEADERS = Set.of("x-internal-token", "x-api-key");

  /** All attribute values and text under an element: a header name may be a key or a script. */
  private static String allText(Node node) {
    StringBuilder out = new StringBuilder();
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      out.append(node.getNodeValue()).append('\n');
    }
    NamedNodeMap attributes = node.getAttributes();
    for (int i = 0; attributes != null && i < attributes.getLength(); i++) {
      out.append(attributes.item(i).getNodeValue()).append('\n');
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      out.append(allText(child));
    }
    return out.toString();
  }

  private static Element urlParameter(Element connector) {
    NodeList params = connector.getElementsByTagNameNS(CAMUNDA, "inputParameter");
    for (int i = 0; i < params.getLength(); i++) {
      Element param = (Element) params.item(i);
      if ("url".equals(param.getAttribute("name"))) {
        return param;
      }
    }
    return null;
  }

  private static Map<String, Set<String>> declaredRegistries() throws IOException {
    Map<String, Set<String>> out = new TreeMap<>();
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    for (Path file : files(PACK.resolve("backend/registry"), ".yaml")) {
      Map<?, ?> descriptor = yaml.load(Files.readString(file));
      Set<String> access = new TreeSet<>();
      if (descriptor.get("operations") instanceof Map<?, ?> operations) {
        for (Object operation : operations.values()) {
          if (operation instanceof Map<?, ?> op && op.get("access") != null) {
            access.add(op.get("access").toString());
          }
        }
      }
      out.put(String.valueOf(descriptor.get("entity")), access);
    }
    return out;
  }

  /** The integrations the pack's own packages declare, from each manifest and services/ file. */
  private static Set<String> declaredSlugs(Path patchbay) throws IOException {
    Set<String> slugs = new TreeSet<>();
    for (Path dir : packageDirs(patchbay)) {
      Path manifest = dir.resolve("manifest.json");
      if (Files.isRegularFile(manifest)) {
        for (JsonNode integration : JSON.readTree(manifest.toFile()).path("integrations")) {
          slugs.add(integration.path("id").asText());
        }
      }
      for (Path service : files(dir.resolve("services"), ".json")) {
        if (service.getParent().getFileName().toString().equals("services")) {
          String name = service.getFileName().toString();
          slugs.add(name.substring(0, name.length() - ".json".length()));
        }
      }
    }
    return slugs;
  }

  @Test
  void connectorsCallOnlyTheDestinationsOfferedToAPack() throws Exception {
    Map<String, Set<String>> registries = declaredRegistries();
    Set<String> slugs = declaredSlugs(PACK.resolve("patchbay"));
    List<String> problems = new ArrayList<>();
    for (Path bpmn : files(ENGINE.resolve("processes"), ".bpmn")) {
      problems.addAll(
          connectorProblems(
              bpmn.getFileName().toString(), Files.readString(bpmn), registries, slugs));
    }
    assertEquals(List.of(), problems);
  }

  private static String bpmn(String url, String headers) {
    return """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="d">
          <process id="p">
            <serviceTask id="t">
              <extensionElements>
                <camunda:connector>
                  <camunda:connectorId>http-connector</camunda:connectorId>
                  <camunda:inputOutput>
                    <camunda:inputParameter name="method">POST</camunda:inputParameter>
                    <camunda:inputParameter name="url">%s</camunda:inputParameter>
                    %s
                  </camunda:inputOutput>
                </camunda:connector>
              </extensionElements>
            </serviceTask>
          </process>
        </definitions>
        """
        .formatted(url, headers);
  }

  private static List<String> connectorProblemsOf(String url, String headers) {
    return connectorProblems(
        "fixture.bpmn",
        bpmn(url, headers),
        Map.of("vehicles", Set.of("public")),
        Set.of("pack-land-registry"));
  }

  @Test
  void allowedDestinationsPass() {
    for (String allowed :
        List.of(
            INTEGRATIONS + "mail-send",
            INTEGRATIONS + "pdf-render",
            INTEGRATIONS + "pack-land-registry",
            BACKEND + "/api/internal/documents/server-upload",
            BACKEND + "/api/internal/documents/move-pending",
            BACKEND + "/api/public/registry/vehicles/${objectId}",
            BACKEND + "/api/internal/payments/quote/${execution.processInstanceId}")) {
      assertEquals(List.of(), connectorProblemsOf(allowed, ""), allowed);
    }
  }

  @Test
  void aBackendInternalPathPasses() {
    // Scenario: Connector to an offered backend path.
    assertEquals(
        List.of(), connectorProblemsOf(BACKEND + "/api/internal/documents/server-upload", ""));
  }

  @Test
  void anUndeclaredIntegrationIsRefusedNamingTheConnectorAndTheSlug() {
    List<String> problems = connectorProblemsOf(INTEGRATIONS + "pack-unknown", "");
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("fixture.bpmn t"), problems.get(0));
    assertTrue(problems.get(0).contains("pack-unknown"), problems.get(0));
  }

  @Test
  void aLiteralHostIsRefused() {
    List<String> problems = connectorProblemsOf("http://backend:8085/api/internal/cases", "");
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("must start with"), problems.get(0));
  }

  @Test
  void theRemovedBusAddressIsRefusedWithThePlatformVersion() {
    List<String> problems = connectorProblemsOf("${busBaseUrl}/api/v1/send", "");
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("platform API 3.0"), problems.get(0));
  }

  @Test
  void otherDestinationsThePackMayNotCallAreRefused() {
    Map<String, String> refused = new LinkedHashMap<>();
    // A host that merely starts like the bean, or hides behind it.
    refused.put(PATCHBAY + ".evil.example/api/v1/integrations/mail-send", "must start with");
    refused.put(PATCHBAY + "@evil.example/api/v1/integrations/mail-send", "must start with");
    refused.put(PATCHBAY + "/api/v1/integrations", "must start with");
    refused.put("${frontendBaseUrl}/api/v1/integrations/mail-send", "must start with");
    // Patchbay's own surfaces other than the integration API.
    refused.put(PATCHBAY + "/admin/integrations", "must start with");
    refused.put(PATCHBAY + "/actuator/health", "must start with");
    // Slugs that are not one plain segment, or not core and not declared.
    refused.put(INTEGRATIONS + "mail-send/extra", "one plain segment");
    refused.put(INTEGRATIONS + "mail-send/", "one plain segment");
    refused.put(INTEGRATIONS + "Mail-Send", "one plain segment");
    refused.put(INTEGRATIONS + "mailsend", "no package of the pack declares it");
    // A pack slug is declared by a package of this pack, not by any pack.
    refused.put(INTEGRATIONS + "pack-other", "no package of the pack declares it");
    // Dot segments, queries, fragments and their escaped forms.
    refused.put(INTEGRATIONS + "mail-send?target=evil", "plain path");
    refused.put(INTEGRATIONS + "mail-send#x", "plain path");
    refused.put(INTEGRATIONS + "../../admin/integrations", "plain path");
    refused.put(INTEGRATIONS + "%2e%2e/admin", "plain path");
    refused.put(BACKEND + "/api/internal/documents/%2e%2e/cases", "plain path");
    refused.put(BACKEND + "/api/public/registry/vehicles/../../internal/case-index", "plain path");
    // Backend paths the platform API does not offer, or a registry the pack does not declare.
    refused.put(BACKEND + "/api/internal/case-index", "not a backend path");
    refused.put(BACKEND + "/api/internal/documents/server-upload/extra", "not a backend path");
    refused.put(BACKEND + "/engine-rest/process-definition", "not a backend path");
    refused.put(BACKEND + "/pack/land-registry/parcels/1", "not a backend path");
    refused.put(BACKEND + "/api/internal/registry/vehicles", "has no internal operation");
    refused.put(BACKEND + "/api/public/registry/people/${id}", "not declared");
    refused.put(BACKEND + "api/internal/documents/server-upload", "must start with");
    refused.forEach(
        (url, reason) -> {
          List<String> problems = connectorProblemsOf(url, "");
          assertEquals(1, problems.size(), url + " -> " + problems);
          assertTrue(problems.get(0).contains(reason), url + " -> " + problems.get(0));
        });
  }

  @Test
  void aConnectorMustHaveAPlainUrlParameter() {
    String noUrl = bpmn("x", "").replace("name=\"url\"", "name=\"address\"");
    List<String> problems = connectorProblems("f.bpmn", noUrl, Map.of(), Set.of());
    assertTrue(problems.get(0).contains("without a url"), problems.toString());
    String scripted = bpmn("<camunda:script scriptFormat=\"groovy\">x</camunda:script>", "");
    problems = connectorProblems("f.bpmn", scripted, Map.of(), Set.of());
    assertTrue(problems.get(0).contains("plain value"), problems.toString());
  }

  @Test
  void aConnectorThatSetsACredentialHeaderIsRefused() {
    for (String header :
        List.of("X-Internal-Token", "x-internal-token", "X-API-Key", "X-Api-Key")) {
      String headers =
          "<camunda:inputParameter name=\"headers\"><camunda:map>"
              + "<camunda:entry key=\"Content-Type\">application/json</camunda:entry>"
              + "<camunda:entry key=\""
              + header
              + "\">${secret}</camunda:entry>"
              + "</camunda:map></camunda:inputParameter>";
      List<String> problems = connectorProblemsOf(INTEGRATIONS + "mail-send", headers);
      assertEquals(1, problems.size(), header + " -> " + problems);
      assertTrue(problems.get(0).contains(header.toLowerCase(Locale.ROOT)), problems.get(0));
    }
    // The header named in a script, not as a map key.
    String scripted =
        "<camunda:inputParameter name=\"headers\"><camunda:script scriptFormat=\"juel\">"
            + "${{'X-Internal-Token': 'x'}}</camunda:script></camunda:inputParameter>";
    assertEquals(1, connectorProblemsOf(INTEGRATIONS + "mail-send", scripted).size());
    // Ordinary headers are fine.
    String ordinary =
        "<camunda:inputParameter name=\"headers\"><camunda:map>"
            + "<camunda:entry key=\"Content-Type\">application/json</camunda:entry>"
            + "</camunda:map></camunda:inputParameter>";
    assertEquals(List.of(), connectorProblemsOf(INTEGRATIONS + "mail-send", ordinary));
  }

  // ---------------------------------------------------------------------------------------------
  // The pack's own Patchbay packages: patchbay/<package>/
  // ---------------------------------------------------------------------------------------------

  private static final String PREFIX = "pack-";

  private static List<Path> packageDirs(Path patchbay) throws IOException {
    if (!Files.isDirectory(patchbay)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(patchbay)) {
      return list.filter(Files::isDirectory).sorted().toList();
    }
  }

  private static void checkId(List<String> problems, Path dir, String what, JsonNode id) {
    if (id == null || !id.isTextual() || id.asText().isEmpty()) {
      problems.add(dir.getFileName() + ": " + what + " is missing");
    } else if (!id.asText().startsWith(PREFIX)) {
      problems.add(
          dir.getFileName() + ": " + what + " '" + id.asText() + "' must start with " + PREFIX);
    }
  }

  private static void noBaseUrl(List<String> problems, Path file, JsonNode node) {
    node.fields()
        .forEachRemaining(
            e -> {
              if (e.getKey().equals("baseUrl")) {
                problems.add(file + ": has baseUrl; a pack's address comes from the environment");
              }
              noBaseUrl(problems, file, e.getValue());
            });
    if (node.isArray()) {
      node.forEach(child -> noBaseUrl(problems, file, child));
    }
  }

  /** Every rule one package folder breaks; empty when it keeps them all. */
  static List<String> packageProblems(Path dir) throws IOException {
    List<String> problems = new ArrayList<>();
    String name = dir.getFileName().toString();
    for (String forbidden : List.of("connections", "secrets")) {
      if (Files.exists(dir.resolve(forbidden))) {
        problems.add(
            name + ": has " + forbidden + "/; a pack's package holds no address or secret");
      }
    }
    Path manifest = dir.resolve("manifest.json");
    if (!Files.isRegularFile(manifest)) {
      problems.add(name + ": has no manifest.json");
    } else {
      JsonNode root = JSON.readTree(manifest.toFile());
      if (!"patchbay-package".equals(root.path("format").asText())) {
        problems.add(name + ": manifest format must be patchbay-package");
      }
      if (root.path("formatVersion").asInt(-1) != 1) {
        problems.add(name + ": manifest formatVersion must be 1");
      }
      for (JsonNode integration : root.path("integrations")) {
        checkId(problems, dir, "integration slug", integration.get("id"));
      }
    }
    for (Path file : files(dir.resolve("entities"), ".json")) {
      checkId(problems, dir, "payload definition id", JSON.readTree(file.toFile()).get("id"));
    }
    for (Path file : files(dir.resolve("contracts"), ".json")) {
      if (!file.getFileName().toString().endsWith(".openapi.json")) {
        checkId(problems, dir, "contract id", JSON.readTree(file.toFile()).get("id"));
      }
    }
    for (Path file : files(dir.resolve("services"), ".json")) {
      JsonNode node = JSON.readTree(file.toFile());
      noBaseUrl(problems, dir.relativize(file), node);
      boolean version = file.getParent().getFileName().toString().endsWith(".versions");
      JsonNode service = version ? node.path("service") : node;
      checkId(problems, dir, "integration slug", service.get("id"));
      checkId(problems, dir, "input definition", service.get("input"));
      checkId(problems, dir, "output definition", service.get("output"));
      if (!version) {
        checkId(problems, dir, "contract reference", service.get("contract"));
      }
      checkId(problems, dir, "connection id", service.path("target").get("connection"));
      if (version) {
        checkId(problems, dir, "version input definition id", node.path("input").get("id"));
        checkId(problems, dir, "version output definition id", node.path("output").get("id"));
      }
    }
    return problems;
  }

  @Test
  void packsPatchbayPackagesKeepTheirBoundary() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path dir : packageDirs(PACK.resolve("patchbay"))) {
      problems.addAll(packageProblems(dir));
    }
    assertEquals(List.of(), problems);
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  /** A package as Patchbay 0.1.0 exports it, with every id this pack's own. */
  private static Path goodPackage(Path root) throws IOException {
    Path dir = root.resolve("land-registry");
    write(
        dir.resolve("manifest.json"),
        """
        {"format":"patchbay-package","formatVersion":1,"integrations":[
          {"id":"pack-land-registry","name":"pack-land-registry","activeVersion":"1.0.0","versions":["1.0.0"]}]}
        """);
    write(
        dir.resolve("entities/pack-land-lookup.json"),
        "{\"id\":\"pack-land-lookup\",\"fields\":[]}");
    write(
        dir.resolve("entities/pack-land-lookup-result.json"),
        "{\"id\":\"pack-land-lookup-result\",\"fields\":[]}");
    write(
        dir.resolve("contracts/pack-land-system.json"),
        "{\"id\":\"pack-land-system\",\"sourceFile\":\"pack-land-system.openapi.json\"}");
    write(dir.resolve("contracts/pack-land-system.openapi.json"), "{\"openapi\":\"3.0.0\"}");
    write(
        dir.resolve("services/pack-land-registry.json"),
        """
        {"id":"pack-land-registry","input":"pack-land-lookup","output":"pack-land-lookup-result",
         "contract":"pack-land-system","target":{"method":"GET","path":"/parcels","connection":"pack-land-system"}}
        """);
    write(
        dir.resolve("services/pack-land-registry.versions/1.0.0.json"),
        """
        {"version":"1.0.0","service":{"id":"pack-land-registry","version":"1.0.0",
          "input":"pack-land-lookup","output":"pack-land-lookup-result",
          "target":{"method":"GET","path":"/parcels","connection":"pack-land-system"}},
         "input":{"id":"pack-land-lookup"},"output":{"id":"pack-land-lookup-result"}}
        """);
    return dir;
  }

  private static void replaceIn(Path dir, String relative, String from, String to)
      throws IOException {
    Path file = dir.resolve(relative);
    String text = Files.readString(file);
    assertTrue(text.contains(from), relative + " has no " + from);
    Files.writeString(file, text.replace(from, to));
  }

  @Test
  void aPackageThatKeepsTheRulesPasses(@TempDir Path root) throws IOException {
    assertEquals(List.of(), packageProblems(goodPackage(root)));
    assertEquals(Set.of("pack-land-registry"), declaredSlugs(root));
  }

  @Test
  void aPackageIsRefusedWhenItBreaksARule(@TempDir Path root) throws IOException {
    Map<String, String> reasons = new TreeMap<>();
    int n = 0;

    // Scenario: A pack package redefines the core mail model.
    Path p = goodPackage(root.resolve("c" + ++n));
    write(p.resolve("entities/mail-send.json"), "{\"id\":\"mail-send\",\"fields\":[]}");
    reasons.put(p.toString(), "payload definition id 'mail-send' must start with pack-");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(p, "manifest.json", "\"id\":\"pack-land-registry\"", "\"id\":\"pdf-render\"");
    reasons.put(p.toString(), "integration slug 'pdf-render' must start with pack-");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.json",
        "\"id\":\"pack-land-registry\"",
        "\"id\":\"mail-send\"");
    reasons.put(p.toString(), "integration slug 'mail-send' must start with pack-");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.versions/1.0.0.json",
        "\"input\":\"pack-land-lookup\"",
        "\"input\":\"mail-send\"");
    reasons.put(p.toString(), "input definition 'mail-send' must start with pack-");

    p = goodPackage(root.resolve("c" + ++n));
    write(p.resolve("contracts/mailpit.json"), "{\"id\":\"mailpit\"}");
    reasons.put(p.toString(), "contract id 'mailpit' must start with pack-");

    // A connection id of the core: the pack would send its calls to Mailpit.
    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.json",
        "\"connection\":\"pack-land-system\"",
        "\"connection\":\"mailpit\"");
    reasons.put(p.toString(), "connection id 'mailpit' must start with pack-");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.versions/1.0.0.json",
        "\"connection\":\"pack-land-system\"",
        "\"connection\":\"pdf-renderer\"");
    reasons.put(p.toString(), "connection id 'pdf-renderer' must start with pack-");

    // Scenario: A version file with a literal base URL (in a version, a draft, nested).
    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.versions/1.0.0.json",
        "\"connection\":\"pack-land-system\"",
        "\"connection\":\"pack-land-system\",\"baseUrl\":\"http://backend:8085\"");
    reasons.put(p.toString(), "has baseUrl");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(
        p,
        "services/pack-land-registry.json",
        "\"connection\":\"pack-land-system\"",
        "\"connection\":\"pack-land-system\",\"baseUrl\":\"https://land.example.org\"");
    reasons.put(p.toString(), "has baseUrl");

    // Scenario: A package with a secret (and a connection with an address).
    p = goodPackage(root.resolve("c" + ++n));
    write(p.resolve("secrets/pack-land-system.json"), "{\"value\":\"x\"}");
    reasons.put(p.toString(), "has secrets/");

    p = goodPackage(root.resolve("c" + ++n));
    write(p.resolve("connections/pack-land-system.json"), "{\"baseUrl\":\"http://backend:8085\"}");
    reasons.put(p.toString(), "has connections/");

    // Manifest shape.
    p = goodPackage(root.resolve("c" + ++n));
    Files.delete(p.resolve("manifest.json"));
    reasons.put(p.toString(), "has no manifest.json");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(p, "manifest.json", "\"formatVersion\":1", "\"formatVersion\":2");
    reasons.put(p.toString(), "formatVersion must be 1");

    p = goodPackage(root.resolve("c" + ++n));
    replaceIn(p, "manifest.json", "patchbay-package", "something-else");
    reasons.put(p.toString(), "format must be patchbay-package");

    for (Map.Entry<String, String> e : reasons.entrySet()) {
      List<String> problems = packageProblems(Path.of(e.getKey()));
      if (problems.stream().noneMatch(line -> line.contains(e.getValue()))) {
        fail("expected '" + e.getValue() + "' in " + problems + " for " + e.getKey());
      }
    }
  }

  /**
   * The checker reads the layout Patchbay really exports: on the core's own packages the only thing
   * it objects to is the missing {@code pack-} prefix, never a missing file or a wrong key.
   */
  @Test
  void theCheckerReadsTheRealExportLayout() throws IOException {
    List<Path> corePackages = packageDirs(Path.of("../patchbay/packages"));
    assertEquals(2, corePackages.size(), corePackages.toString());
    for (Path dir : corePackages) {
      List<String> problems = packageProblems(dir);
      assertTrue(!problems.isEmpty(), "the core's ids are not pack- ids: " + dir);
      for (String line : problems) {
        assertTrue(line.contains("must start with pack-"), line);
      }
    }
  }

  @Test
  void theCoreSlugsAreTheCorePackagesIntegrations() throws IOException {
    Set<String> actual = declaredSlugs(Path.of("../patchbay/packages"));
    assertEquals(CORE_SLUGS, actual);
  }

  // ---------------------------------------------------------------------------------------------
  // The pack's docker/patchbay.Dockerfile
  // ---------------------------------------------------------------------------------------------

  private static List<List<String>> instructions(String text) {
    List<List<String>> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.startsWith("#") || (line.isEmpty() && current.length() == 0)) {
        continue;
      }
      boolean continued = line.endsWith("\\");
      current.append(continued ? line.substring(0, line.length() - 1) : line).append(' ');
      if (!continued) {
        String[] parts = current.toString().strip().split("\\s+", 2);
        out.add(List.of(parts[0].toUpperCase(Locale.ROOT), parts.length > 1 ? parts[1] : ""));
        current.setLength(0);
      }
    }
    return out;
  }

  /** Every rule a pack's patchbay.Dockerfile breaks (design D8): the zip stage, only patchbay/. */
  static List<String> layerDockerfileProblems(String text) {
    List<String> problems = new ArrayList<>();
    List<List<String>> all = instructions(text);
    Set<String> stages = new TreeSet<>();
    String zipStage = null;
    String finalImage = null;
    boolean argBeforeFrom = false;
    boolean seenFrom = false;
    boolean finalCopiesZips = false;
    String currentStage = null;
    for (List<String> instruction : all) {
      String kind = instruction.get(0);
      String rest = instruction.get(1);
      switch (kind) {
        case "ARG" -> {
          if (!seenFrom && rest.split("[=\\s]")[0].equals("PATCHBAY_CORE")) {
            argBeforeFrom = true;
          }
        }
        case "FROM" -> {
          seenFrom = true;
          String[] words = rest.split("\\s+");
          int image = 0;
          while (image < words.length && words[image].startsWith("--")) {
            image++;
          }
          finalImage = image < words.length ? words[image] : "";
          currentStage =
              image + 2 < words.length && words[image + 1].equalsIgnoreCase("AS")
                  ? words[image + 2]
                  : null;
          finalCopiesZips = false;
          if (currentStage != null) {
            stages.add(currentStage);
          }
        }
        case "ADD" -> problems.add("ADD is not allowed; COPY from patchbay/ only: " + rest);
        case "RUN" -> {
          if (rest.contains("--mount")) {
            problems.add("RUN --mount can read outside patchbay/: " + rest);
          }
          if (ZIP_COMMAND.matcher(rest).find()
              && rest.contains("/import/50-")
              && currentStage != null) {
            zipStage = currentStage;
          }
        }
        case "COPY" -> {
          List<String> words = new ArrayList<>(List.of(rest.split("\\s+")));
          String from = null;
          while (!words.isEmpty() && words.get(0).startsWith("--")) {
            String flag = words.remove(0);
            if (flag.startsWith("--from=")) {
              from = flag.substring("--from=".length());
            }
          }
          words.replaceAll(w -> w.replaceAll("^\\[?\"?|\"?,?\\]?$", ""));
          List<String> sources = words.size() > 1 ? words.subList(0, words.size() - 1) : words;
          if (from == null) {
            for (String source : sources) {
              if (!(source.equals("patchbay") || source.startsWith("patchbay/"))
                  || source.contains("..")) {
                problems.add("COPY takes only patchbay/ from the build context, not " + source);
              }
            }
          } else if (!stages.contains(from)) {
            problems.add("COPY --from=" + from + " is not an earlier stage of this file");
          } else if (!sources.stream().allMatch(s -> s.startsWith("/import"))) {
            problems.add("COPY --from=" + from + " takes only /import/, not " + sources);
          } else if (from.equals(zipStage)) {
            finalCopiesZips = true;
          }
        }
        default -> {}
      }
    }
    if (!argBeforeFrom) {
      problems.add("must declare ARG PATCHBAY_CORE before the first FROM");
    }
    if (finalImage == null
        || !(finalImage.equals("${PATCHBAY_CORE}") || finalImage.equals("$PATCHBAY_CORE"))) {
      problems.add("the last stage must be FROM ${PATCHBAY_CORE}, not " + finalImage);
    }
    if (zipStage == null) {
      problems.add("needs a zip stage that writes /import/50-<name>.zip (design D8)");
    } else if (!finalCopiesZips) {
      problems.add("the last stage must COPY --from=" + zipStage + " /import/ /import/");
    }
    return problems;
  }

  @Test
  void aPacksPatchbayLayerDockerfileZipsOnlyItsPackages() throws IOException {
    Path file = PACK.resolve("docker/patchbay.Dockerfile");
    assertTrue(
        Files.isRegularFile(file),
        PACK
            + " has no docker/patchbay.Dockerfile: a pack builds its Patchbay layer even with no"
            + " integration of its own (docs/platform-api.md, pack layout)");
    assertEquals(List.of(), layerDockerfileProblems(Files.readString(file)));
  }

  private static final String GOOD_LAYER =
      """
      # The pack's Patchbay layer.
      ARG PATCHBAY_CORE=cib7-poc-patchbay:latest
      FROM alpine:3.24.4 AS packages
      RUN apk add --no-cache zip
      COPY patchbay/ /src/
      RUN mkdir -p /import \\
       && cd /src \\
       && for d in */; do \\
            [ -d "$d" ] || continue; \\
            name="${d%/}"; \\
            (cd "$name" && zip -qrXD "/import/50-${name}.zip" .); \\
          done
      FROM ${PATCHBAY_CORE}
      COPY --from=packages /import/ /import/
      """;

  @Test
  void aLayerDockerfileThatKeepsTheRulesPasses() {
    assertEquals(List.of(), layerDockerfileProblems(GOOD_LAYER));
  }

  @Test
  void layerDockerfileRulesRefuseWhatAPackLayerMayNotDo() {
    Map<String, String> cases = new LinkedHashMap<>();
    // Copies from outside patchbay/.
    cases.put(GOOD_LAYER.replace("COPY patchbay/ /src/", "COPY . /src/"), "only patchbay/");
    cases.put(GOOD_LAYER.replace("COPY patchbay/ /src/", "COPY engine/ /src/"), "only patchbay/");
    cases.put(
        GOOD_LAYER.replace("COPY patchbay/ /src/", "COPY patchbay/ ../backend /src/"),
        "only patchbay/");
    cases.put(
        GOOD_LAYER.replace("COPY patchbay/ /src/", "COPY patchbay/ backend/ /src/"),
        "only patchbay/");
    cases.put(GOOD_LAYER.replace("COPY patchbay/ /src/", "ADD patchbay/ /src/"), "ADD is not");
    cases.put(
        GOOD_LAYER.replace("RUN apk add", "RUN --mount=type=bind,source=.,target=/ctx apk add"),
        "RUN --mount");
    cases.put(
        GOOD_LAYER.replace(
            "COPY --from=packages /import/ /import/",
            "COPY --from=docker.io/library/alpine:3.24 /etc/passwd /import/"),
        "not an earlier stage");
    cases.put(
        GOOD_LAYER.replace(
            "COPY --from=packages /import/ /import/", "COPY --from=packages /src/ /import/"),
        "only /import/");
    // Wrong base or no zip stage.
    cases.put(
        GOOD_LAYER.replace("FROM ${PATCHBAY_CORE}", "FROM krixerx/patchbay:0.1.0"),
        "must be FROM ${PATCHBAY_CORE}");
    cases.put(
        GOOD_LAYER.replace("ARG PATCHBAY_CORE=cib7-poc-patchbay:latest\n", ""),
        "ARG PATCHBAY_CORE");
    cases.put(GOOD_LAYER.replace("/import/50-${name}.zip", "/import/${name}.zip"), "zip stage");
    cases.put(GOOD_LAYER.replace("zip -qrXD", "tar -cf"), "zip stage");
    cases.put(
        GOOD_LAYER.replace("COPY --from=packages /import/ /import/\n", "RUN true\n"),
        "COPY --from=packages");
    cases.put("FROM alpine\n", "ARG PATCHBAY_CORE");
    cases.forEach(
        (dockerfile, reason) -> {
          List<String> problems = layerDockerfileProblems(dockerfile);
          if (problems.stream().noneMatch(p -> p.contains(reason))) {
            fail("expected '" + reason + "' in " + problems + " for:\n" + dockerfile);
          }
        });
  }

  @Test
  void aPackNoLongerShipsBusRoutes() {
    assertTrue(!Files.exists(PACK.resolve("esb")), "esb/ was removed in platform API 3.0");
    assertTrue(
        !Files.exists(PACK.resolve("docker/esb.Dockerfile")),
        "docker/esb.Dockerfile was removed in platform API 3.0");
  }

  // ---------------------------------------------------------------------------------------------
  // Expressions that reach reflection or the engine (defence in depth, NOT the fix)
  // ---------------------------------------------------------------------------------------------

  /**
   * Spellings that reach reflection, the engine or arbitrary classes from a JUEL or FreeMarker
   * expression. A text scan: it is bypassable (string concatenation, {@code "get"+"Class"},
   * FreeMarker's {@code ?lower_case}/{@code ?interpret} tricks, unusual whitespace or unicode
   * escapes), so it catches only the plain forms and the honest mistake. The guard that actually
   * closes the hole is in the engine (handoffs.md, "Restricting beans does not stop a pack BPMN
   * from reaching secrets"); this check says "no" early, at pack check time.
   */
  private static final Map<String, Pattern> REFLECTION = new LinkedHashMap<>();

  static {
    REFLECTION.put("getClass", Pattern.compile("getClass"));
    REFLECTION.put("forName", Pattern.compile("forName"));
    REFLECTION.put("processEngine", Pattern.compile("processEngine"));
    REFLECTION.put("?new", Pattern.compile("\\?\\s*new\\b"));
    REFLECTION.put("?api", Pattern.compile("\\?\\s*api\\b"));
    REFLECTION.put("Execute", Pattern.compile("\\bExecute\\b"));
    REFLECTION.put("ObjectConstructor", Pattern.compile("ObjectConstructor"));
  }

  /** The reflection spellings in a pack file's text; empty when it holds none. */
  static List<String> reflectionProblems(String file, String text) {
    List<String> problems = new ArrayList<>();
    REFLECTION.forEach(
        (name, pattern) -> {
          if (pattern.matcher(text).find()) {
            problems.add(file + ": uses " + name + ", which reaches reflection or the engine");
          }
        });
    return problems;
  }

  @Test
  void packExpressionsAndTemplatesUseNoReflection() throws IOException {
    List<String> problems = new ArrayList<>();
    for (String dir : List.of("processes", "templates", "documents")) {
      for (Path file : files(ENGINE.resolve(dir), "")) {
        String name = file.toString();
        if (name.endsWith(".bpmn")
            || name.endsWith(".dmn")
            || name.endsWith(".ftl")
            || name.endsWith(".ftlh")) {
          problems.addAll(
              reflectionProblems(ENGINE.relativize(file).toString(), Files.readString(file)));
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  @Test
  void reflectionSpellingsAreRefused() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("${''.getClass().forName('java.lang.System')}", "getClass");
    cases.put("${Class.forName('java.lang.System')}", "forName");
    cases.put("${execution.processEngine.processEngineConfiguration.dataSource}", "processEngine");
    cases.put("${\"freemarker.template.utility.ObjectConstructor\"?new()}", "?new");
    cases.put("${x ? new()}", "?new");
    cases.put("${\"freemarker.template.utility.Execute\"?new()(\"id\")}", "Execute");
    cases.put("<#assign c = 'x'>ObjectConstructor", "ObjectConstructor");
    cases.put("${bean?api.class}", "?api");
    cases.forEach(
        (text, name) -> {
          List<String> problems = reflectionProblems("f.json.ftl", text);
          if (problems.stream().noneMatch(p -> p.contains("uses " + name + ","))) {
            fail("expected " + name + " in " + problems + " for " + text);
          }
        });
    // Ordinary expressions and prose pass; the scan names tokens, not words like "execute".
    for (String ok :
        List.of(
            "${execution.processInstanceId}",
            "${applicant.name?json_string}",
            "${(items?size)!0}",
            "Please execute the form and renew the news",
            "${x?new_line}")) {
      assertEquals(List.of(), reflectionProblems("f", ok), ok);
    }
  }

  // ---------------------------------------------------------------------------------------------

  private static List<Path> files(Path dir, String suffix) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(suffix))
          .sorted()
          .toList();
    }
  }
}
