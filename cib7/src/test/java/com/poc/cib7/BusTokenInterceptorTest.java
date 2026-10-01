package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.cibseven.connect.spi.ConnectorInvocation;
import org.cibseven.connect.spi.ConnectorRequest;
import org.junit.jupiter.api.Test;

/** The bus token goes to the bus and nowhere else, and cannot be overridden by the BPMN. */
class BusTokenInterceptorTest {

  private final BusTokenInterceptor interceptor =
      new BusTokenInterceptor("http://esb:8080", "secret");

  @Test
  void addsTokenToBusRequestsReplacingAnyExistingValue() throws Exception {
    BasicClassicHttpRequest request = new BasicClassicHttpRequest("POST", "http://esb:8080/render");
    request.setHeader(BusTokenInterceptor.HEADER, "forged");
    interceptor.handleInvocation(invocation(request));
    assertEquals("secret", request.getFirstHeader(BusTokenInterceptor.HEADER).getValue());
    assertEquals(1, request.getHeaders(BusTokenInterceptor.HEADER).length);
  }

  @Test
  void leavesOtherHostsAlone() throws Exception {
    for (String uri :
        new String[] {"http://elsewhere:8080/render", "http://esb:8080.attacker.example/x"}) {
      BasicClassicHttpRequest request = new BasicClassicHttpRequest("GET", uri);
      interceptor.handleInvocation(invocation(request));
      assertNull(request.getFirstHeader(BusTokenInterceptor.HEADER), uri);
    }
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
