package com.poc.backend.payment.mockprovider;

import com.poc.backend.payment.PaymentCallbackController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers the callback over HTTP to {@code app.payment.callback-url}, by default this backend's
 * own {@code /api/public/payments/callback} on localhost. Over the network on purpose: the callback
 * handler sees exactly the request an external provider would send.
 */
@Component
public class HttpCallbackSender implements CallbackSender {

  private static final Logger log = LoggerFactory.getLogger(HttpCallbackSender.class);

  private final RestClient rest = RestClient.create();
  private final String callbackUrl;

  public HttpCallbackSender(@Value("${app.payment.callback-url}") String callbackUrl) {
    this.callbackUrl = callbackUrl;
  }

  @Override
  public boolean send(byte[] body, String signature) {
    try {
      rest.post()
          .uri(callbackUrl)
          .contentType(MediaType.APPLICATION_JSON)
          .header(PaymentCallbackController.SIGNATURE_HEADER, signature)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientException e) {
      log.warn("Mock provider callback to {} failed: {}", callbackUrl, e.getMessage());
      return false;
    }
  }
}
