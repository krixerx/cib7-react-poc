package com.poc.backend.payment;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.links.CapabilityLinkVerifier;
import com.poc.backend.security.CaseAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gives the signed-in applicant the payment link for their own case, so the SPA's "Open payment
 * page" button works without the email. JWT-authenticated (rule 5, {@code /api/cases/**}); only the
 * user who started the case gets a link, everyone else the same 404 as for an unknown case.
 */
@RestController
@RequestMapping("/api/cases")
public class PaymentLinkController {

  private final CaseAccessService caseAccess;
  private final EngineClient engine;
  private final CapabilityLinkVerifier links;

  private final FeeSchedule fees;

  public PaymentLinkController(
      CaseAccessService caseAccess,
      EngineClient engine,
      CapabilityLinkVerifier links,
      FeeSchedule fees) {
    this.caseAccess = caseAccess;
    this.engine = engine;
    this.links = links;
    this.fees = fees;
  }

  @GetMapping("/{processInstanceId}/payment-link")
  public ResponseEntity<?> paymentLink(@PathVariable String processInstanceId) {
    if (!caseAccess.isCaseStarter(processInstanceId)) {
      return notFound();
    }
    ProcessInstanceRef instance = engine.findActiveById(processInstanceId);
    if (instance == null || !fees.isPayable(instance.definitionKey())) {
      return notFound();
    }
    return ResponseEntity.ok(new PaymentLink("/pay/" + links.mintPayment(processInstanceId)));
  }

  private static ResponseEntity<?> notFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new PaymentController.ErrorResponse("not_found", "No payable case found."));
  }

  public record PaymentLink(String path) {}
}
