package com.zentro.security.observability;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Restricts correlation/trace headers to configured internal origins and base paths. */
public final class InternalCorrelationInterceptor implements ClientHttpRequestInterceptor {
  private final List<URI> destinations;
  public InternalCorrelationInterceptor(List<String> destinations) {
    this.destinations = destinations.stream().map(URI::create).toList();
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    var headers = request.getHeaders();
    headers.remove("baggage");
    if (destinations.stream().anyMatch(base -> matches(base, request.getURI()))) {
      String id = MDC.get(CorrelationIdFilter.MDC_KEY);
      headers.remove(CorrelationIdFilter.HEADER);
      if (CorrelationIdFilter.isValid(id)) {
        headers.set(CorrelationIdFilter.HEADER, id);
      }
    } else {
      headers.remove(CorrelationIdFilter.HEADER);
      headers.remove("traceparent");
      headers.remove("tracestate");
    }
    return execution.execute(request, body);
  }

  private static boolean matches(URI base, URI target) {
    if (base.getHost() == null || target.getHost() == null || target.getUserInfo() != null
        || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))) return false;
    String path = base.normalize().getPath();
    if (path == null || path.isEmpty()) path = "/";
    String targetPath = target.normalize().getPath();
    return base.getScheme().equalsIgnoreCase(target.getScheme()) && base.getHost().equalsIgnoreCase(target.getHost())
        && port(base) == port(target)
        && (targetPath.equals(path) || targetPath.startsWith(path.endsWith("/") ? path : path + "/"));
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
