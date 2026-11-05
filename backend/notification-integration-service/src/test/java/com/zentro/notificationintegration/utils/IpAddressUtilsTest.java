package com.zentro.notificationintegration.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IpAddressUtilsTest {

  @ParameterizedTest(name = "{0} is blocked")
  @ValueSource(strings = {
      "0.0.0.0", "0.1.2.3", "10.0.0.5", "10.255.255.255", "100.64.0.1", "100.127.255.254", "127.0.0.1",
      "127.10.20.30", "169.254.169.254", "169.254.0.1", "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.0.2.10",
      "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1",
      "239.255.255.250", "240.0.0.1", "255.255.255.255",
      "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "febf::1", "fec0::1", "ff02::1", "2001:db8::1",
      "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::10.0.0.1", "64:ff9b::a00:1",
      "2002:a00:1::1", "100::1"
  })
  @DisplayName("Loopback, private, link-local, metadata, multicast, reserved and their IPv6 forms are not public")
  void nonPublicAddresses(String address) throws Exception {
    assertThat(IpAddressUtils.isPublicUnicast(InetAddress.getByName(address))).isFalse();
  }

  @ParameterizedTest(name = "{0} is public")
  @ValueSource(strings = {
      "8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
      "169.253.255.255", "192.167.255.255", "198.17.255.255", "223.255.255.254",
      "2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1"
  })
  @DisplayName("Globally routable unicast addresses are public, including right next to blocked ranges")
  void publicAddresses(String address) throws Exception {
    assertThat(IpAddressUtils.isPublicUnicast(InetAddress.getByName(address))).isTrue();
  }

  @Test
  @DisplayName("null is not public")
  void nullIsNotPublic() {
    assertThat(IpAddressUtils.isPublicUnicast(null)).isFalse();
  }
}
