package com.zentro.notificationintegration.webhook.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.notificationintegration.webhook.exception.InvalidWebhookUrlException;
import com.zentro.notificationintegration.webhook.exception.UnresolvableWebhookHostException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookUrlValidatorTest {
  /** Fake DNS: names map to fixed addresses; IP literals resolve to themselves; anything else is unknown. */
  private static final Map<String, String> DNS = Map.of(
      "hooks.example.com", "93.184.216.34",
      "internal.example.com", "10.0.0.7",
      "metadata.example.com", "169.254.169.254",
      "localhost", "127.0.0.1",
      "rebind.example.com", "192.168.0.10");

  private static InetAddress[] resolve(String host) throws UnknownHostException {
    String address = DNS.get(host);
    if (address != null) {
      return new InetAddress[] {InetAddress.getByName(address)};
    }
    if (host.matches("[0-9.]+") || host.contains(":")) {
      return new InetAddress[] {InetAddress.getByName(host)};
    }
    throw new UnknownHostException(host);
  }

  private final WebhookUrlValidator strict = new WebhookUrlValidator(false, WebhookUrlValidatorTest::resolve);
  private final WebhookUrlValidator local = new WebhookUrlValidator(true, WebhookUrlValidatorTest::resolve);

  @Test
  @DisplayName("A public https URL is accepted, with port, path and query")
  void publicHttpsIsAccepted() {
    assertThat(strict.validate("https://hooks.example.com:8443/swiftdrop/events?source=zentro").toString())
        .isEqualTo("https://hooks.example.com:8443/swiftdrop/events?source=zentro");
    assertThat(strict.validate("  https://93.184.216.34/hook  ").getHost()).isEqualTo("93.184.216.34");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "http://hooks.example.com/hook|URL must use https.",
      "ftp://hooks.example.com/hook|URL must use https.",
      "https://user:pass@hooks.example.com/hook|URL must not contain a user name or password.",
      "https://token@hooks.example.com/hook|URL must not contain a user name or password.",
      "https://hooks.example.com/hook#part|URL must not contain a fragment.",
      "/relative/path|URL must be an absolute https URL with a host name.",
      "https:///no-host|URL must be an absolute https URL with a host name.",
      "https://hooks example.com/|URL is not valid.",
      "https://internal.example.com/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://metadata.example.com/latest|URL must not point to a private, loopback, link-local or reserved address.",
      "https://169.254.169.254/latest/meta-data|URL must not point to a private, loopback, link-local or reserved address.",
      "https://10.0.0.5/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://172.16.4.4/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://192.168.1.10/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://127.0.0.1/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://localhost/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://[::1]/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://[fd00::1]/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://[::ffff:10.0.0.1]/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://0.0.0.0/hook|URL must not point to a private, loopback, link-local or reserved address.",
      "https://224.0.0.1/hook|URL must not point to a private, loopback, link-local or reserved address."
  })
  @DisplayName("Broken rules are rejected with a message that names the rule")
  void rejected(String url, String message) {
    assertThatThrownBy(() -> strict.validate(url))
        .isInstanceOf(InvalidWebhookUrlException.class)
        .hasMessage(message);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   "})
  @DisplayName("A blank URL is rejected")
  void blankIsRejected(String url) {
    assertThatThrownBy(() -> strict.validate(url)).hasMessage("URL is required.");
    assertThatThrownBy(() -> strict.validate(null)).hasMessage("URL is required.");
  }

  @Test
  @DisplayName("URLs longer than 2048 characters are rejected; exactly 2048 is allowed")
  void lengthLimit() {
    String base = "https://hooks.example.com/";
    String exact = base + "a".repeat(WebhookUrlValidator.MAX_LENGTH - base.length());
    assertThat(strict.validate(exact)).isNotNull();
    assertThatThrownBy(() -> strict.validate(exact + "a"))
        .hasMessage("URL must be at most 2048 characters.");
  }

  @Test
  @DisplayName("A host that cannot be resolved is reported separately (retried at send time)")
  void unresolvableHost() {
    assertThatThrownBy(() -> strict.validate("https://does-not-exist.example.org/hook"))
        .isInstanceOf(UnresolvableWebhookHostException.class)
        .hasMessage("URL host cannot be resolved.");
  }

  @Test
  @DisplayName("Development setting: http://localhost and loopback literals are allowed, nothing else private")
  void insecureLocalOnlyAllowsLoopback() {
    assertThat(local.validate("http://localhost:8089/hooks").getPort()).isEqualTo(8089);
    assertThat(local.validate("http://127.0.0.1:8089/hooks")).isNotNull();
    assertThat(local.validate("https://localhost/hooks")).isNotNull();
    assertThat(local.validate("https://hooks.example.com/hook")).isNotNull();

    assertThatThrownBy(() -> local.validate("http://hooks.example.com/hook")).hasMessage("URL must use https.");
    assertThatThrownBy(() -> local.validate("http://10.0.0.5/hook")).hasMessage("URL must use https.");
    assertThatThrownBy(() -> local.validate("https://10.0.0.5/hook"))
        .hasMessageContaining("private, loopback");
    assertThatThrownBy(() -> local.validate("https://169.254.169.254/latest"))
        .hasMessageContaining("private, loopback");
  }

  @Test
  @DisplayName("Without the development setting, http://localhost is rejected")
  void strictRejectsLocalhost() {
    assertThatThrownBy(() -> strict.validate("http://localhost:8089/hooks")).hasMessage("URL must use https.");
  }

  @Test
  @DisplayName("A name that resolves to several addresses is rejected when any of them is private")
  void anyBlockedAddressRejects() {
    WebhookUrlValidator mixed = new WebhookUrlValidator(false, host -> new InetAddress[] {
        InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.1.2.3")});
    assertThatThrownBy(() -> mixed.validate("https://mixed.example.com/hook"))
        .hasMessageContaining("private, loopback");
  }
}
