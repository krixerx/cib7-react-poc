package com.poc.backend.consent;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.links.CapabilityLinkVerifier;
import com.poc.backend.links.CapabilityLinkVerifier.CapabilityLink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The co-signing flow shared by co-owner confirmation (vehicle registration) and co-founder
 * signature (business registration), parameterised by {@link Config}.
 *
 * <p>The acting party is identified only by the verified capability token (docs/security.md rule
 * 3): it carries the case id, the party id and the consent round. The engine's {@code
 * ConsentPartiesListener} assigned the party ids and seeded the confirmations map keyed by them, so
 * nothing the applicant's browser wrote decides who may sign. Status snapshots carry each party's
 * display name and state, never another party's email or any token.
 */
public final class ConsentFlow {

  /** Party id of the applicant; the engine pre-approves it on every submission. */
  public static final String APPLICANT = "applicant";

  /** Engine variable names and messages of one flow. */
  public record Config(
      String processKey,
      String purpose,
      String firstNameVariable,
      String lastNameVariable,
      String partiesVariable,
      String confirmationsVariable,
      String rejectedVariable,
      String sentVariable,
      String signatureMessage,
      String sendMessage,
      String rejectionPrefix,
      String rejectionVerb) {}

  /** One party as another party may see it. */
  public record Party(
      String partyId,
      String name,
      boolean isApplicant,
      String status,
      String signedAt,
      String reason) {}

  /** Everything a status response needs; controllers map it to their own DTOs. */
  public record Snapshot(
      String processInstanceId,
      String applicantName,
      Party current,
      List<Party> parties,
      String state,
      String rejectedBy,
      String rejectionReason) {}

  /** A refused action: HTTP status plus the error body the SPA already understands. */
  public record Refusal(int httpStatus, String code, String message) {}

  /** Either a snapshot or a refusal. */
  public record Outcome(Snapshot snapshot, Refusal refusal) {
    static Outcome ok(Snapshot snapshot) {
      return new Outcome(snapshot, null);
    }

    static Outcome refused(int status, String code, String message) {
      return new Outcome(null, new Refusal(status, code, message));
    }
  }

  /** Messages that differ between the two flows. */
  public record Messages(
      String unknownLink,
      String alreadyRejected,
      String alreadySent,
      String alreadySigned,
      String notWaiting,
      String rejectedBack,
      String notReady,
      String notWaitingForSend) {}

  private final Config config;
  private final Messages messages;
  private final EngineClient engine;
  private final CapabilityLinkVerifier verifier;
  private final ObjectMapper mapper = new ObjectMapper();

  public ConsentFlow(
      Config config, Messages messages, EngineClient engine, CapabilityLinkVerifier verifier) {
    this.config = config;
    this.messages = messages;
    this.engine = engine;
    this.verifier = verifier;
  }

  public Outcome status(String token) {
    Optional<Context> ctx = resolve(token);
    if (ctx.isEmpty()) {
      return unknown();
    }
    return Outcome.ok(snapshot(ctx.get()));
  }

  public Outcome sign(String token, String decision, String reason) {
    Optional<Context> resolved = resolve(token);
    if (resolved.isEmpty()) {
      return unknown();
    }
    Context ctx = resolved.get();
    if (APPLICANT.equals(ctx.link().partyId())) {
      return Outcome.refused(
          409, "applicant_auto_confirmed", "The applicant's signature is recorded automatically.");
    }
    String pi = ctx.link().processInstanceId();
    if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.rejectedVariable()))) {
      return Outcome.refused(409, "already_rejected", messages.alreadyRejected());
    }
    if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.sentVariable()))) {
      return Outcome.refused(409, "already_sent", messages.alreadySent());
    }
    ObjectNode confirmations = ctx.confirmations();
    if (confirmations.has(ctx.link().partyId())) {
      return Outcome.refused(409, "already_signed", messages.alreadySigned());
    }

    // Persist the outcome BEFORE correlation so the status endpoint reflects it even if a
    // sibling instance fires the completion condition immediately after.
    ObjectNode entry = mapper.createObjectNode();
    entry.put("status", "approve".equals(decision) ? "approved" : "rejected");
    entry.put("signedAt", Instant.now().toString());
    if (reason != null && !reason.isBlank()) {
      entry.put("reason", reason.trim());
    }
    confirmations.set(ctx.link().partyId(), entry);
    engine.setJsonVariable(pi, config.confirmationsVariable(), confirmations);

    if ("reject".equals(decision)) {
      // Process-scope flag drives the multi-instance completionCondition and the gateway after
      // it; the local correlation key only picks WHICH waiting instance is unblocked.
      engine.setBooleanVariable(pi, config.rejectedVariable(), true);
      engine.setStringVariable(
          pi,
          "sendBackReason",
          config.rejectionPrefix()
              + " "
              + ctx.partyName()
              + " "
              + config.rejectionVerb()
              + ": "
              + reason.trim());
    }

    boolean correlated =
        engine.correlateMessage(
            config.signatureMessage(), pi, Map.of("partyId", ctx.link().partyId()), Map.of());
    if (!correlated) {
      return Outcome.refused(409, "not_waiting", messages.notWaiting());
    }
    return Outcome.ok(snapshot(reload(ctx)));
  }

  public Outcome send(String token) {
    Optional<Context> resolved = resolve(token);
    if (resolved.isEmpty()) {
      return unknown();
    }
    Context ctx = resolved.get();
    String pi = ctx.link().processInstanceId();
    if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.sentVariable()))) {
      return Outcome.refused(409, "already_sent", messages.alreadySent());
    }
    if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.rejectedVariable()))) {
      return Outcome.refused(409, "already_rejected", messages.rejectedBack());
    }
    if (!"ready_to_send".equals(snapshot(ctx).state())) {
      return Outcome.refused(409, "not_ready", messages.notReady());
    }
    boolean correlated =
        engine.correlateMessage(
            config.sendMessage(), pi, Map.of(), Map.of(config.sentVariable(), true));
    if (!correlated) {
      return Outcome.refused(409, "not_waiting", messages.notWaitingForSend());
    }
    return Outcome.ok(snapshot(reload(ctx)));
  }

  // --- helpers ---------------------------------------------------------

  private record Context(
      CapabilityLink link,
      String applicantName,
      JsonNode parties,
      ObjectNode confirmations,
      String partyName) {}

  private Outcome unknown() {
    return Outcome.refused(404, "unknown_token", messages.unknownLink());
  }

  /**
   * Verified link plus the case data. A token whose party is not (or no longer) on the case's party
   * list is treated like any other invalid link.
   */
  private Optional<Context> resolve(String token) {
    Optional<CapabilityLink> link =
        verifier.verifyConsent(token, config.purpose(), config.processKey());
    if (link.isEmpty()) {
      return Optional.empty();
    }
    Context ctx = load(link.get());
    return ctx.partyName() == null ? Optional.empty() : Optional.of(ctx);
  }

  private Context reload(Context ctx) {
    return load(ctx.link());
  }

  private Context load(CapabilityLink link) {
    String pi = link.processInstanceId();
    String applicantName =
        (Objects.toString(engine.getStringVariable(pi, config.firstNameVariable()), "")
                + " "
                + Objects.toString(engine.getStringVariable(pi, config.lastNameVariable()), ""))
            .trim();
    JsonNode parties = engine.getJsonVariable(pi, config.partiesVariable());
    JsonNode rawConfirmations = engine.getJsonVariable(pi, config.confirmationsVariable());
    ObjectNode confirmations =
        rawConfirmations instanceof ObjectNode obj ? obj : mapper.createObjectNode();
    String partyName = null;
    if (APPLICANT.equals(link.partyId())) {
      partyName = applicantName;
    } else if (parties != null && parties.isArray()) {
      for (JsonNode party : parties) {
        if (link.partyId().equals(party.path("partyId").asText(null))) {
          partyName = party.path("name").asText("");
        }
      }
    }
    return new Context(link, applicantName, parties, confirmations, partyName);
  }

  private Snapshot snapshot(Context ctx) {
    String pi = ctx.link().processInstanceId();
    List<Party> parties = new ArrayList<>();
    parties.add(party(APPLICANT, ctx.applicantName(), true, ctx.confirmations()));
    if (ctx.parties() != null && ctx.parties().isArray()) {
      for (JsonNode p : ctx.parties()) {
        parties.add(
            party(
                p.path("partyId").asText(""),
                p.path("name").asText(""),
                false,
                ctx.confirmations()));
      }
    }
    boolean allApproved = parties.stream().allMatch(p -> "approved".equals(p.status()));
    String state;
    if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.rejectedVariable()))) {
      state = "rejected";
    } else if (Boolean.TRUE.equals(engine.getBooleanVariable(pi, config.sentVariable()))) {
      state = "sent";
    } else if (allApproved) {
      state = "ready_to_send";
    } else {
      state = "pending";
    }
    Party current =
        parties.stream()
            .filter(p -> p.partyId().equals(ctx.link().partyId()))
            .findFirst()
            .orElse(null);
    Party rejecter =
        parties.stream().filter(p -> "rejected".equals(p.status())).findFirst().orElse(null);
    return new Snapshot(
        pi,
        ctx.applicantName(),
        current,
        parties,
        state,
        rejecter != null ? rejecter.name() : null,
        rejecter != null ? rejecter.reason() : null);
  }

  private static Party party(
      String partyId, String name, boolean isApplicant, ObjectNode confirmations) {
    String status = "pending";
    String signedAt = null;
    String reason = null;
    JsonNode entry = confirmations.get(partyId);
    if (entry != null) {
      if (entry.hasNonNull("status")) status = entry.get("status").asText();
      if (entry.hasNonNull("signedAt")) signedAt = entry.get("signedAt").asText();
      if (entry.hasNonNull("reason")) reason = entry.get("reason").asText();
    }
    return new Party(partyId, name, isApplicant, status, signedAt, reason);
  }
}
