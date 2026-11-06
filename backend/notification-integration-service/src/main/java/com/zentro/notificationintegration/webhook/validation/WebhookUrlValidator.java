package com.zentro.notificationintegration.webhook.validation;

import com.zentro.notificationintegration.utils.IpAddressUtils;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.exception.InvalidWebhookUrlException;
import com.zentro.notificationintegration.webhook.exception.UnresolvableWebhookHostException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rules for merchant webhook endpoints, checked at registration AND before every send (an address can change
 * after registration):
 * <ul>
 *   <li>at most {@value #MAX_LENGTH} characters, absolute, scheme https;</li>
 *   <li>no user name or password, no fragment;</li>
 *   <li>the host must resolve, and every address it resolves to must be public unicast: loopback, private,
 *       link-local (cloud metadata), multicast, unspecified, reserved and their IPv6 forms are blocked.</li>
 * </ul>
 * With {@code zentro.webhooks.allow-insecure-local=true} (development only) the host {@code localhost} or a
 * loopback literal is also accepted, over http or https, as long as it resolves only to loopback addresses.
 * Any other private address stays blocked.
 *
 * <p>Known limit: the HTTP client resolves the host again when it connects, so a host whose DNS answer changes
 * between this check and the connection (DNS rebinding) is not fully covered. Checking before every send keeps
 * that window small.
 */
@Component
public class WebhookUrlValidator {
  public static final int MAX_LENGTH = 2048;

  /** Resolves a host name to its addresses; replaceable in tests. */
  @FunctionalInterface
  public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final boolean allowInsecureLocal;
  private final HostResolver resolver;

  @Autowired
  public WebhookUrlValidator(WebhookProperties properties) {
    this(properties.allowInsecureLocal(), InetAddress::getAllByName);
  }

  public WebhookUrlValidator(boolean allowInsecureLocal, HostResolver resolver) {
    this.allowInsecureLocal = allowInsecureLocal;
    this.resolver = resolver;
  }

  /**
   * Checks the URL and returns it parsed.
   *
   * @throws UnresolvableWebhookHostException when the host cannot be resolved
   * @throws InvalidWebhookUrlException       for every other broken rule
   */
  public URI validate(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidWebhookUrlException("URL is required.");
    }
    String value = raw.trim();
    if (value.length() > MAX_LENGTH) {
      throw new InvalidWebhookUrlException("URL must be at most " + MAX_LENGTH + " characters.");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException ex) {
      throw new InvalidWebhookUrlException("URL is not valid.");
    }
    if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidWebhookUrlException("URL must be an absolute https URL with a host name.");
    }
    if (uri.getRawUserInfo() != null) {
      throw new InvalidWebhookUrlException("URL must not contain a user name or password.");
    }
    if (uri.getRawFragment() != null) {
      throw new InvalidWebhookUrlException("URL must not contain a fragment.");
    }

    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = stripBrackets(uri.getHost()).toLowerCase(Locale.ROOT);
    boolean local = allowInsecureLocal && isLocalName(host);
    if (!"https".equals(scheme) && !(local && "http".equals(scheme))) {
      throw new InvalidWebhookUrlException("URL must use https.");
    }

    InetAddress[] addresses;
    try {
      addresses = resolver.resolve(host);
    } catch (UnknownHostException | SecurityException ex) {
      throw new UnresolvableWebhookHostException("URL host cannot be resolved.");
    }
    if (addresses == null || addresses.length == 0) {
      throw new UnresolvableWebhookHostException("URL host cannot be resolved.");
    }
    for (InetAddress address : addresses) {
      boolean allowed = local ? address.isLoopbackAddress() : IpAddressUtils.isPublicUnicast(address);
      if (!allowed) {
        throw new InvalidWebhookUrlException(
            "URL must not point to a private, loopback, link-local or reserved address.");
      }
    }
    return uri;
  }

  private static boolean isLocalName(String host) {
    return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
  }

  private static String stripBrackets(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }
}
