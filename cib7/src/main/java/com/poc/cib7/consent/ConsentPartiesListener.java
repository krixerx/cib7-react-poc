package com.poc.cib7.consent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.poc.cib7.links.CapabilityLinks;
import java.time.Instant;
import java.util.Collection;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.DelegateTask;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.spin.plugin.variable.SpinValues;

/**
 * Turns the co-owner / co-founder list an applicant submitted into server-owned consent state
 * (docs/security.md rule 3). Attached as a {@code complete} task listener on the applicant's submit
 * task, so it runs after the submitted variables are written and before the case moves on.
 *
 * <p>It keeps only {@code name} and {@code email} of each party, assigns party ids ({@code p1},
 * {@code p2}, ...; the applicant is {@code applicant}), and drops anything else the client sent:
 * tokens, party ids, a pre-filled confirmations map, the reject/sent flags. The confirmations map
 * is rebuilt keyed by party id with only the applicant approved, and {@code consentRound} is
 * replaced so every link minted for an earlier round stops verifying.
 *
 * <p>The round is the submission time in epoch milliseconds rather than "previous + 1": the
 * previous value is a process variable, and the client could have written it in this same request.
 * A timestamp grows on every resubmission without reading anything the client controls.
 *
 * <p>Configured per process through field injection: {@code partiesVariable} (the list, e.g. {@code
 * additionalOwners}), {@code confirmationsVariable} ({@code ownerConfirmations}), {@code
 * rejectedVariable} ({@code rejectedByOwner}) and {@code sentVariable} ({@code sentToProcess}).
 */
public class ConsentPartiesListener implements TaskListener {

  static final int MAX_NAME = 200;
  static final int MAX_EMAIL = 254;

  /** Variables earlier versions let the browser write; removed so no stale token survives. */
  private static final String LEGACY_APPLICANT_TOKEN = "applicantToken";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Expression partiesVariable;
  private Expression confirmationsVariable;
  private Expression rejectedVariable;
  private Expression sentVariable;

  @Override
  public void notify(DelegateTask task) {
    DelegateExecution execution = task.getExecution();
    String parties = name(partiesVariable, task);
    String confirmations = name(confirmationsVariable, task);

    ArrayNode sanitized = sanitize(execution.getVariable(parties));
    ObjectNode initial = MAPPER.createObjectNode();
    ObjectNode applicant = initial.putObject(CapabilityLinks.APPLICANT_PARTY);
    applicant.put("status", "approved");
    applicant.put("signedAt", Instant.now().toString());

    execution.setVariable(parties, SpinValues.jsonValue(sanitized.toString()).create());
    execution.setVariable(confirmations, SpinValues.jsonValue(initial.toString()).create());
    execution.setVariable(CapabilityLinks.ROUND_VARIABLE, System.currentTimeMillis());
    execution.setVariable(name(rejectedVariable, task), false);
    execution.setVariable(name(sentVariable, task), false);
    if (execution.hasVariable(LEGACY_APPLICANT_TOKEN)) {
      execution.removeVariable(LEGACY_APPLICANT_TOKEN);
    }
  }

  /**
   * Reduces whatever the client sent (Spin JSON, a JSON string, a list of maps, or nothing) to
   * {@code [{partyId, name, email}]}. Entries without a name and an email are dropped; the form
   * validates both, so a gap here means a client that skipped the form.
   */
  static ArrayNode sanitize(Object raw) {
    ArrayNode out = MAPPER.createArrayNode();
    JsonNode parsed = parse(raw);
    if (parsed == null || !parsed.isArray()) {
      return out;
    }
    int next = 1;
    for (JsonNode entry : parsed) {
      String name = text(entry, "name", MAX_NAME);
      String email = text(entry, "email", MAX_EMAIL);
      if (name.isEmpty() || email.isEmpty()) {
        continue;
      }
      ObjectNode party = out.addObject();
      party.put("partyId", "p" + next++);
      party.put("name", name);
      party.put("email", email);
    }
    return out;
  }

  private static JsonNode parse(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      if (raw instanceof Collection<?> || raw instanceof java.util.Map<?, ?>) {
        return MAPPER.valueToTree(raw);
      }
      // SpinJsonNode.toString() and a plain String variable both yield the JSON text.
      return MAPPER.readTree(raw.toString());
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return null;
    }
  }

  private static String text(JsonNode entry, String field, int max) {
    JsonNode value = entry.path(field);
    if (!value.isTextual()) {
      return "";
    }
    String trimmed = value.asText().trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static String name(Expression expression, DelegateTask task) {
    if (expression == null) {
      throw new IllegalStateException("ConsentPartiesListener is missing a field");
    }
    return String.valueOf(expression.getValue(task));
  }
}
