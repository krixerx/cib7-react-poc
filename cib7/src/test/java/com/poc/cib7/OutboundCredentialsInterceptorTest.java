package com.poc.cib7;

import static com.poc.cib7.OutboundCredentialsInterceptor.API_KEY_HEADER;
import static com.poc.cib7.OutboundCredentialsInterceptor.INTERNAL_TOKEN_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.cibseven.connect.spi.ConnectorInvocation;
import org.cibseven.connect.spi.ConnectorRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Each credential goes to its own destination and nowhere else, and a header the BPMN set cannot
 * override or carry it (docs/security.md rule 9, docs/cib7.md).
 */
class OutboundCredentialsInterceptorTest {

  private static final String KEY = "patchbay-key";
  private static final String TOKEN = "internal-token";

  private final OutboundCredentialsInterceptor interceptor =
      new OutboundCredentialsInterceptor("http://patchbay:8080", "http://backend:8085", KEY, TOKEN);

  @Test
  void keyOnlyOnPatchbayIntegrations() throws Exception {
    BasicClassicHttpRequest request =
        send("POST", "http://patchbay:8080/api/v1/integrations/mail-send");
    assertEquals(KEY, request.getFirstHeader(API_KEY_HEADER).getValue());
    assertNull(request.getFirstHeader(INTERNAL_TOKEN_HEADER));
  }

  @Test
  void tokenOnlyOnBackendInternal() throws Exception {
    BasicClassicHttpRequest request =
        send("POST", "http://backend:8085/api/internal/documents/move-pending");
    assertEquals(TOKEN, request.getFirstHeader(INTERNAL_TOKEN_HEADER).getValue());
    assertNull(request.getFirstHeader(API_KEY_HEADER));
  }

  @Test
  void hostAndSchemeCompareCaseInsensitivelyAndDefaultPortCounts() throws Exception {
    OutboundCredentialsInterceptor onDefaultPort =
        new OutboundCredentialsInterceptor("http://patchbay", "http://backend:8085", KEY, TOKEN);
    BasicClassicHttpRequest request =
        new BasicClassicHttpRequest("POST", "HTTP://PatchBay:80/api/v1/integrations/pdf-render");
    onDefaultPort.handleInvocation(invocation(request));
    assertEquals(KEY, request.getFirstHeader(API_KEY_HEADER).getValue());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Backend outside /api/internal/.
        "http://backend:8085/api/public/registry/vehicles/X",
        "http://backend:8085/api/internalx/y",
        "http://backend:8085/api/documents/x",
        // Dot segments that leave /api/internal/.
        "http://backend:8085/api/internal/../public/consent/x",
        "http://backend:8085/api/internal/./../../api/public/x",
        // Patchbay outside its integration API, or the backend's path on Patchbay.
        "http://patchbay:8080/admin/integrations",
        "http://patchbay:8080/api/v1/integrations/../../../admin/api/x",
        "http://patchbay:8080/api/internal/documents/move-pending",
        // The integration path on the backend.
        "http://backend:8085/api/v1/integrations/mail-send",
        // Foreign hosts, other ports and schemes.
        "http://evil.example/api/internal/x",
        "http://evil.example/api/v1/integrations/mail-send",
        "http://backend:9999/api/internal/x",
        "https://backend:8085/api/internal/x",
        "http://patchbay:8081/api/v1/integrations/mail-send",
        // Host-suffix and userinfo tricks.
        "http://backend:8085.evil/api/internal/x",
        "http://backend.evil:8085/api/internal/x",
        "http://backend:8085@evil/api/internal/x",
        "http://patchbay:8080@evil/api/v1/integrations/mail-send"
      })
  void neitherCredentialElsewhere(String uri) throws Exception {
    BasicClassicHttpRequest request = send("POST", uri);
    assertNull(request.getFirstHeader(API_KEY_HEADER), uri);
    assertNull(request.getFirstHeader(INTERNAL_TOKEN_HEADER), uri);
  }

  @Test
  void bpmnSetHeadersAreReplacedOnTheirDestination() throws Exception {
    BasicClassicHttpRequest request =
        new BasicClassicHttpRequest("POST", "http://backend:8085/api/internal/x");
    request.addHeader(INTERNAL_TOKEN_HEADER, "forged");
    request.addHeader("x-internal-token", "forged-too");
    request.addHeader(API_KEY_HEADER, "forged");
    interceptor.handleInvocation(invocation(request));
    assertEquals(1, request.getHeaders(INTERNAL_TOKEN_HEADER).length);
    assertEquals(TOKEN, request.getFirstHeader(INTERNAL_TOKEN_HEADER).getValue());
    assertNull(request.getFirstHeader(API_KEY_HEADER));
  }

  @Test
  void bpmnSetHeadersAreRemovedEverywhereElse() throws Exception {
    for (String uri :
        new String[] {
          "http://patchbay:8080/api/v1/integrations/pack-x",
          "http://backend:8085/api/public/x",
          "http://evil.example/"
        }) {
      BasicClassicHttpRequest request = new BasicClassicHttpRequest("POST", uri);
      request.addHeader(INTERNAL_TOKEN_HEADER, "forged");
      request.addHeader("X-Api-Key", "forged");
      interceptor.handleInvocation(invocation(request));
      assertNull(request.getFirstHeader(INTERNAL_TOKEN_HEADER), uri);
      if (uri.startsWith("http://patchbay")) {
        assertEquals(1, request.getHeaders(API_KEY_HEADER).length, uri);
        assertEquals(KEY, request.getFirstHeader(API_KEY_HEADER).getValue(), uri);
      } else {
        assertNull(request.getFirstHeader(API_KEY_HEADER), uri);
      }
    }
  }

  private BasicClassicHttpRequest send(String method, String uri) throws Exception {
    BasicClassicHttpRequest request = new BasicClassicHttpRequest(method, uri);
    interceptor.handleInvocation(invocation(request));
    return request;
  }

  private static ConnectorInvocation invocation(BasicClassicHttpRequest target) {
    return new ConnectorInvocation() {
      @Override
      public Object getTarget() {
        return target;
      }

      @Override
      public ConnectorRequest<?> getRequest() {
        return null;
      }

      @Override
      public Object proceed() {
        return null;
      }
    };
  }
}
