package com.poc.backend.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.poc.backend.statistics.StatisticsReport.Counts;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The counting rules of docs/statistics.md, checked against hand-written engine history rows. */
class StatisticsAggregatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId TALLINN = ZoneId.of("Europe/Tallinn");
  private static final LocalDate DAY1 = LocalDate.parse("2026-10-01");
  private static final LocalDate DAY2 = LocalDate.parse("2026-10-02");

  private static JsonNode json(String text) {
    return MAPPER.readTree(text.replace('\'', '"'));
  }

  private static JsonNode definition(String key, String name) {
    return json("{'key':'" + key + "','name':'" + name + "'}");
  }

  private static JsonNode instance(String id, String key, String start, String state) {
    return json(
        "{'id':'"
            + id
            + "','processDefinitionKey':'"
            + key
            + "','businessKey':'BK-"
            + id
            + "','startTime':'"
            + start
            + "','state':'"
            + state
            + "'}");
  }

  private static JsonNode activity(
      String root, String pi, String key, String activityId, String type, Long durationMs) {
    String end = durationMs == null ? "null" : "'2026-10-01T12:00:00.000+0000'";
    String duration = durationMs == null ? "null" : String.valueOf(durationMs);
    return json(
        "{'rootProcessInstanceId':'"
            + root
            + "','processInstanceId':'"
            + pi
            + "','processDefinitionKey':'"
            + key
            + "','activityId':'"
            + activityId
            + "','activityName':'Name of "
            + activityId
            + "','activityType':'"
            + type
            + "','endTime':"
            + end
            + ",'durationInMillis':"
            + duration
            + ",'canceled':false}");
  }

  private static JsonNode incident(String id, String rootCause, String root, String pi) {
    return json(
        "{'id':'"
            + id
            + "','rootCauseIncidentId':'"
            + rootCause
            + "','rootProcessInstanceId':'"
            + root
            + "','processInstanceId':'"
            + pi
            + "','processDefinitionKey':'sub','activityId':'Task_Call',"
            + "'createTime':'2026-10-01T10:00:00.000+0000','incidentType':'failedJob',"
            + "'incidentMessage':'Connection refused'}");
  }

  private static StatisticsQuery query(Set<String> tasks) {
    return new StatisticsQuery(DAY1, DAY2, TALLINN, Set.of(), tasks);
  }

  private final List<JsonNode> definitions =
      List.of(definition("vehicle", "Vehicle registration"), definition("business", "Business"));

  private final List<JsonNode> instances =
      List.of(
          instance("p1", "vehicle", "2026-10-01T08:00:00.000+0000", "COMPLETED"),
          instance("p2", "vehicle", "2026-10-01T09:00:00.000+0000", "ACTIVE"),
          instance("p3", "vehicle", "2026-10-02T09:00:00.000+0000", "ACTIVE"),
          instance("p4", "business", "2026-10-02T09:00:00.000+0000", "EXTERNALLY_TERMINATED"),
          instance("p5", "business", "2026-10-02T09:00:00.000+0000", "COMPLETED"));

  private final List<JsonNode> activities =
      List.of(
          activity("p1", "p1", "vehicle", "Task_Review", "userTask", 1000L),
          activity("p2", "p2", "vehicle", "Task_Review", "userTask", null),
          activity("p3", "sub-1", "sub", "Task_Call", "serviceTask", null),
          activity("p3", "p3", "vehicle", "Call_Sub", "callActivity", null),
          activity("p5", "p5", "business", "Task_Check", "userTask", 3000L));

  // p3 failed in its sub-process: a root-cause incident there and a propagated copy on p3.
  // p5 had an incident that is resolved by now, so it is not in the open list.
  private final List<JsonNode> openIncidents =
      List.of(incident("i1", "i1", "p3", "sub-1"), incident("i2", "i1", "p3", "p3"));

  @Test
  void eachInstanceHasExactlyOneStatus() {
    StatisticsReport report =
        StatisticsAggregator.aggregate(
            query(Set.of()), definitions, instances, activities, openIncidents, false);

    assertThat(report.totals()).isEqualTo(new Counts(5, 2, 1, 1, 1));
    assertThat(report.perDay()).extracting(d -> d.counts().started()).containsExactly(2, 3);
    assertThat(report.perService())
        .extracting(s -> s.name() + "=" + s.counts().started())
        .containsExactly("Business=2", "Vehicle registration=3");
  }

  @Test
  void subProcessTasksAndFailuresCountUnderTheParent() {
    StatisticsReport report =
        StatisticsAggregator.aggregate(
            query(Set.of()), definitions, instances, activities, openIncidents, false);

    assertThat(report.taskStats())
        .extracting(t -> t.id() + " done=" + t.completed() + " waiting=" + t.waiting())
        .containsExactlyInAnyOrder(
            "business:Task_Check done=1 waiting=0",
            "vehicle:Task_Review done=1 waiting=1",
            "sub:Task_Call done=0 waiting=1");
    assertThat(report.taskStats())
        .filteredOn(t -> t.id().equals("vehicle:Task_Review"))
        .extracting(StatisticsReport.TaskStats::avgDurationMs)
        .containsExactly(1000L);
    // The propagated copy of the incident is not listed a second time.
    assertThat(report.failures()).hasSize(1);
    assertThat(report.failures().get(0).processInstanceId()).isEqualTo("p3");
    assertThat(report.failures().get(0).task()).isEqualTo("Name of Task_Call");
    assertThat(report.failures().get(0).serviceName()).isEqualTo("Vehicle registration");
  }

  @Test
  void taskFilterKeepsOnlyCasesThatReachedTheTaskButNotTheTaskList() {
    StatisticsReport report =
        StatisticsAggregator.aggregate(
            query(Set.of("vehicle:Task_Review")),
            definitions,
            instances,
            activities,
            openIncidents,
            false);

    assertThat(report.totals()).isEqualTo(new Counts(2, 1, 1, 0, 0));
    assertThat(report.tasks()).hasSize(3);
    assertThat(report.failures()).isEmpty();
  }

  @Test
  void dayBoundariesFollowTheCallersZone() {
    // 21:30 UTC on 30 Sep is 00:30 on 1 Oct in Tallinn; 21:30 UTC on 2 Oct is already 3 Oct.
    List<JsonNode> edge =
        List.of(
            instance("e1", "vehicle", "2026-09-30T21:30:00.000+0000", "COMPLETED"),
            instance("e2", "vehicle", "2026-10-02T21:30:00.000+0000", "COMPLETED"));

    StatisticsReport report =
        StatisticsAggregator.aggregate(
            query(Set.of()), definitions, edge, List.of(), List.of(), false);

    assertThat(report.totals().started()).isEqualTo(1);
    assertThat(report.perDay().get(0).counts().started()).isEqualTo(1);
  }
}
