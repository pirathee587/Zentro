package com.zentro.notificationintegration.utils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Generic IP address checks. Stateless; no network access (only already-resolved addresses are inspected).
 */
public final class IpAddressUtils {

  /** IPv4 ranges that are never reachable public unicast addresses: {network, prefix length}. */
  private static final int[][] NON_PUBLIC_V4 = {
      {ip(0, 0, 0, 0), 8},         // "this network", including 0.0.0.0
      {ip(10, 0, 0, 0), 8},        // private
      {ip(100, 64, 0, 0), 10},     // carrier-grade NAT
      {ip(127, 0, 0, 0), 8},       // loopback
      {ip(169, 254, 0, 0), 16},    // link-local, including cloud metadata 169.254.169.254
      {ip(172, 16, 0, 0), 12},     // private
      {ip(192, 0, 0, 0), 24},      // IETF protocol assignments
      {ip(192, 0, 2, 0), 24},      // documentation
      {ip(192, 88, 99, 0), 24},    // 6to4 relay anycast (deprecated)
      {ip(192, 168, 0, 0), 16},    // private
      {ip(198, 18, 0, 0), 15},     // benchmarking
      {ip(198, 51, 100, 0), 24},   // documentation
      {ip(203, 0, 113, 0), 24},    // documentation
      {ip(224, 0, 0, 0), 4},       // multicast
      {ip(240, 0, 0, 0), 4},       // reserved, including 255.255.255.255
  };

  private IpAddressUtils() {
  }

  /**
   * True only for a globally routable unicast address. False for loopback, private, link-local (including cloud
   * metadata), carrier-grade NAT, multicast, unspecified, documentation and reserved ranges, IPv6 unique-local,
   * link-local, site-local, multicast and documentation ranges, and IPv6 forms that embed a non-public IPv4
   * address (IPv4-mapped, IPv4-compatible, NAT64, 6to4).
   */
  public static boolean isPublicUnicast(InetAddress address) {
    if (address == null) {
      return false;
    }
    if (address instanceof Inet4Address v4) {
      return isPublicV4(v4.getAddress());
    }
    if (address instanceof Inet6Address v6) {
      return isPublicV6(v6.getAddress());
    }
    return false;
  }

  private static boolean isPublicV4(byte[] b) {
    int value = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    for (int[] range : NON_PUBLIC_V4) {
      int mask = range[1] == 0 ? 0 : -1 << (32 - range[1]);
      if ((value & mask) == (range[0] & mask)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPublicV6(byte[] b) {
    boolean firstTenZero = true;
    for (int i = 0; i < 10; i++) {
      firstTenZero &= b[i] == 0;
    }
    // ::/96 (unspecified, loopback, IPv4-compatible) and ::ffff:0:0/96 (IPv4-mapped): judge the embedded IPv4.
    if (firstTenZero && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
      return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
    }
    // 64:ff9b::/96 NAT64: judge the embedded IPv4.
    if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b && allZero(b, 4, 12)) {
      return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
    }
    // 2002::/16 6to4: judge the embedded IPv4.
    if (b[0] == 0x20 && b[1] == 0x02) {
      return isPublicV4(new byte[] {b[2], b[3], b[4], b[5]});
    }
    int first = b[0] & 0xff;
    int second = b[1] & 0xff;
    if ((first & 0xfe) == 0xfc) {
      return false; // fc00::/7 unique-local
    }
    if (first == 0xfe && (second & 0xc0) == 0x80) {
      return false; // fe80::/10 link-local
    }
    if (first == 0xfe && (second & 0xc0) == 0xc0) {
      return false; // fec0::/10 site-local (deprecated)
    }
    if (first == 0xff) {
      return false; // ff00::/8 multicast
    }
    if (first == 0x20 && second == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
      return false; // 2001:db8::/32 documentation
    }
    if (first == 0x01 && allZero(b, 1, 8)) {
      return false; // 100::/64 discard-only
    }
    return (first & 0xe0) == 0x20; // only 2000::/3 is global unicast
  }

  private static boolean allZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static int ip(int a, int b, int c, int d) {
    return (a << 24) | (b << 16) | (c << 8) | d;
  }
}
