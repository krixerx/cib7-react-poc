package com.poc.backend.statistics;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Filters of one statistics request. Dates are inclusive calendar days in {@code zone}, the
 * caller's own timezone, so "today" means the user's today rather than the server's.
 */
public record StatisticsQuery(
    LocalDate from, LocalDate to, ZoneId zone, Set<String> services, Set<String> tasks) {}
