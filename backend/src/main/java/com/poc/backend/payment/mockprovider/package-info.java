/**
 * A stand-in for an external payment provider (a bank or card acquirer), so the demo can pay a fee
 * end to end without one.
 *
 * <p>It keeps its own sessions, shows the payer a "Demo bank" page in the SPA, and reports the
 * outcome to the merchant only through a signed server-to-server callback to {@code POST
 * /api/public/payments/callback}, exactly as a real provider would. The merchant side ({@code
 * com.poc.backend.payment}) verifies that callback without knowing it came from this package.
 *
 * <p>In production this package is the only thing that is replaced: a client for the real provider
 * implements {@link com.poc.backend.payment.PaymentProvider}, and the provider's own servers sign
 * and send the callback. Nothing here is a security boundary; anyone may call these endpoints, and
 * the worst they can do is pay a fee for someone.
 */
package com.poc.backend.payment.mockprovider;
