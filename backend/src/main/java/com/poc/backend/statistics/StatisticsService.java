package com.poc.backend.statistics;

import com.poc.backend.engine.EngineClient;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Reads the engine history the statistics page needs and hands it to {@link StatisticsAggregator}.
 *
 * <p>It reads through {@link EngineClient}, i.e. as the {@code cib7-business} service account, not
 * with the caller's token. Access to statistics is decided by the {@code statistics-viewer} realm
 * role alone (see {@code SecurityConfig}), so the role can later be given to someone who holds no
 * engine grants of their own. The endpoint returns aggregates plus the open-failure list, never
 * variables.
 *
 * <p>The row caps keep one request bounded. They are generous for the in-memory POC; a deployment
 * with real volume should read from reporting views instead (docs/statistics.md).
 */
@Service
public class StatisticsService {

  static final int MAX_INSTANCES = 10_000;
  static final int MAX_ACTIVITIES = 100_000;

  private final EngineClient engine;

  public StatisticsService(EngineClient engine) {
    this.engine = engine;
  }

  public StatisticsReport report(StatisticsQuery query) {
    ZonedDateTime start = query.from().atStartOfDay(query.zone());
    ZonedDateTime end = query.to().plusDays(1).atStartOfDay(query.zone());
    String startedAfter = start.format(StatisticsAggregator.ENGINE_DATE);

    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("startedAfter", startedAfter);
    filter.put("startedBefore", end.format(StatisticsAggregator.ENGINE_DATE));
    if (!query.services().isEmpty()) {
      filter.put("processDefinitionKeyIn", List.copyOf(query.services()));
    }

    List<JsonNode> definitions = engine.latestProcessDefinitions();
    List<JsonNode> instances = engine.historicProcessInstances(filter, MAX_INSTANCES + 1);
    boolean truncated = instances.size() > MAX_INSTANCES;
    if (truncated) {
      instances = instances.subList(0, MAX_INSTANCES);
    }
    // Every activity of a case starts after the case itself, so the range start bounds them.
    List<JsonNode> activities =
        instances.isEmpty()
            ? List.of()
            : engine.historicActivityInstancesStartedAfter(startedAfter, MAX_ACTIVITIES + 1);
    if (activities.size() > MAX_ACTIVITIES) {
      truncated = true;
      activities = activities.subList(0, MAX_ACTIVITIES);
    }
    List<JsonNode> incidents = instances.isEmpty() ? List.of() : engine.openIncidents();

    return StatisticsAggregator.aggregate(
        query, definitions, instances, activities, incidents, truncated);
  }
}
