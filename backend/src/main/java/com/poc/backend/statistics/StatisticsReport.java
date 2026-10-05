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
    Outcomes outcomes,
    List<Option> services,
    List<TaskOption> tasks,
    List<ServiceCounts> perService,
    List<DayCounts> perDay,
    Flow flow,
    BackOffice backOffice,
    List<TaskStats> taskStats,
    List<Failure> failures) {

  /** Each started service has exactly one status, so the four buckets add up to started. */
  public record Counts(int started, int completed, int inProgress, int failed, int cancelled) {}

  /**
   * What happened to the cases, beyond their status. {@code approved} and {@code rejected} split
   * the completed cases by the end event they finished on; {@code touchless} are completed cases
   * that never had a back-office task; {@code returned} are cases whose applicant had to do the
   * same task more than once. {@code avgLeadTimeMs} is start to end of the completed cases.
   */
  public record Outcomes(
      int approved,
      int rejected,
      int returned,
      int touchless,
      int reachedBackOffice,
      Long avgLeadTimeMs) {}

  /** Where the cases of one path stand now; the status buckets with completed split in two. */
  public record PathCounts(int approved, int rejected, int inProgress, int failed, int cancelled) {}

  /** The cases that reached a back-office task and those that have not (or never will). */
  public record Flow(PathCounts backOffice, PathCounts direct) {}

  /**
   * The back office as a team: user tasks of the selected cases that are not the applicant's own.
   * Deliberately no per-person figures. {@code oldestWaitingSince} is the engine timestamp of the
   * longest-waiting open task.
   */
  public record BackOffice(int done, int waiting, Long avgTaskMs, String oldestWaitingSince) {}

  public record Option(String key, String name) {}

  /** {@code id} is {@code <processDefinitionKey>:<activityId>} of the task's own definition. */
  public record TaskOption(String id, String serviceKey, String name) {}

  public record ServiceCounts(String key, String name, Counts counts, Outcomes outcomes) {}

  public record DayCounts(LocalDate date, Counts counts, Outcomes outcomes) {}

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
