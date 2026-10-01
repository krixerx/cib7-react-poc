package com.poc.backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.payment.FeeSchedule.Charge;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the payment amount coercion and the fee-tier logic — no Spring, no
 * HTTP. {@link FeeSchedule#parseAmount} is package-private for this test.
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
    assertThat(FeeSchedule.parseAmount(42)).isEqualTo(42.0);
    assertThat(FeeSchedule.parseAmount(38000.5)).isEqualTo(38000.5);
  }

  @Test
  void plainDecimalStringParses() {
    assertThat(FeeSchedule.parseAmount("12.34")).isEqualTo(12.34);
  }

  @Test
  void commaThousandsAreStripped() {
    assertThat(FeeSchedule.parseAmount("38,000")).isEqualTo(38000.0);
    assertThat(FeeSchedule.parseAmount("1,234,567")).isEqualTo(1234567.0);
  }

  @Test
  void regularSpacesAreStripped() {
    assertThat(FeeSchedule.parseAmount(" 1 000 ")).isEqualTo(1000.0);
  }

  @Test
  void currencySymbolsAreStripped() {
    assertThat(FeeSchedule.parseAmount("€150")).isEqualTo(150.0);
    assertThat(FeeSchedule.parseAmount("$25.50")).isEqualTo(25.5);
  }

  @Test
  void europeanDecimalCommaIsDestroyedNotConverted() {
    // Characterization, not endorsement: "," is removed outright, so the
    // fractional part fuses into the integer part.
    assertThat(FeeSchedule.parseAmount("1 234,56")).isEqualTo(123456.0);
  }

  @Test
  void nonBreakingSpaceIsNotStrippedAndFallsBackToZero() {
    // The replace chain handles the ASCII space twice but never U+00A0,
    // so an NBSP-grouped amount fails Double.parseDouble entirely.
    assertThat(FeeSchedule.parseAmount("1\u00A0234")).isEqualTo(0.0);
  }

  @Test
  void unparseableInputsFallBackToZero() {
    assertThat(FeeSchedule.parseAmount("abc")).isEqualTo(0.0);
    assertThat(FeeSchedule.parseAmount("")).isEqualTo(0.0);
    assertThat(FeeSchedule.parseAmount(null)).isEqualTo(0.0);
    assertThat(FeeSchedule.parseAmount(Boolean.TRUE)).isEqualTo(0.0);
  }

  // --- fee tiers (FeeSchedule with a mocked EngineClient) ---------------

  private final EngineClient engine = mock(EngineClient.class);
  private final FeeSchedule fees = new FeeSchedule(engine);

  private BigDecimal vehicleFeeForPrice(Object rawPrice) {
    when(engine.getRawVariable(PI, "price")).thenReturn(rawPrice);
    return fees.chargeFor(new ProcessInstanceRef(PI, "vehicleRegistration")).orElseThrow().amount();
  }

  @Test
  void vehicleFeeIs25Below5000() {
    assertThat(vehicleFeeForPrice(4999)).isEqualByComparingTo("25");
  }

  @Test
  void vehicleFeeIs75From5000To19999() {
    assertThat(vehicleFeeForPrice(5000)).isEqualByComparingTo("75");
    assertThat(vehicleFeeForPrice(19999)).isEqualByComparingTo("75");
  }

  @Test
  void vehicleFeeIs150From20000() {
    assertThat(vehicleFeeForPrice(20000)).isEqualByComparingTo("150");
  }

  @Test
  void missingPriceLandsInTheLowestTier() {
    assertThat(vehicleFeeForPrice(null)).isEqualByComparingTo("25");
  }

  @Test
  void localeFormattedStringPriceStillTiersCorrectly() {
    assertThat(vehicleFeeForPrice("38,000")).isEqualByComparingTo("150");
  }

  @Test
  void businessRegistrationIsFlat265() {
    Charge charge =
        fees.chargeFor(new ProcessInstanceRef(PI, "businessRegistration")).orElseThrow();

    assertThat(charge.amount()).isEqualByComparingTo("265");
    assertThat(charge.currency()).isEqualTo("EUR");
    assertThat(charge.recipient()).isEqualTo("Äriregister (Justiitsministeerium)");
  }

  @Test
  void transportVehicleFeeComesFromTheDmnVariable() {
    when(engine.getRawVariable(PI, "registrationFee")).thenReturn(57.5);

    assertThat(
            fees.chargeFor(new ProcessInstanceRef(PI, "transportVehicleRegistration"))
                .orElseThrow()
                .amount())
        .isEqualByComparingTo("57.50");
  }

  @Test
  void learningPermitIsFlat6() {
    assertThat(
            fees.chargeFor(new ProcessInstanceRef(PI, "transportLearningPermit"))
                .orElseThrow()
                .amount())
        .isEqualByComparingTo("6");
  }

  @Test
  void unknownDefinitionKeyHasNoCharge() {
    assertThat(fees.chargeFor(new ProcessInstanceRef(PI, "someOtherProcess"))).isEmpty();
  }
}
