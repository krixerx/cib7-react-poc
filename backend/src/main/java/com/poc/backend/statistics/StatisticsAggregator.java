package com.poc.backend.statistics;

import com.poc.backend.statistics.StatisticsReport.Counts;
import com.poc.backend.statistics.StatisticsReport.DayCounts;
import com.poc.backend.statistics.StatisticsReport.Failure;
import com.poc.backend.statistics.StatisticsReport.Option;
import com.poc.backend.statistics.StatisticsReport.ServiceCounts;
import com.poc.backend.statistics.StatisticsReport.TaskOption;
import com.poc.backend.statistics.StatisticsReport.TaskStats;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Turns raw engine history rows into a {@link StatisticsReport}. Kept free of I/O so the counting
 * rules can be tested against hand-written rows.
 *
 * <p>Counting rules, applied in this order to every top-level instance started in the range:
 * completed ({@code COMPLETED}), cancelled ({@code EXTERNALLY_TERMINATED} or {@code
 * INTERNALLY_TERMINATED}), failed (still running with an open incident), otherwise in progress. A
 * case that failed, was retried and then completed is therefore completed. Activities and incidents
 * of call-activity sub-processes are attributed to their top-level instance through {@code
 * rootProcessInstanceId}, so a sub-process never counts as a service of its own.
 */
final class StatisticsAggregator {

  /** The engine REST date format, e.g. {@code 2026-10-02T09:15:00.000+0000}. */
  static final DateTimeFormatter ENGINE_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

  static final int MAX_FAILURES = 100;
  private static final int MAX_MESSAGE_LENGTH = 300;

  private enum Status {
    COMPLETED,
    IN_PROGRESS,
    FAILED,
    CANCELLED
  }

  private record Instance(
      String id, String serviceKey, String businessKey, LocalDate startDate, String state) {}

  private record Activity(String taskId, String name, boolean ended, boolean canceled, Long ms) {}

  private StatisticsAggregator() {}

  static StatisticsReport aggregate(
      StatisticsQuery query,
      List<JsonNode> definitions,
      List<JsonNode> instanceRows,
      List<JsonNode> activityRows,
      List<JsonNode> incidentRows,
      boolean truncated) {
    Map<String, String> serviceNames = new LinkedHashMap<>();
    for (JsonNode def : definitions) {
      String key = text(def, "key");
      if (key != null) {
        serviceNames.put(key, firstNonBlank(text(def, "name"), key));
      }
    }

    Map<String, Instance> instances = new LinkedHashMap<>();
    for (JsonNode row : instanceRows) {
      String start = text(row, "startTime");
      if (start == null) continue;
      LocalDate day = parse(start).atZoneSameInstant(query.zone()).toLocalDate();
      if (day.isBefore(query.from()) || day.isAfter(query.to())) continue;
      String id = text(row, "id");
      instances.put(
          id,
          new Instance(
              id,
              text(row, "processDefinitionKey"),
              text(row, "businessKey"),
              day,
              text(row, "state")));
    }

    // Task names keyed by task id, from every activity row, so failure rows can name the task.
    Map<String, String> taskNames = new LinkedHashMap<>();
    Map<String, List<Activity>> activitiesByRoot = new LinkedHashMap<>();
    for (JsonNode row : activityRows) {
      String type = text(row, "activityType");
      if (type == null || !(type.endsWith("Task") || type.equals("task"))) continue;
      String taskId = text(row, "processDefinitionKey") + ":" + text(row, "activityId");
      String name = firstNonBlank(text(row, "activityName"), text(row, "activityId"));
      taskNames.putIfAbsent(taskId, name);
      String root = rootOf(row);
      if (!instances.containsKey(root)) continue;
      activitiesByRoot
          .computeIfAbsent(root, r -> new ArrayList<>())
          .add(
              new Activity(
                  taskId,
                  name,
                  text(row, "endTime") != null,
                  row.path("canceled").asBoolean(false),
                  row.path("durationInMillis").isNumber()
                      ? row.path("durationInMillis").asLong()
                      : null));
    }

    // Task options come from the service-filtered set, before the task filter narrows it, so
    // picking one task does not make the others disappear from the list.
    Map<String, TaskOption> taskOptions = new LinkedHashMap<>();
    for (Instance instance : instances.values()) {
      for (Activity activity : activitiesByRoot.getOrDefault(instance.id(), List.of())) {
        taskOptions.putIfAbsent(
            activity.taskId(),
            new TaskOption(activity.taskId(), instance.serviceKey(), activity.name()));
      }
    }

    List<Instance> selected = new ArrayList<>();
    for (Instance instance : instances.values()) {
      if (query.tasks().isEmpty()
          || activitiesByRoot.getOrDefault(instance.id(), List.of()).stream()
              .anyMatch(a -> query.tasks().contains(a.taskId()))) {
        selected.add(instance);
      }
    }

    Set<String> openIncidentRoots = new HashSet<>();
    for (JsonNode incident : incidentRows) {
      openIncidentRoots.add(rootOf(incident));
    }

    Map<String, Status> statuses = new LinkedHashMap<>();
    CountBuilder totals = new CountBuilder();
    Map<String, CountBuilder> perService = new LinkedHashMap<>();
    Map<LocalDate, CountBuilder> perDay = new LinkedHashMap<>();
    for (LocalDate d = query.from(); !d.isAfter(query.to()); d = d.plusDays(1)) {
      perDay.put(d, new CountBuilder());
    }
    for (Instance instance : selected) {
      Status status = statusOf(instance, openIncidentRoots);
      statuses.put(instance.id(), status);
      totals.add(status);
      perService.computeIfAbsent(instance.serviceKey(), k -> new CountBuilder()).add(status);
      perDay.get(instance.startDate()).add(status);
    }

    Map<String, TaskAccumulator> tasks = new LinkedHashMap<>();
    for (Instance instance : selected) {
      for (Activity activity : activitiesByRoot.getOrDefault(instance.id(), List.of())) {
        tasks
            .computeIfAbsent(
                activity.taskId(),
                id -> new TaskAccumulator(id, instance.serviceKey(), activity.name()))
            .add(activity);
      }
    }

    List<Failure> failures = new ArrayList<>();
    for (JsonNode incident : incidentRows) {
      String root = rootOf(incident);
      if (statuses.get(root) != Status.FAILED) continue;
      // Each failure also leaves a propagated copy on every parent execution; only the root
      // cause says where it actually went wrong.
      String rootCause = text(incident, "rootCauseIncidentId");
      if (rootCause != null && !rootCause.equals(text(incident, "id"))) continue;
      Instance instance = instances.get(root);
      String taskId = text(incident, "processDefinitionKey") + ":" + text(incident, "activityId");
      failures.add(
          new Failure(
              text(incident, "createTime"),
              instance.serviceKey(),
              serviceName(serviceNames, instance.serviceKey()),
              instance.id(),
              instance.businessKey(),
              firstNonBlank(taskNames.get(taskId), text(incident, "activityId")),
              text(incident, "incidentType"),
              shorten(text(incident, "incidentMessage"))));
    }
    failures.sort(
        Comparator.comparing((Failure f) -> f.time() == null ? OffsetDateTime.MIN : parse(f.time()))
            .reversed());

    List<Option> serviceOptions =
        serviceNames.entrySet().stream()
            .map(e -> new Option(e.getKey(), e.getValue()))
            .sorted(Comparator.comparing(Option::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<TaskOption> taskOptionList =
        taskOptions.values().stream()
            .sorted(
                Comparator.comparing(
                        (TaskOption t) -> serviceName(serviceNames, t.serviceKey()),
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TaskOption::name, String.CASE_INSENSITIVE_ORDER))
            .toList();

    return new StatisticsReport(
        query.from(),
        query.to(),
        query.zone().getId(),
        truncated,
        totals.build(),
        serviceOptions,
        taskOptionList,
        perService.entrySet().stream()
            .map(
                e ->
                    new ServiceCounts(
                        e.getKey(), serviceName(serviceNames, e.getKey()), e.getValue().build()))
            .sorted(Comparator.comparing(ServiceCounts::name, String.CASE_INSENSITIVE_ORDER))
            .toList(),
        perDay.entrySet().stream()
            .map(e -> new DayCounts(e.getKey(), e.getValue().build()))
            .toList(),
        tasks.values().stream()
            .map(TaskAccumulator::build)
            .sorted(
                Comparator.comparing(
                        (TaskStats t) -> serviceName(serviceNames, t.serviceKey()),
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(TaskStats::name, String.CASE_INSENSITIVE_ORDER))
            .toList(),
        failures.stream().limit(MAX_FAILURES).toList());
  }

  private static Status statusOf(Instance instance, Set<String> openIncidentRoots) {
    String state = instance.state() == null ? "" : instance.state();
    return switch (state) {
      case "COMPLETED" -> Status.COMPLETED;
      case "EXTERNALLY_TERMINATED", "INTERNALLY_TERMINATED" -> Status.CANCELLED;
      default -> openIncidentRoots.contains(instance.id()) ? Status.FAILED : Status.IN_PROGRESS;
    };
  }

  private static String rootOf(JsonNode row) {
    return firstNonBlank(text(row, "rootProcessInstanceId"), text(row, "processInstanceId"));
  }

  private static String serviceName(Map<String, String> names, String key) {
    return names.getOrDefault(key, key == null ? "" : key);
  }

  static OffsetDateTime parse(String engineDate) {
    return OffsetDateTime.parse(engineDate, ENGINE_DATE);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }

  private static String firstNonBlank(String a, String b) {
    return a != null && !a.isBlank() ? a : b;
  }

  private static String shorten(String message) {
    if (message == null || message.length() <= MAX_MESSAGE_LENGTH) return message;
    return message.substring(0, MAX_MESSAGE_LENGTH) + "…";
  }

  private static final class CountBuilder {
    private int completed;
    private int inProgress;
    private int failed;
    private int cancelled;

    void add(Status status) {
      switch (status) {
        case COMPLETED -> completed++;
        case IN_PROGRESS -> inProgress++;
        case FAILED -> failed++;
        case CANCELLED -> cancelled++;
      }
    }

    Counts build() {
      return new Counts(
          completed + inProgress + failed + cancelled, completed, inProgress, failed, cancelled);
    }
  }

  private static final class TaskAccumulator {
    private final String id;
    private final String serviceKey;
    private final String name;
    private int completed;
    private int waiting;
    private long totalMs;
    private int timed;

    TaskAccumulator(String id, String serviceKey, String name) {
      this.id = id;
      this.serviceKey = serviceKey;
      this.name = name;
    }

    void add(Activity activity) {
      if (!activity.ended()) {
        waiting++;
      } else if (!activity.canceled()) {
        completed++;
        if (activity.ms() != null) {
          totalMs += activity.ms();
          timed++;
        }
      }
    }

    TaskStats build() {
      return new TaskStats(
          id, serviceKey, name, completed, waiting, timed == 0 ? null : totalMs / timed);
    }
  }
}
