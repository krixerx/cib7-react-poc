package com.poc.backend.cases;

import com.poc.backend.security.CaseAccessService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval over case summary cards — the backend half of the {@code search_cases} MCP tool.
 * Deliberately <em>not</em> semantic: it narrows by exact {@code service} / {@code status} and
 * ranks by keyword overlap with {@code q}, and the AI client reading the returned prose summaries
 * does the actual meaning-matching. At this corpus size (one short card per case) shipping the
 * candidates to the LLM beats maintaining an embedding index.
 *
 * <p>With no parameters it returns the caller's newest cards, so an AI client can also use it as
 * "give me an overview of my cases".
 *
 * <p>Same over-fetch-then-post-filter authorization as the documents API: every candidate card runs
 * through {@link CaseAccessService} before it may be returned, and the access check runs
 * <em>after</em> the cheap filters and only until {@code limit} accessible cards are collected, so
 * engine lookups stay bounded.
 */
@RestController
@RequestMapping("/api/cases")
public class CaseSearchController {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 50;

  /** Tokens shorter than this carry no signal ("of", "my", "is") and are ignored. */
  private static final int MIN_TOKEN_LENGTH = 3;

  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

  private final CaseCardRepository repository;
  private final CaseAccessService caseAccess;

  public CaseSearchController(CaseCardRepository repository, CaseAccessService caseAccess) {
    this.repository = repository;
    this.caseAccess = caseAccess;
  }

  @GetMapping("/search")
  public ResponseEntity<?> search(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "service", required = false) String service,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit) {
    int max = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
    Set<String> queryTokens = tokenize(q);

    List<ScoredCard> candidates = new ArrayList<>();
    for (CaseCard card : repository.findAllByOrderByUpdatedAtDesc()) {
      if (service != null && !service.isBlank() && !service.equals(card.getService())) continue;
      if (status != null && !status.isBlank() && !status.equals(card.getStatus())) continue;
      int matched = 0;
      if (!queryTokens.isEmpty()) {
        Set<String> cardTokens =
            tokenize(card.getSummary() + " " + card.getService() + " " + card.getStatus());
        cardTokens.retainAll(queryTokens);
        matched = cardTokens.size();
        if (matched == 0) continue;
      }
      candidates.add(new ScoredCard(card, matched));
    }
    // Repository order is already newest-first; a stable sort on match count
    // keeps recency as the tiebreaker.
    candidates.sort(Comparator.comparingInt((ScoredCard c) -> c.matched).reversed());

    List<CaseHit> hits = new ArrayList<>();
    for (ScoredCard candidate : candidates) {
      if (hits.size() >= max) break;
      if (!caseAccess.canAccessCase(candidate.card.getProcessInstanceId())) continue;
      hits.add(toHit(candidate.card));
    }

    return ResponseEntity.ok(new SearchResponse(q, hits.size(), hits));
  }

  private static Set<String> tokenize(String text) {
    Set<String> tokens = new HashSet<>();
    if (text == null || text.isBlank()) return tokens;
    for (String token : NON_ALNUM.split(text.toLowerCase(Locale.ROOT))) {
      if (token.length() >= MIN_TOKEN_LENGTH) tokens.add(token);
    }
    return tokens;
  }

  private static CaseHit toHit(CaseCard card) {
    return new CaseHit(
        card.getProcessInstanceId(),
        card.getService(),
        card.getStatus(),
        card.getSummary(),
        card.getUpdatedAt());
  }

  private record ScoredCard(CaseCard card, int matched) {}

  public record CaseHit(
      String processInstanceId, String service, String status, String summary, Instant updatedAt) {}

  public record SearchResponse(String query, int count, List<CaseHit> results) {}
}
