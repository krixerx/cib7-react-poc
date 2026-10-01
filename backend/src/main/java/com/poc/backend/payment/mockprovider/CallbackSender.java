package com.poc.backend.payment.mockprovider;

/**
 * How the mock provider delivers a signed callback to the merchant. The default is a real HTTP POST
 * ({@link HttpCallbackSender}); tests substitute one that hands the bytes to MockMvc.
 */
public interface CallbackSender {

  /** Posts {@code body} with the given signature; true when the merchant answered 2xx. */
  boolean send(byte[] body, String signature);
}
