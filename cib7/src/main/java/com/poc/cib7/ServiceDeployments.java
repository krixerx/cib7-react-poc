package com.poc.cib7;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.repository.Deployment;
import org.cibseven.bpm.engine.repository.DeploymentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Deploys each service's BPMN + DMN as its OWN named engine deployment, one per {@code
 * classpath:/processes/<service>/} folder. Replaces the Spring Boot starter's auto-deploy ({@code
 * camunda.bpm.auto-deployment-enabled: false} in application.yaml), which would bundle every
 * resource into a single "SpringAutoDeployment".
 *
 * <p>Why per-service deployments:
 *
 * <ul>
 *   <li><b>Independent versioning.</b> Duplicate filtering is evaluated per deployment name, with
 *       {@code deployChangedOnly=true} — editing one service's BPMN re-versions only that service;
 *       every other service's deployment is a filtered no-op. With the single-bundle starter
 *       deploy, unrelated definitions get re-versioned (or share one drift-prone deployment row).
 *   <li><b>Independent lifecycle.</b> A deployment is the engine's unit of rollback/deletion
 *       (cascade). One row per service means one service can be removed or rolled back in Cockpit
 *       without touching the others — matching the spec-first premise that the analyst's service
 *       folder is the unit of change.
 *   <li><b>{@code decisionRefBinding="deployment"} works correctly.</b> Business rule tasks bind to
 *       the decision table that shipped in the SAME deployment as the process, so an in-flight case
 *       never silently picks up a newer DMN. That binding requires the service-scoped grouping this
 *       class creates.
 * </ul>
 *
 * <p>The folder name (= the spec folder name under {@code docs/business/services/}) becomes the
 * deployment name. The /service-builder skill emits into these folders; see its SKILL.md
 * conventions.
 *
 * <p>Runs in {@code @PostConstruct} — during context refresh, after the engine bean exists and
 * before the HTTP port opens, so {@code /engine-rest} never serves a window with missing
 * definitions. Idempotent: re-running against an unchanged classpath creates no new versions.
 */
@Component
public class ServiceDeployments {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceDeployments.class);

  private static final String RESOURCE_PATTERN = "classpath*:processes/*/*.*";

  private final ProcessEngine processEngine;

  public ServiceDeployments(ProcessEngine processEngine) {
    this.processEngine = processEngine;
  }

  @PostConstruct
  public void deployServices() throws IOException {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    // service folder name -> (resource name inside the deployment -> resource)
    Map<String, Map<String, Resource>> services = new TreeMap<>();
    for (Resource resource : resolver.getResources(RESOURCE_PATTERN)) {
      String filename = resource.getFilename();
      if (filename == null || !(filename.endsWith(".bpmn") || filename.endsWith(".dmn"))) {
        continue;
      }
      String service = parentFolder(resource);
      services
          .computeIfAbsent(service, k -> new TreeMap<>())
          .put("processes/" + service + "/" + filename, resource);
    }

    for (Map.Entry<String, Map<String, Resource>> service : services.entrySet()) {
      DeploymentBuilder builder =
          processEngine
              .getRepositoryService()
              .createDeployment()
              .name(service.getKey())
              .source("service-deployments")
              // Compare against the latest deployment with the same name and
              // create a new version only for resources whose bytes changed.
              .enableDuplicateFiltering(true);
      for (Map.Entry<String, Resource> entry : service.getValue().entrySet()) {
        builder.addInputStream(entry.getKey(), entry.getValue().getInputStream());
      }
      Deployment deployment = builder.deploy();
      LOG.info(
          "Service '{}': deployment {} with {} resource(s)",
          service.getKey(),
          deployment.getId(),
          service.getValue().size());
    }
  }

  /** The immediate parent folder of a {@code processes/<service>/<file>} classpath resource. */
  private static String parentFolder(Resource resource) throws IOException {
    String url = resource.getURL().toString();
    int fileSlash = url.lastIndexOf('/');
    int folderSlash = url.lastIndexOf('/', fileSlash - 1);
    return url.substring(folderSlash + 1, fileSlash);
  }
}
