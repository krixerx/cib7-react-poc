package com.poc.cib7;

import static com.poc.cib7.OutboundCredentialsInterceptor.API_KEY_HEADER;
import static com.poc.cib7.OutboundCredentialsInterceptor.INTERNAL_TOKEN_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.httpclient.HttpConnector;
import org.cibseven.connect.httpclient.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link OutboundCredentialsInterceptor} picks the credential on the first request only, so a 3xx
 * from the backend or Patchbay must neither be followed to another host nor pass as an answer.
 */
@SpringBootTest(
    properties = {
      "app.backend.internal-token=redirect-test-token",
      "app.patchbay.api-key=redirect-test-key"
    })
@ActiveProfiles("test")
class OutboundRedirectTest {

  private static final String TOKEN = "redirect-test-token";
  private static final String KEY = "redirect-test-key";

  /** Headers of every request the foreign host received, one map per request. */
  private static final List<Map<String, List<String>>> FOREIGN = new CopyOnWriteArrayList<>();

  private static final HttpServer foreign = start(false);
  private static final HttpServer redirecting = start(true);

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @DynamicPropertySource
  static void destinations(DynamicPropertyRegistry registry) {
    // Backend and Patchbay share the redirecting server on different paths.
    registry.add("BACKEND_URL", () -> base(redirecting));
    registry.add("PATCHBAY_URL", () -> base(redirecting));
  }

  @AfterAll
  static void stopServers() {
    foreign.stop(0);
    redirecting.stop(0);
  }

  @BeforeEach
  void clear() {
    FOREIGN.clear();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "GET  | /api/internal/registry/vehicles | 302",
        "GET  | /api/internal/registry/vehicles | 307",
        "POST | /api/internal/registry/vehicles | 302",
        "POST | /api/internal/registry/vehicles | 307",
        "GET  | /api/v1/integrations/mail-send  | 301",
        "GET  | /api/v1/integrations/mail-send  | 308",
        "POST | /api/v1/integrations/mail-send  | 303",
        "POST | /api/v1/integrations/mail-send  | 308"
      })
  void redirectFromCredentialedDestinationFailsWithoutReachingAnotherHost(
      String method, String path, int status) {
    HttpConnector http = Connectors.getConnector(Connectors.HTTP_CONNECTOR_ID);

    RuntimeException failure = null;
    Integer answered = null;
    try {
      var request = http.createRequest().url(base(redirecting) + path + "?status=" + status);
      if ("POST".equals(method)) {
        request.post().contentType("application/json").payload("{}");
      } else {
        request.get();
      }
      HttpResponse response = request.execute();
      answered = response.getStatusCode();
      response.close();
    } catch (RuntimeException e) {
      failure = e;
    }

    // Checked first so a regression reports the leak rather than the missing error.
    for (Map<String, List<String>> headers : FOREIGN) {
      assertFalse(headers.containsKey(INTERNAL_TOKEN_HEADER), "foreign host got " + headers);
      assertFalse(headers.containsKey(API_KEY_HEADER), "foreign host got " + headers);
      assertFalse(headers.toString().contains(TOKEN) || headers.toString().contains(KEY));
    }
    assertEquals(0, FOREIGN.size(), "the redirect was followed to the foreign host");
    assertNotNull(failure, method + " " + path + " answered " + answered + " without failing");
    assertTrue(causeChain(failure).contains("redirect"), causeChain(failure));
  }

  private static String base(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /**
   * The redirecting server answers {@code ?status=} with a {@code Location} on the foreign server;
   * the foreign server records the headers it was sent and answers 200.
   */
  private static HttpServer start(boolean redirects) {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
              body.readAllBytes();
            }
            if (redirects) {
              String query = exchange.getRequestURI().getQuery();
              int status = Integer.parseInt(query.substring("status=".length()));
              exchange
                  .getResponseHeaders()
                  .add("Location", base(foreign) + exchange.getRequestURI().getPath());
              exchange.sendResponseHeaders(status, -1);
            } else {
              Map<String, List<String>> headers =
                  new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
              headers.putAll(exchange.getRequestHeaders());
              FOREIGN.add(headers);
              byte[] answer = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, answer.length);
              exchange.getResponseBody().write(answer);
            }
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String causeChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
    }
    return messages.toString();
  }
}
