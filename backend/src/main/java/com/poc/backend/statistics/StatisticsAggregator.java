package com.poc.backend.statistics;

import com.poc.backend.statistics.StatisticsReport.BackOffice;
import com.poc.backend.statistics.StatisticsReport.Counts;
import com.poc.backend.statistics.StatisticsReport.DayCounts;
import com.poc.backend.statistics.StatisticsReport.Failure;
import com.poc.backend.statistics.StatisticsReport.Flow;
import com.poc.backend.statistics.StatisticsReport.Option;
import com.poc.backend.statistics.StatisticsReport.Outcomes;
import com.poc.backend.statistics.StatisticsReport.PathCounts;
import com.poc.backend.statistics.StatisticsReport.ServiceCounts;
import com.poc.backend.statistics.StatisticsReport.TaskOption;
import com.poc.backend.statistics.StatisticsReport.TaskStats;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>Outcomes come from the activity rows, never from process variables: a completed case is
 * rejected when its top-level definition ended on an end event whose id contains {@code Rejected}
 * (the spec convention, e.g. {@code EndEvent_Rejected}), otherwise approved. A user task assigned
 * to the case's starter is the applicant's; any other user task is back-office work.
 */
final class StatisticsAggregator {

  private static final String REJECTED_MARKER = "rejected";

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
      String id,
      String serviceKey,
      String businessKey,
      LocalDate startDate,
      String state,
      String startUserId,
      Long durationMs) {}

  private record Activity(
      String taskId,
      String name,
      boolean ended,
      boolean canceled,
      Long ms,
      boolean userTask,
      String assignee,
      String startTime) {}

  /** Per-case facts beyond the status, derived from the case's activities. */
  private record CaseFacts(
      Status status, boolean rejected, boolean returned, boolean backOffice, Long leadMs) {}

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
              text(row, "state"),
              text(row, "startUserId"),
              row.path("durationInMillis").isNumber()
                  ? row.path("durationInMillis").asLong()
                  : null));
    }

    // Task names keyed by task id, from every activity row, so failure rows can name the task.
    Map<String, String> taskNames = new LinkedHashMap<>();
    Map<String, List<Activity>> activitiesByRoot = new LinkedHashMap<>();
    Set<String> rejectedRoots = new HashSet<>();
    for (JsonNode row : activityRows) {
      String type = text(row, "activityType");
      if (type == null) continue;
      if (type.endsWith("EndEvent")) {
        // Only the top-level definition's own end event says how the case ended.
        String root = rootOf(row);
        String activityId = text(row, "activityId");
        if (root.equals(text(row, "processInstanceId"))
            && activityId != null
            && activityId.toLowerCase(Locale.ROOT).contains(REJECTED_MARKER)) {
          rejectedRoots.add(root);
        }
        continue;
      }
      if (!(type.endsWith("Task") || type.equals("task"))) continue;
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
                      : null,
                  type.equals("userTask"),
                  text(row, "assignee"),
                  text(row, "startTime")));
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
    PathBuilder backOfficePath = new PathBuilder();
    PathBuilder directPath = new PathBuilder();
    for (Instance instance : selected) {
      List<Activity> activities = activitiesByRoot.getOrDefault(instance.id(), List.of());
      CaseFacts facts =
          factsOf(instance, activities, statusOf(instance, openIncidentRoots), rejectedRoots);
      statuses.put(instance.id(), facts.status());
      totals.add(facts);
      perService.computeIfAbsent(instance.serviceKey(), k -> new CountBuilder()).add(facts);
      perDay.get(instance.startDate()).add(facts);
      (facts.backOffice() ? backOfficePath : directPath).add(facts);
    }

    Map<String, TaskAccumulator> tasks = new LinkedHashMap<>();
    TaskAccumulator backOffice = new TaskAccumulator(null, null, null);
    for (Instance instance : selected) {
      for (Activity activity : activitiesByRoot.getOrDefault(instance.id(), List.of())) {
        tasks
            .computeIfAbsent(
                activity.taskId(),
                id -> new TaskAccumulator(id, instance.serviceKey(), activity.name()))
            .add(activity);
        if (isBackOffice(activity, instance)) {
          backOffice.add(activity);
        }
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
        totals.outcomes(),
        serviceOptions,
        taskOptionList,
        perService.entrySet().stream()
            .map(
                e ->
                    new ServiceCounts(
                        e.getKey(),
                        serviceName(serviceNames, e.getKey()),
                        e.getValue().build(),
                        e.getValue().outcomes()))
            .sorted(Comparator.comparing(ServiceCounts::name, String.CASE_INSENSITIVE_ORDER))
            .toList(),
        perDay.entrySet().stream()
            .map(e -> new DayCounts(e.getKey(), e.getValue().build(), e.getValue().outcomes()))
            .toList(),
        new Flow(backOfficePath.build(), directPath.build()),
        backOffice.buildBackOffice(),
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

  /**
   * A user task is the applicant's own when it is assigned to the user who started the case; every
   * other user task, assigned to someone else or still unclaimed, is back-office work.
   */
  private static boolean isBackOffice(Activity activity, Instance instance) {
    return activity.userTask()
        && (activity.assignee() == null || !activity.assignee().equals(instance.startUserId()));
  }

  private static CaseFacts factsOf(
      Instance instance, List<Activity> activities, Status status, Set<String> rejectedRoots) {
    boolean backOffice = false;
    Map<String, Integer> applicantTaskRuns = new HashMap<>();
    for (Activity activity : activities) {
      if (isBackOffice(activity, instance)) {
        backOffice = true;
      } else if (activity.userTask()) {
        applicantTaskRuns.merge(activity.taskId(), 1, Integer::sum);
      }
    }
    boolean returned = applicantTaskRuns.values().stream().anyMatch(n -> n > 1);
    boolean completed = status == Status.COMPLETED;
    return new CaseFacts(
        status,
        completed && rejectedRoots.contains(instance.id()),
        returned,
        backOffice,
        completed ? instance.durationMs() : null);
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
    private int rejected;
    private int returned;
    private int touchless;
    private int reachedBackOffice;
    private long leadMs;
    private int timed;

    void add(CaseFacts facts) {
      switch (facts.status()) {
        case COMPLETED -> completed++;
        case IN_PROGRESS -> inProgress++;
        case FAILED -> failed++;
        case CANCELLED -> cancelled++;
      }
      if (facts.rejected()) rejected++;
      if (facts.returned()) returned++;
      if (facts.backOffice()) {
        reachedBackOffice++;
      } else if (facts.status() == Status.COMPLETED) {
        touchless++;
      }
      if (facts.leadMs() != null) {
        leadMs += facts.leadMs();
        timed++;
      }
    }

    Counts build() {
      return new Counts(
          completed + inProgress + failed + cancelled, completed, inProgress, failed, cancelled);
    }

    Outcomes outcomes() {
      return new Outcomes(
          completed - rejected,
          rejected,
          returned,
          touchless,
          reachedBackOffice,
          timed == 0 ? null : leadMs / timed);
    }
  }

  private static final class PathBuilder {
    private int approved;
    private int rejected;
    private int inProgress;
    private int failed;
    private int cancelled;

    void add(CaseFacts facts) {
      switch (facts.status()) {
        case COMPLETED -> {
          if (facts.rejected()) rejected++;
          else approved++;
        }
        case IN_PROGRESS -> inProgress++;
        case FAILED -> failed++;
        case CANCELLED -> cancelled++;
      }
    }

    PathCounts build() {
      return new PathCounts(approved, rejected, inProgress, failed, cancelled);
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
    private String oldestWaitingSince;

    TaskAccumulator(String id, String serviceKey, String name) {
      this.id = id;
      this.serviceKey = serviceKey;
      this.name = name;
    }

    void add(Activity activity) {
      if (!activity.ended()) {
        waiting++;
        if (activity.startTime() != null
            && (oldestWaitingSince == null
                || parse(activity.startTime()).isBefore(parse(oldestWaitingSince)))) {
          oldestWaitingSince = activity.startTime();
        }
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

    BackOffice buildBackOffice() {
      return new BackOffice(
          completed, waiting, timed == 0 ? null : totalMs / timed, oldestWaitingSince);
    }
  }
}
