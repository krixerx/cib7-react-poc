package com.poc.backend.payment.mockprovider;

import com.poc.backend.payment.mockprovider.MockPaymentProvider.ProviderPayment;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo bank's own API behind the SPA's {@code /mock-bank/:sessionId} page. In production the
 * payer is on the provider's site at this point and none of this exists in the backend.
 */
@RestController
@RequestMapping("/api/public/mock-provider/sessions")
public class MockProviderController {

  private final MockPaymentProvider provider;

  public MockProviderController(MockPaymentProvider provider) {
    this.provider = provider;
  }

  @GetMapping("/{sessionId}")
  public ResponseEntity<?> get(@PathVariable String sessionId) {
    return view(provider.find(sessionId));
  }

  @PostMapping("/{sessionId}/pay")
  public ResponseEntity<?> pay(@PathVariable String sessionId) {
    return settle(sessionId, "PAID");
  }

  @PostMapping("/{sessionId}/cancel")
  public ResponseEntity<?> cancel(@PathVariable String sessionId) {
    return settle(sessionId, "CANCELLED");
  }

  private ResponseEntity<?> settle(String sessionId, String outcome) {
    try {
      return view(provider.settle(sessionId, outcome));
    } catch (MockPaymentProvider.CallbackFailedException e) {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
          .body(
              new ErrorResponse(
                  "callback_failed", "The merchant did not accept the payment. Try again."));
    }
  }

  private static ResponseEntity<?> view(Optional<ProviderPayment> payment) {
    return payment
        .<ResponseEntity<?>>map(
            p ->
                ResponseEntity.ok(
                    new SessionView(
                        p.sessionId(),
                        p.merchantName(),
                        p.reference(),
                        p.amount(),
                        p.currency(),
                        p.status(),
                        p.returnPath())))
        .orElseGet(
            () ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("unknown_session", "Unknown payment session.")));
  }

  public record SessionView(
      String sessionId,
      String merchantName,
      String reference,
      BigDecimal amount,
      String currency,
      String status,
      String returnPath) {}

  public record ErrorResponse(String code, String message) {}
}
