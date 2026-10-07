package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What a pack may reach over the bus, and what its own bus routes may do (docs/platform-api.md,
 * "Bus paths" and "ESB routes"). The engine knows one outbound address, the ESB, and the ESB holds
 * the secrets that open the backend's internal API; these rules keep a pack's BPMN on the paths the
 * core offers it and keep a pack's routes from reaching those secrets, the core's own services or
 * code. Tagged {@code pack}, so {@code scripts/pack-check.sh} runs it against any pack directory.
 */
@Tag("pack")
class PackBusTest {

  private static final String CAMUNDA = "http://camunda.org/schema/1.0/bpmn";

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"));

  /** The pack's other parts sit beside its {@code engine/} folder. */
  private static final Path PACK = ENGINE.toAbsolutePath().normalize().getParent();

  private static final String BUS = "${busBaseUrl}";

  private static final Set<String> FIXED_PATHS =
      Set.of(
          "/api/v1/send",
          "/render",
          "/api/internal/documents/move-pending",
          "/api/internal/documents/server-upload",
          "/api/internal/payments/quote/${execution.processInstanceId}");

  private static final Pattern REGISTRY =
      Pattern.compile("^/api/(public|internal)/registry/([a-z][a-z0-9-]*)(/.*)?$");

  private static final Pattern PACK_PATH = Pattern.compile("^/pack/[a-z][a-z0-9-]*(/.*)?$");

  // ---------------------------------------------------------------------------------------------
  // Connector URLs in the pack's BPMN
  // ---------------------------------------------------------------------------------------------

  /**
   * Why a connector URL is refused, or empty when the core offers that path to a pack. {@code
   * registries} maps each declared registry entity to the access classes its operations use.
   */
  static Optional<String> connectorUrlProblem(String url, Map<String, Set<String>> registries) {
    if (!url.startsWith(BUS + "/")) {
      return Optional.of("must start with " + BUS + "/: the bus is the engine's only address");
    }
    String path = url.substring(BUS.length());
    if (path.contains("..") || path.contains("?") || path.contains("#")) {
      return Optional.of("must be a plain path, without '..', a query or a fragment");
    }
    if (FIXED_PATHS.contains(path) || PACK_PATH.matcher(path).matches()) {
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
    return Optional.of("is not a bus path the platform API offers a pack");
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

  @Test
  void connectorsCallOnlyTheBusPathsOfferedToAPack() throws Exception {
    Map<String, Set<String>> registries = declaredRegistries();
    List<String> problems = new ArrayList<>();
    for (Path bpmn : files(ENGINE.resolve("processes"), ".bpmn")) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      NodeList connectors =
          factory
              .newDocumentBuilder()
              .parse(bpmn.toFile())
              .getElementsByTagNameNS(CAMUNDA, "connector");
      for (int i = 0; i < connectors.getLength(); i++) {
        Element connector = (Element) connectors.item(i);
        String at =
            bpmn.getFileName()
                + " "
                + ((Element) connector.getParentNode().getParentNode()).getAttribute("id");
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
        connectorUrlProblem(value, registries)
            .ifPresent(p -> problems.add(at + ": " + value + " " + p));
      }
    }
    assertEquals(List.of(), problems);
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

  @Test
  void connectorUrlRulesRefuseWhatThePackMayNotCall() {
    Map<String, Set<String>> registries = Map.of("vehicles", Set.of("public"));
    for (String allowed :
        List.of(
            BUS + "/api/v1/send",
            BUS + "/api/public/registry/vehicles/${objectId}",
            BUS + "/api/internal/payments/quote/${execution.processInstanceId}",
            BUS + "/pack/land-registry/parcels/${parcelId}")) {
      assertEquals(Optional.empty(), connectorUrlProblem(allowed, registries), allowed);
    }
    Map<String, String> refused =
        Map.of(
            "http://backend:8085/api/internal/case-index",
            "must start with",
            BUS + "/api/internal/case-index",
            "not a bus path",
            BUS + "/api/internal/registry/vehicles",
            "has no internal operation",
            BUS + "/api/public/registry/people/${id}",
            "not declared",
            BUS + "/pack/x/../../api/internal/case-index",
            "plain path",
            BUS + "/render?target=evil",
            "plain path",
            "${frontendBaseUrl}/api/v1/send",
            "must start with");
    refused.forEach(
        (url, reason) -> {
          String problem = connectorUrlProblem(url, registries).orElse("(accepted)");
          assertTrue(problem.contains(reason), url + " -> " + problem);
        });
  }

  // ---------------------------------------------------------------------------------------------
  // The pack's own ESB routes
  // ---------------------------------------------------------------------------------------------

  /** Hosts of the core's own services: a pack route reaches only systems outside the stack. */
  private static final Set<String> CORE_HOSTS =
      Set.of(
          "backend",
          "cib7",
          "esb",
          "frontend",
          "gotenberg",
          "graylog",
          "keycloak",
          "localhost",
          "mailpit",
          "mcp",
          "mongodb",
          "opensearch",
          "pdf-renderer",
          "postgres",
          "rustfs",
          "traefik",
          "127.0.0.1",
          "0.0.0.0",
          "[::1]");

  /** Headers that carry the core's secrets; only the core's routes set them. */
  private static final Set<String> CORE_HEADERS = Set.of("x-internal-token", "x-bus-token");

  /** Steps and languages that run code or reach beans: a pack is data. */
  private static final Set<String> CODE_KEYS =
      Set.of(
          "bean",
          "beanRef",
          "groovy",
          "java",
          "javaScript",
          "joor",
          "js",
          "jsh",
          "method",
          "mvel",
          "ognl",
          "process",
          "python",
          "script",
          "spel");

  /** Keys whose value is an endpoint the route sends to. */
  private static final Set<String> PRODUCER_KEYS =
      Set.of("to", "toD", "wireTap", "enrich", "pollEnrich", "uri");

  private static final Pattern ENV =
      Pattern.compile("\\$\\{\\s*(?:sys)?env[.:]\\s*([A-Za-z0-9_]+)|\\{\\{\\s*env:([A-Za-z0-9_]+)");

  private static final Pattern HTTP =
      Pattern.compile("^https?://(\\[[^\\]]+\\]|[^/:?#]+)", Pattern.CASE_INSENSITIVE);

  /** Every rule a pack route file breaks; empty when it keeps them all. */
  static List<String> routeProblems(String fileName, String yamlText) {
    List<String> problems = new ArrayList<>();
    if (!fileName.matches("pack-[a-z0-9-]+\\.yaml")) {
      problems.add("file name must be pack-<name>.yaml");
    }
    Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
    if (!(root instanceof List<?> items)) {
      problems.add("must be a YAML list of routes");
      return problems;
    }
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> entry)
          || entry.size() != 1
          || !(entry.get("route") instanceof Map<?, ?> route)) {
        problems.add("only route entries are allowed (no beans, templates or rest blocks)");
        continue;
      }
      String id = String.valueOf(route.get("id"));
      if (!id.startsWith("pack-")) {
        problems.add("route id '" + id + "' must start with pack-");
      }
      if (!(route.get("from") instanceof Map<?, ?> from)
          || !(from.get("uri") instanceof String uri)) {
        problems.add(id + ": needs from.uri");
        continue;
      }
      Object steps = from.get("steps");
      if (uri.startsWith("platform-http:")) {
        String path = uri.substring("platform-http:".length()).split("\\?", 2)[0];
        if (!PACK_PATH.matcher(path).matches()) {
          problems.add(id + ": listens on " + path + "; a pack route listens under /pack/<name>");
        }
        if (!(steps instanceof List<?> list)
            || list.isEmpty()
            || !(list.get(0) instanceof Map<?, ?> first)
            || !"direct:bus-auth".equals(first.get("to"))) {
          problems.add(id + ": first step must be to: direct:bus-auth");
        }
      } else if (!uri.startsWith("direct:pack-")) {
        problems.add(id + ": from " + uri + "; only platform-http:/pack/... or direct:pack-...");
      }
      walk(id, "steps", steps, problems);
    }
    return problems;
  }

  private static void walk(String id, String key, Object node, List<String> problems) {
    if (node instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        String k = String.valueOf(e.getKey());
        if (CODE_KEYS.contains(k)) {
          problems.add(id + ": '" + k + "' runs code; a pack route may not");
        }
        if ((k.equals("setHeader") || k.equals("removeHeader"))
            && e.getValue() instanceof Map<?, ?> header
            && header.get("name") != null
            && CORE_HEADERS.contains(header.get("name").toString().toLowerCase(Locale.ROOT))) {
          problems.add(id + ": sets or removes " + header.get("name") + ", a core header");
        }
        if (PRODUCER_KEYS.contains(k) && e.getValue() instanceof String target) {
          producerProblem(target).ifPresent(p -> problems.add(id + ": " + k + " " + target + p));
        }
        walk(id, k, e.getValue(), problems);
      }
    } else if (node instanceof List<?> list) {
      list.forEach(child -> walk(id, key, child, problems));
    } else if (node instanceof String text) {
      Matcher env = ENV.matcher(text);
      while (env.find()) {
        String name = env.group(1) != null ? env.group(1) : env.group(2);
        if (!name.startsWith("PACK_")) {
          problems.add(id + ": reads environment variable " + name + "; a pack reads only PACK_*");
        }
      }
      for (String header : CORE_HEADERS) {
        if (text.toLowerCase(Locale.ROOT).contains("header." + header)) {
          problems.add(id + ": reads " + header + ", a core header");
        }
      }
    }
  }

  private static Optional<String> producerProblem(String target) {
    if (target.equals("direct:bus-auth") || target.startsWith("direct:pack-")) {
      return Optional.empty();
    }
    if (target.startsWith("log:")) {
      return Optional.empty();
    }
    if (target.startsWith("${env.PACK_")) {
      return Optional.empty();
    }
    Matcher http = HTTP.matcher(target);
    if (!http.find()) {
      return Optional.of(
          ": a pack route sends only to http(s) systems outside the stack, its own direct:pack-"
              + " routes or log:");
    }
    String host = http.group(1).toLowerCase(Locale.ROOT);
    if (CORE_HOSTS.contains(host) || host.startsWith("${") || host.contains("$")) {
      return Optional.of(": " + host + " is not an outside system named in the route");
    }
    return Optional.empty();
  }

  @Test
  void packRoutesKeepTheBusRules() throws IOException {
    Path routes = PACK.resolve("esb/routes");
    List<String> problems = new ArrayList<>();
    for (Path file : files(routes, ".yaml")) {
      routeProblems(file.getFileName().toString(), Files.readString(file))
          .forEach(p -> problems.add(file.getFileName() + ": " + p));
    }
    if (Files.isDirectory(routes)) {
      try (Stream<Path> other = Files.list(routes)) {
        other
            .filter(p -> !p.toString().endsWith(".yaml"))
            .forEach(p -> problems.add(p.getFileName() + ": only pack-<name>.yaml files"));
      }
    }
    assertEquals(List.of(), problems);
  }

  private static final String GOOD_ROUTE =
      """
      - route:
          id: pack-land-registry
          from:
            uri: "platform-http:/pack/land-registry?matchOnUriPrefix=true"
            steps:
              - to: "direct:bus-auth"
              - setHeader:
                  name: "Authorization"
                  simple: "Bearer ${env.PACK_LAND_REGISTRY_TOKEN}"
              - removeHeader:
                  name: "CamelHttpPath"
              - toD: "${env.PACK_LAND_REGISTRY_URL}?bridgeEndpoint=true"
      - route:
          id: pack-land-registry-audit
          from:
            uri: "direct:pack-land-registry-audit"
            steps:
              - to: "https://audit.example.org/events?bridgeEndpoint=true"
      """;

  @Test
  void aRouteThatKeepsTheRulesPasses() {
    assertEquals(List.of(), routeProblems("pack-land-registry.yaml", GOOD_ROUTE));
  }

  @Test
  void routeRulesRefuseWhatAPackRouteMayNotDo() {
    Map<String, String> cases = new TreeMap<>();
    cases.put("core-ish.yaml", "file name must be pack-");
    cases.put(
        GOOD_ROUTE.replace("id: pack-land-registry\n", "id: land-registry\n"),
        "must start with pack-");
    cases.put(
        GOOD_ROUTE.replace("platform-http:/pack/land-registry", "platform-http:/api/internal"),
        "listens under /pack/");
    cases.put(
        GOOD_ROUTE.replace("- to: \"direct:bus-auth\"", "- log: \"no auth\""),
        "first step must be to: direct:bus-auth");
    cases.put(GOOD_ROUTE.replace("Authorization", "X-Internal-Token"), "core header");
    cases.put(
        GOOD_ROUTE.replace("PACK_LAND_REGISTRY_TOKEN", "INTERNAL_TASK_TOKEN"), "reads only PACK_*");
    cases.put(
        GOOD_ROUTE.replace("PACK_LAND_REGISTRY_TOKEN}", "PACK_X} ${header.X-Bus-Token}"),
        "core header");
    cases.put(
        GOOD_ROUTE.replace("https://audit.example.org", "http://backend:8085"),
        "not an outside system");
    cases.put(
        GOOD_ROUTE.replace("https://audit.example.org/events", "http://${header.target}"),
        "not an outside system");
    cases.put(
        GOOD_ROUTE.replace("https://audit.example.org/events", "exec:/bin/sh"),
        "sends only to http(s) systems");
    cases.put(
        GOOD_ROUTE.replace("direct:pack-land-registry-audit", "timer:tick"),
        "only platform-http:/pack/");
    cases.put(
        GOOD_ROUTE.replace(
            "        - removeHeader:\n",
            "        - script:\n            groovy: \"x\"\n        - removeHeader:\n"),
        "runs code");
    cases.put(
        "- beans:\n    - name: x\n      type: java.lang.Runtime\n" + GOOD_ROUTE,
        "only route entries");
    cases.forEach(
        (yaml, reason) -> {
          String file = yaml.equals("core-ish.yaml") ? yaml : "pack-land-registry.yaml";
          String text = yaml.equals("core-ish.yaml") ? GOOD_ROUTE : yaml;
          List<String> problems = routeProblems(file, text);
          if (problems.stream().noneMatch(p -> p.contains(reason))) {
            fail("expected '" + reason + "' in " + problems + " for:\n" + text);
          }
        });
  }

  private static List<Path> files(Path dir, String suffix) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
    }
  }
}
