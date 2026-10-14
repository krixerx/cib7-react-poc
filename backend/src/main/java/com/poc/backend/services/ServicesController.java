package com.poc.backend.services;

import com.poc.backend.engine.EngineClient;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public service list: what an applicant can start, shown on the landing page and in the mobile
 * app before anyone signs in.
 *
 * <p>The engine's {@code /engine-rest} takes no anonymous calls, so the list comes from here, read
 * as the {@code cib7-business} service account. It is harmless reference data (docs/security.md
 * rule 5): the latest version of each active definition, with only the fields a client needs to
 * name a service and start it.
 */
@RestController
public class ServicesController {

  private final EngineClient engine;

  public ServicesController(EngineClient engine) {
    this.engine = engine;
  }

  @GetMapping("/api/public/services")
  public List<Service> services() {
    return engine.latestProcessDefinitions().stream()
        .filter(d -> !d.path("suspended").asBoolean(false))
        .map(
            d ->
                new Service(
                    d.path("id").asText(),
                    d.path("key").asText(),
                    d.path("name").isTextual() ? d.path("name").asText() : null,
                    d.path("version").asInt()))
        .sorted(
            Comparator.comparing((Service s) -> s.name() == null ? s.key() : s.name())
                .thenComparing(Service::key))
        .toList();
  }

  /** One startable service: a deployed process definition, latest version. */
  public record Service(String id, String key, String name, int version) {}
}
