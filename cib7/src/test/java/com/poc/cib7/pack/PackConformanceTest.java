package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import com.poc.cib7.policy.VariablePolicy;
import com.poc.cib7.policy.VariablePolicyRegistry;
import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.repository.Deployment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Engine-side checks that hold for every service pack, whatever its services: the platform API's
 * rules for {@code processes/}, {@code templates/} and {@code documents/} (docs/platform-api.md).
 * Tagged {@code pack}, so {@code scripts/pack-check.sh} runs it against any pack directory. Checks
 * of the reference services' own behaviour stay in the other tests.
 */
@Tag("pack")
@SpringBootTest
@ActiveProfiles("test")
class PackConformanceTest {

  private static final String CAMUNDA = "http://camunda.org/schema/1.0/bpmn";

  private static final Path PACK =
      Path.of(System.getProperty("services.pack.dir", "../packs/reference/engine"));

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  private static List<Path> files(Path dir, String suffix) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
    }
  }

  /**
   * Each {@code processes/<service>/} folder is one engine deployment named after it, holding all
   * its BPMN and DMN files: {@code decisionRefBinding="deployment"} needs the decisions beside the
   * process.
   */
  @Test
  void everyServiceFolderIsOneDeploymentWithAllItsFiles() throws IOException {
    RepositoryService repository = processEngine.getRepositoryService();
    List<Path> services;
    try (Stream<Path> dirs = Files.list(PACK.resolve("processes"))) {
      services = dirs.filter(Files::isDirectory).sorted().toList();
    }
    assertFalse(services.isEmpty(), "the pack has no processes/<service>/ folder");
    for (Path service : services) {
      String name = service.getFileName().toString();
      Deployment deployment =
          repository
              .createDeploymentQuery()
              .deploymentName(name)
              .orderByDeploymentTime()
              .desc()
              .list()
              .get(0);
      Set<String> deployed =
          new TreeSet<>(repository.getDeploymentResourceNames(deployment.getId()));
      Set<String> expected = new TreeSet<>();
      for (Path file : files(service, "")) {
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".bpmn") || fileName.endsWith(".dmn")) {
          expected.add("processes/" + name + "/" + fileName);
        }
      }
      assertTrue(
          deployed.containsAll(expected),
          name + ": deployment holds " + deployed + ", the folder " + expected);
    }
    assertEquals(
        0,
        repository.createDeploymentQuery().deploymentName("SpringAutoDeployment").count(),
        "the starter's single-bundle auto-deploy must be off");
  }

  /**
   * A pack is data: its BPMN may name only the core classes released for packs, and its scripts are
   * FreeMarker payload templates loaded by resource. No Java delegate or expression of its own, no
   * script task, no inline script (docs/platform-api.md, what a pack cannot do).
   */
  @Test
  void bpmnUsesOnlyReleasedCoreClassesAndTemplateScripts() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    for (Path bpmn : files(PACK.resolve("processes"), ".bpmn")) {
      NodeList all =
          factory.newDocumentBuilder().parse(bpmn.toFile()).getElementsByTagNameNS("*", "*");
      for (int i = 0; i < all.getLength(); i++) {
        Element element = (Element) all.item(i);
        String where = bpmn.getFileName() + " <" + element.getTagName() + ">";
        assertFalse("scriptTask".equals(element.getLocalName()), where + ": no script tasks");
        for (String attribute : List.of("delegateExpression", "expression")) {
          assertFalse(
              element.hasAttributeNS(CAMUNDA, attribute) || isListenerWith(element, attribute),
              where + " uses camunda:" + attribute + "; a pack may not run its own code");
        }
        String className =
            element.hasAttributeNS(CAMUNDA, "class")
                ? element.getAttributeNS(CAMUNDA, "class")
                : isListenerWith(element, "class") ? element.getAttribute("class") : null;
        if (className != null) {
          assertTrue(
              PackManifest.RELEASED_CLASSES.contains(className),
              where + " names " + className + ", which is not released for packs");
        }
        if ("script".equals(element.getLocalName()) && CAMUNDA.equals(element.getNamespaceURI())) {
          assertEquals("freemarker", element.getAttribute("scriptFormat"), where);
          assertFalse(element.getAttribute("resource").isEmpty(), where + ": inline script");
        }
      }
    }
  }

  private static boolean isListenerWith(Element element, String attribute) {
    return CAMUNDA.equals(element.getNamespaceURI())
        && element.getLocalName().endsWith("Listener")
        && element.hasAttribute(attribute);
  }

  /**
   * A tiered fee is computed from a case variable, so that variable must be one the engine sets (a
   * connector output in the fee's process), never one a client may write: otherwise an applicant
   * could choose their own fee (docs/security.md rule 4). The fee rules live in the pack's backend
   * part; the policies and the BPMN in its engine part.
   */
  @Test
  void feeTiersOnlyReadVariablesTheEngineSets() throws Exception {
    Path payment = PACK.resolve("../backend/payment").normalize();
    Map<String, VariablePolicy> policies =
        VariablePolicyRegistry.load(VariablePolicyRegistry.DEFAULT_LOCATION);
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    for (Path rule : files(payment, ".yaml")) {
      Map<?, ?> root = yaml.load(Files.readString(rule));
      String process = String.valueOf(root.get("process"));
      VariablePolicy policy = policies.get(process);
      assertTrue(policy != null, rule.getFileName() + ": no service has process " + process);
      if (!(root.get("amount") instanceof Map<?, ?> amount
          && amount.get("tiers") instanceof Map<?, ?> tiers)) {
        continue;
      }
      String variable = String.valueOf(tiers.get("variable"));
      Set<String> writable = new TreeSet<>(policy.start());
      policy.forms().values().forEach(writable::addAll);
      writable.addAll(policy.identity().keySet());
      assertFalse(
          writable.contains(variable),
          rule.getFileName() + ": the fee depends on " + variable + ", which a client may write");
      String bpmn = "";
      try (Stream<Path> dirs = Files.list(PACK.resolve("processes"))) {
        for (Path dir : dirs.toList()) {
          for (Path file : files(dir, ".bpmn")) {
            String xml = Files.readString(file);
            if (xml.contains("id=\"" + process + "\"")) {
              bpmn = xml;
            }
          }
        }
      }
      assertTrue(
          bpmn.contains("<camunda:outputParameter name=\"" + variable + "\">"),
          rule.getFileName() + ": " + variable + " is no connector output of " + process);
    }
  }

  /** Every connector payload template compiles (FreeMarker syntax), as the engine loads it. */
  @Test
  void everyPayloadTemplateCompiles() throws IOException {
    Configuration config = new Configuration(Configuration.VERSION_2_3_29);
    config.setTemplateLoader(new ClassTemplateLoader(PackConformanceTest.class, "/templates"));
    for (Path template : files(PACK.resolve("templates"), ".ftl")) {
      config.getTemplate(template.getFileName().toString());
    }
  }

  /**
   * Every document compiles in the {@code documents} bean's settings: {@code .ftlh} with HTML
   * auto-escaping, where a leftover {@code ?html} is an error, and the shared layouts resolve.
   */
  @Test
  void everyDocumentCompiles() throws IOException {
    Configuration config = new Configuration(Configuration.VERSION_2_3_35);
    config.setTemplateLoader(new ClassTemplateLoader(PackConformanceTest.class, "/documents"));
    config.setRecognizeStandardFileExtensions(true);
    Path documents = PACK.resolve("documents");
    for (Path document : files(documents, "")) {
      String name = documents.relativize(document).toString().replace('\\', '/');
      if (name.endsWith(".ftl") || name.endsWith(".ftlh")) {
        config.getTemplate(name);
      }
    }
  }
}
