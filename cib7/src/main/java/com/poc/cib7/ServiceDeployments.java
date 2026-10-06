package com.poc.cib7;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.RepositoryService;
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
 *   <li><b>Independent versioning.</b> Duplicate filtering is evaluated per deployment name —
 *       editing one service's BPMN re-versions only that service; every other service's deployment
 *       is a filtered no-op. With the single-bundle starter deploy, unrelated definitions get
 *       re-versioned (or share one drift-prone deployment row).
 *   <li><b>A service always deploys whole.</b> Filtering runs with {@code deployChangedOnly=false}:
 *       when one file of a service changed, all of its files go into the new deployment. With
 *       {@code true} a changed BPMN would be deployed alone, and its business rule tasks, bound to
 *       their DMN by deployment, would fail with "no decision definition deployed". A latest
 *       deployment that lacks one of the service's files (left behind by the earlier {@code true})
 *       is repaired by deploying the service unfiltered.
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
 * conventions. They come from the service pack, not from this jar: the image puts {@code
 * /opt/services} on the classpath ({@code loader.path}, see cib7/Dockerfile), and tests and {@code
 * mvn spring-boot:run} add {@code packs/reference/engine} through the pom.
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
      deploy(service.getKey(), service.getValue());
    }
  }

  /**
   * Deploys one service's resources as the deployment named after it: nothing when no resource
   * changed, all of them when one did, and all of them unfiltered when the latest deployment is
   * missing one.
   */
  Deployment deploy(String service, Map<String, Resource> resources) throws IOException {
    RepositoryService repository = processEngine.getRepositoryService();
    DeploymentBuilder builder =
        repository.createDeployment().name(service).source("service-deployments");
    if (latestDeploymentHolds(service, resources.keySet())) {
      // Compare against the latest deployment with the same name; if any
      // resource changed, deploy every resource of the service together.
      builder.enableDuplicateFiltering(false);
    } else {
      LOG.warn(
          "Service '{}': the latest deployment lacks some of {}; deploying the whole service again",
          service,
          resources.keySet());
    }
    for (Map.Entry<String, Resource> entry : resources.entrySet()) {
      builder.addInputStream(entry.getKey(), entry.getValue().getInputStream());
    }
    Deployment deployment = builder.deploy();
    LOG.info(
        "Service '{}': deployment {} with {} resource(s)",
        service,
        deployment.getId(),
        resources.size());
    return deployment;
  }

  /** True when there is no deployment yet, or the latest one contains every given resource. */
  private boolean latestDeploymentHolds(String service, Set<String> resourceNames) {
    RepositoryService repository = processEngine.getRepositoryService();
    List<Deployment> latest =
        repository
            .createDeploymentQuery()
            .deploymentName(service)
            .orderByDeploymentTime()
            .desc()
            .listPage(0, 1);
    if (latest.isEmpty()) {
      return true;
    }
    return new HashSet<>(repository.getDeploymentResourceNames(latest.get(0).getId()))
        .containsAll(resourceNames);
  }

  /** The immediate parent folder of a {@code processes/<service>/<file>} classpath resource. */
  private static String parentFolder(Resource resource) throws IOException {
    String url = resource.getURL().toString();
    int fileSlash = url.lastIndexOf('/');
    int folderSlash = url.lastIndexOf('/', fileSlash - 1);
    return url.substring(folderSlash + 1, fileSlash);
  }
}
