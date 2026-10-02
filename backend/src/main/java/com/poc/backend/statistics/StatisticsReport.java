package com.poc.backend.statistics;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the statistics page draws, already aggregated. The page never receives individual
 * process instances apart from the failure list, which is what civil servants already see on the
 * incidents page.
 */
public record StatisticsReport(
    LocalDate from,
    LocalDate to,
    String zone,
    boolean truncated,
    Counts totals,
    List<Option> services,
    List<TaskOption> tasks,
    List<ServiceCounts> perService,
    List<DayCounts> perDay,
    List<TaskStats> taskStats,
    List<Failure> failures) {

  /** Each started service has exactly one status, so the four buckets add up to started. */
  public record Counts(int started, int completed, int inProgress, int failed, int cancelled) {}

  public record Option(String key, String name) {}

  /** {@code id} is {@code <processDefinitionKey>:<activityId>} of the task's own definition. */
  public record TaskOption(String id, String serviceKey, String name) {}

  public record ServiceCounts(String key, String name, Counts counts) {}

  public record DayCounts(LocalDate date, Counts counts) {}

  public record TaskStats(
      String id, String serviceKey, String name, int completed, int waiting, Long avgDurationMs) {}

  public record Failure(
      String time,
      String serviceKey,
      String serviceName,
      String processInstanceId,
      String businessKey,
      String task,
      String type,
      String message) {}
}
