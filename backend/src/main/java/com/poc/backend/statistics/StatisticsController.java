package com.poc.backend.statistics;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Process statistics for the back office. Only callers with the {@code statistics-viewer} realm
 * role reach this controller; {@code SecurityConfig} enforces that before any code here runs.
 *
 * <p>Every request is logged with its filters. The engine sees only the {@code cib7-business}
 * service account, so this line (with {@code user_id} from the MDC) is the record of who looked.
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

  private static final Logger LOG = LoggerFactory.getLogger(StatisticsController.class);

  static final int MAX_RANGE_DAYS = 366;
  private static final int MAX_FILTER_VALUES = 50;

  /** Process definition keys, and task ids of the form {@code <key>:<activityId>}. */
  private static final Pattern FILTER_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,255}");

  private final StatisticsService statistics;

  public StatisticsController(StatisticsService statistics) {
    this.statistics = statistics;
  }

  @GetMapping
  public ResponseEntity<?> report(
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(name = "zone", defaultValue = "UTC") String zone,
      @RequestParam(name = "service", required = false) List<String> services,
      @RequestParam(name = "task", required = false) List<String> tasks) {
    LocalDate fromDate;
    LocalDate toDate;
    ZoneId zoneId;
    try {
      fromDate = LocalDate.parse(from);
      toDate = LocalDate.parse(to);
    } catch (DateTimeParseException e) {
      return badRequest("invalid_date", "from and to must be dates in the form YYYY-MM-DD.");
    }
    try {
      zoneId = ZoneId.of(zone);
    } catch (DateTimeException e) {
      return badRequest("invalid_zone", "zone must be an IANA timezone such as Europe/Tallinn.");
    }
    if (toDate.isBefore(fromDate)) {
      return badRequest("invalid_range", "to must not be before from.");
    }
    if (ChronoUnit.DAYS.between(fromDate, toDate) >= MAX_RANGE_DAYS) {
      return badRequest(
          "invalid_range", "The range may cover at most " + MAX_RANGE_DAYS + " days.");
    }
    Set<String> serviceSet = filterValues(services);
    Set<String> taskSet = filterValues(tasks);
    if (serviceSet == null || taskSet == null) {
      return badRequest(
          "invalid_filter",
          "service and task take up to " + MAX_FILTER_VALUES + " identifiers each.");
    }

    LOG.info(
        "Statistics requested: {}..{} zone={} services={} tasks={}",
        fromDate,
        toDate,
        zoneId,
        serviceSet,
        taskSet);
    return ResponseEntity.ok(
        statistics.report(new StatisticsQuery(fromDate, toDate, zoneId, serviceSet, taskSet)));
  }

  /** The distinct values, or {@code null} when any of them is malformed or there are too many. */
  private static Set<String> filterValues(List<String> values) {
    Set<String> result = new LinkedHashSet<>();
    if (values == null) return result;
    for (String value : values) {
      if (value == null || value.isBlank()) continue;
      if (!FILTER_VALUE.matcher(value).matches()) return null;
      result.add(value);
    }
    return result.size() > MAX_FILTER_VALUES ? null : result;
  }

  private static ResponseEntity<ErrorResponse> badRequest(String code, String message) {
    return ResponseEntity.badRequest().body(new ErrorResponse(code, message));
  }

  public record ErrorResponse(String code, String message) {}
}
