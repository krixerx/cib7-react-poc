package com.poc.backend.cases;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The latest status card of one case — a prose summary the process itself re-posts at every
 * milestone (submitted / sent back / awaiting medical / rejected / completed) via its "Index case"
 * BPMN service tasks. Keyed by process instance id, so each re-post replaces the previous card and
 * the table always holds exactly one card per case.
 *
 * <p>The summary is written for an LLM reader, not a UI: natural language, information-dense,
 * phrased the way a user would ask about their case. Retrieval is plain filtering + keyword rank
 * ({@link CaseSearchController}) — the AI client does the semantic matching over the returned
 * cards; no embeddings involved.
 */
@Entity
@Table(name = "case_cards")
public class CaseCard {

  /** Max stored summary length; longer ingests are truncated, never rejected. */
  public static final int SUMMARY_MAX_LENGTH = 4000;

  @Id private String processInstanceId;

  /** Service key, e.g. transport-vehicle-registration. */
  @Column(nullable = false)
  private String service;

  /** Latest milestone, e.g. submitted / sent-back / rejected / registered. */
  @Column(nullable = false)
  private String status;

  @Column(nullable = false, length = SUMMARY_MAX_LENGTH)
  private String summary;

  @Column(nullable = false)
  private Instant updatedAt;

  protected CaseCard() {
    // JPA
  }

  public CaseCard(String processInstanceId, String service, String status, String summary) {
    this.processInstanceId = processInstanceId;
    this.service = service;
    this.status = status;
    this.summary =
        summary.length() > SUMMARY_MAX_LENGTH ? summary.substring(0, SUMMARY_MAX_LENGTH) : summary;
    this.updatedAt = Instant.now();
  }

  public String getProcessInstanceId() {
    return processInstanceId;
  }

  public String getService() {
    return service;
  }

  public String getStatus() {
    return status;
  }

  public String getSummary() {
    return summary;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
