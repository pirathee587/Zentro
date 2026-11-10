package com.zentro.security.observability;

import io.micrometer.context.ContextSnapshot;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Request-local correlation only; Boot owns HTTP tracing and span lifecycle. */
public final class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

  static boolean isValid(String id) {
    return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  }

  @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
  @Override protected boolean shouldNotFilterErrorDispatch() { return false; }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = (String) request.getAttribute(ATTRIBUTE);
    if (id == null) {
      List<String> values = Collections.list(request.getHeaders(HEADER));
      String incoming = values.size() == 1 ? values.getFirst() : null;
      id = isValid(incoming) ? incoming : UUID.randomUUID().toString();
      request.setAttribute(ATTRIBUTE, id);
      WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(ATTRIBUTE, new CallableMdc(id));
    }
    String accepted = id;
    response.setHeader(HEADER, accepted);
    HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
      @Override public String getHeader(String name) {
        return HEADER.equalsIgnoreCase(name) ? accepted : super.getHeader(name);
      }
      @Override public Enumeration<String> getHeaders(String name) {
        return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(List.of(accepted)) : super.getHeaders(name);
      }
      @Override public Enumeration<String> getHeaderNames() {
        var names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
        if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) names.add(HEADER);
        return Collections.enumeration(names);
      }
    };
    String previous = MDC.get(MDC_KEY);
    try {
      MDC.put(MDC_KEY, accepted);
      chain.doFilter(wrapped, response);
    } finally {
      restore(previous);
    }
  }

  @Override
  protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    doFilterInternal(request, response, chain);
  }

  private static void restore(String previous) {
    if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
  }

  private static final class CallableMdc implements CallableProcessingInterceptor {
    private final String id;
    private final ThreadLocal<String> previous = new ThreadLocal<>();
    private final ThreadLocal<ContextSnapshot.Scope> traceScope = new ThreadLocal<>();
    private ContextSnapshot snapshot;
    private CallableMdc(String id) { this.id = id; }
    @Override public <T> void beforeConcurrentHandling(NativeWebRequest request, java.util.concurrent.Callable<T> task) {
      // Capture after Boot's server observation has started, not when the early filter is entered.
      snapshot = ContextSnapshot.captureAll();
    }
    @Override public <T> void preProcess(NativeWebRequest request, java.util.concurrent.Callable<T> task) {
      if (snapshot != null) traceScope.set(snapshot.setThreadLocals());
      previous.set(MDC.get(MDC_KEY));
      MDC.put(MDC_KEY, id);
    }
    @Override public <T> void postProcess(NativeWebRequest request, java.util.concurrent.Callable<T> task, Object result) {
      try {
        restore(previous.get());
      } finally {
        previous.remove();
        ContextSnapshot.Scope scope = traceScope.get();
        traceScope.remove();
        if (scope != null) scope.close();
      }
    }
  }
}
