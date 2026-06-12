package com.poc.backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.payment.PaymentController.ErrorResponse;
import com.poc.backend.payment.PaymentController.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Characterization tests for the payment amount coercion and the fee-tier logic — no Spring, no
 * HTTP. {@link PaymentController#parseAmount} was relaxed to package-private for this test.
 *
 * <p>Quirks pinned (candidates for the Phase-4 FeeScheduleProperties refactor):
 *
 * <ul>
 *   <li>Commas are stripped unconditionally, so a European decimal comma is destroyed: "1 234,56"
 *       parses as 123456, not 1234.56.
 *   <li>NBSP (U+00A0) is NOT stripped — the second space-replace in the source is a duplicate ASCII
 *       space, so "1\u00A0234" fails to parse and falls back to 0.0.
 * </ul>
 */
class PaymentLogicTest {

  private static final String PI = "pi-pay-1";

  // --- parseAmount ------------------------------------------------------

  @Test
  void numbersPassThrough() {
    assertThat(PaymentController.parseAmount(42)).isEqualTo(42.0);
    assertThat(PaymentController.parseAmount(38000.5)).isEqualTo(38000.5);
  }

  @Test
  void plainDecimalStringParses() {
    assertThat(PaymentController.parseAmount("12.34")).isEqualTo(12.34);
  }

  @Test
  void commaThousandsAreStripped() {
    assertThat(PaymentController.parseAmount("38,000")).isEqualTo(38000.0);
    assertThat(PaymentController.parseAmount("1,234,567")).isEqualTo(1234567.0);
  }

  @Test
  void regularSpacesAreStripped() {
    assertThat(PaymentController.parseAmount(" 1 000 ")).isEqualTo(1000.0);
  }

  @Test
  void currencySymbolsAreStripped() {
    assertThat(PaymentController.parseAmount("€150")).isEqualTo(150.0);
    assertThat(PaymentController.parseAmount("$25.50")).isEqualTo(25.5);
  }

  @Test
  void europeanDecimalCommaIsDestroyedNotConverted() {
    // Characterization, not endorsement: "," is removed outright, so the
    // fractional part fuses into the integer part.
    assertThat(PaymentController.parseAmount("1 234,56")).isEqualTo(123456.0);
  }

  @Test
  void nonBreakingSpaceIsNotStrippedAndFallsBackToZero() {
    // The replace chain handles the ASCII space twice but never U+00A0,
    // so an NBSP-grouped amount fails Double.parseDouble entirely.
    assertThat(PaymentController.parseAmount("1\u00A0234")).isEqualTo(0.0);
  }

  @Test
  void unparseableInputsFallBackToZero() {
    assertThat(PaymentController.parseAmount("abc")).isEqualTo(0.0);
    assertThat(PaymentController.parseAmount("")).isEqualTo(0.0);
    assertThat(PaymentController.parseAmount(null)).isEqualTo(0.0);
    assertThat(PaymentController.parseAmount(Boolean.TRUE)).isEqualTo(0.0);
  }

  // --- fee tiers (via getStatus with a mocked EngineClient) --------------

  private final EngineClient engine = mock(EngineClient.class);
  private final PaymentController controller = new PaymentController(engine);

  private PaymentStatus vehicleStatusForPrice(Object rawPrice) {
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
    when(engine.getRawVariable(PI, "price")).thenReturn(rawPrice);
    ResponseEntity<?> response = controller.getStatus(PI);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return (PaymentStatus) response.getBody();
  }

  @Test
  void vehicleFeeIs25Below5000() {
    assertThat(vehicleStatusForPrice(4999).amount()).isEqualTo(25.0);
  }

  @Test
  void vehicleFeeIs75From5000To19999() {
    assertThat(vehicleStatusForPrice(5000).amount()).isEqualTo(75.0);
    assertThat(vehicleStatusForPrice(19999).amount()).isEqualTo(75.0);
  }

  @Test
  void vehicleFeeIs150From20000() {
    assertThat(vehicleStatusForPrice(20000).amount()).isEqualTo(150.0);
  }

  @Test
  void missingPriceLandsInTheLowestTier() {
    // null raw price → parseAmount 0.0 → cheapest tier.
    assertThat(vehicleStatusForPrice(null).amount()).isEqualTo(25.0);
  }

  @Test
  void localeFormattedStringPriceStillTiersCorrectly() {
    assertThat(vehicleStatusForPrice("38,000").amount()).isEqualTo(150.0);
  }

  @Test
  void businessRegistrationIsFlat265() {
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "businessRegistration"));
    when(engine.getStringVariable(PI, "applicantFirstName")).thenReturn("Frida");
    when(engine.getStringVariable(PI, "applicantLastName")).thenReturn("Asutaja");
    when(engine.getStringVariable(PI, "companyName")).thenReturn("Näidis OÜ");

    PaymentStatus status = (PaymentStatus) controller.getStatus(PI).getBody();

    assertThat(status.amount()).isEqualTo(265.0);
    assertThat(status.item()).isEqualTo("Näidis OÜ");
    assertThat(status.payerName()).isEqualTo("Frida Asutaja");
    assertThat(status.currency()).isEqualTo("EUR");
    assertThat(status.reference()).isEqualTo(PI);
    assertThat(status.status()).isEqualTo("pending");
  }

  @Test
  void alreadyPaidCaseReportsPaid() {
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
    when(engine.getBooleanVariable(PI, "paymentReceived")).thenReturn(true);

    PaymentStatus status = (PaymentStatus) controller.getStatus(PI).getBody();

    assertThat(status.status()).isEqualTo("paid");
  }

  @Test
  void unknownProcessInstanceIs404() {
    when(engine.findActiveById(PI)).thenReturn(null);

    ResponseEntity<?> response = controller.getStatus(PI);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(((ErrorResponse) response.getBody()).code()).isEqualTo("unknown_case");
  }

  @Test
  void unknownDefinitionKeyIsAlso404() {
    // resolve() only knows the two registration processes; anything else
    // falls through to null → unknown_case.
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "someOtherProcess"));

    ResponseEntity<?> response = controller.getStatus(PI);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }
}
