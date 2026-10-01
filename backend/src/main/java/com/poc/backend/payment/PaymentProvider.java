package com.poc.backend.payment;

import java.math.BigDecimal;

/**
 * The external payment provider as the merchant side sees it: register a payment, get back where to
 * send the payer's browser. The provider later reports the outcome through the signed callback
 * ({@link PaymentCallbackController}), never through the browser.
 *
 * <p>The demo implementation is {@link com.poc.backend.payment.mockprovider.MockPaymentProvider}. A
 * real deployment replaces that package with a client for a real provider; nothing in this package
 * changes.
 */
public interface PaymentProvider {

  /**
   * Registers a payment for {@code sessionId} and returns the URL to redirect the payer to.
   *
   * @param returnPath where the provider sends the payer back afterwards (the public pay page)
   */
  String startCheckout(
      String sessionId,
      String reference,
      BigDecimal amount,
      String currency,
      String merchantName,
      String returnPath);
}
