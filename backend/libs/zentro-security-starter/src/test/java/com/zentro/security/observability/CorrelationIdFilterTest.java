package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "has space", "a\rb", "a\nb", "a\tb", "name@example.test", "a,b", "é", "<script>", "_prefix"})
  void replacesUnsafeValues(String value) throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, value);
    assertGenerated(request);
  }

  @Test void replacesMissingOversizedAndDuplicateValues() throws Exception {
    assertGenerated(new MockHttpServletRequest());
    var oversized = new MockHttpServletRequest();
    oversized.addHeader(CorrelationIdFilter.HEADER, "a".repeat(129));
    assertGenerated(oversized);
    var duplicate = new MockHttpServletRequest();
    duplicate.addHeader(CorrelationIdFilter.HEADER, "one");
    duplicate.addHeader(CorrelationIdFilter.HEADER, "two");
    assertGenerated(duplicate);
  }

  private void assertGenerated(MockHttpServletRequest request) throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      String id = ((HttpServletRequest) req).getHeader(CorrelationIdFilter.HEADER);
      assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
      assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(id);
    });
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    assertThatCode(() -> UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"request-123", "abc.DEF_123", "a"})
  void preservesNonUuidIdentifiersAndRestoresMdcAfterFailure(String id) {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER, id);
    MDC.put(CorrelationIdFilter.MDC_KEY, "outer");
    try {
      assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(id);
        throw new jakarta.servlet.ServletException("test failure");
      })).isInstanceOf(jakarta.servlet.ServletException.class);
      assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer");
    } finally { MDC.remove(CorrelationIdFilter.MDC_KEY); }
  }

  @Test void redispatchReusesIdAndCleansMdc() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    String id = response.getHeader(CorrelationIdFilter.HEADER);
    for (var dispatch : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
      request.setDispatcherType(dispatch);
      if (dispatch == DispatcherType.ERROR) request.setAttribute("jakarta.servlet.error.request_uri", "/error");
      filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(id));
      assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
  }

  @Test void concurrentRequestsDoNotShareMdc() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks = java.util.stream.IntStream.range(0, 40).<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "request-" + i);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
          assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("request-" + i);
          Thread.yield();
          assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("request-" + i);
        });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        return null;
      }).toList();
      for (var future : executor.invokeAll(tasks)) future.get();
    }
  }
}
