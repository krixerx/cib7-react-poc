package com.poc.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit test for the engine→backend shared-secret header filter. No Spring context. */
class InternalTokenAuthenticationFilterTest {

  private static final String SECRET = "s3cret-internal-token";

  private final InternalTokenAuthenticationFilter filter =
      new InternalTokenAuthenticationFilter(SECRET);

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("POST", "/api/documents/move-pending");
  }

  @Test
  void validTokenPassesTheChain() throws Exception {
    MockHttpServletRequest req = request();
    req.addHeader(InternalTokenAuthenticationFilter.HEADER, SECRET);
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isSameAs(req);
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @Test
  void missingHeaderIs401Json() throws Exception {
    MockHttpServletRequest req = request();
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentType()).isEqualTo("application/json");
    assertThat(res.getContentAsString())
        .isEqualTo(
            "{\"code\":\"invalid_internal_token\",\"message\":\"X-Internal-Token missing or invalid.\"}");
  }

  @Test
  void wrongTokenIs401() throws Exception {
    MockHttpServletRequest req = request();
    req.addHeader(InternalTokenAuthenticationFilter.HEADER, "not-the-secret");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentAsString()).contains("invalid_internal_token");
  }

  @Test
  void tokenOfDifferentLengthIs401() throws Exception {
    // MessageDigest.isEqual compares across lengths without throwing.
    MockHttpServletRequest req = request();
    req.addHeader(InternalTokenAuthenticationFilter.HEADER, SECRET + "-and-more");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void emptyHeaderValueIs401() throws Exception {
    MockHttpServletRequest req = request();
    req.addHeader(InternalTokenAuthenticationFilter.HEADER, "");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(401);
  }
}
