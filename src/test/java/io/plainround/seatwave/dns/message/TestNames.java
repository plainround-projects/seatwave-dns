package io.plainround.seatwave.dns.message;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Test helpers that build names, addresses and records from text that is known to be valid. */
public final class TestNames {

  // The SOA timers of the lab zone: refresh 2 h, retry 15 min, expire 14 days.
  private static final long REFRESH_SECONDS = 7_200;
  private static final long RETRY_SECONDS = 900;
  private static final long EXPIRE_SECONDS = 1_209_600;

  private TestNames() {}

  /**
   * Parses a name that the test knows is valid.
   *
   * @param text the name
   * @return the name
   */
  public static DomainName name(String text) {
    return DomainName.parse(text).discardError().orElseThrow();
  }

  /**
   * Parses an address literal; never looks anything up.
   *
   * @param literal for example 192.0.2.10
   * @return the address
   */
  public static InetAddress address(String literal) {
    return InetAddress.ofLiteral(literal);
  }

  /**
   * Builds an A or AAAA record.
   *
   * @param owner the owner name
   * @param ttlSeconds the TTL
   * @param literal the address
   * @return the record
   */
  public static ResourceRecord addressRecord(String owner, long ttlSeconds, String literal) {
    InetAddress address = address(literal);
    RecordType type = address instanceof Inet4Address ? RecordType.A : RecordType.AAAA;
    return new ResourceRecord(name(owner), type, ttlSeconds, new RecordData.Address(address));
  }

  /**
   * Builds an NS or CNAME record.
   *
   * @param owner the owner name
   * @param type NS or CNAME
   * @param ttlSeconds the TTL
   * @param target the target name
   * @return the record
   */
  public static ResourceRecord hostRecord(
      String owner, RecordType type, long ttlSeconds, String target) {
    return new ResourceRecord(name(owner), type, ttlSeconds, new RecordData.Host(name(target)));
  }

  /**
   * Builds an SOA record whose negative-caching TTL is the given minimum.
   *
   * @param owner the zone apex
   * @param ttlSeconds the TTL of the SOA itself
   * @param minimumSeconds the minimum field
   * @return the record
   */
  public static ResourceRecord soa(String owner, long ttlSeconds, long minimumSeconds) {
    return new ResourceRecord(
        name(owner),
        RecordType.SOA,
        ttlSeconds,
        new RecordData.Authority(
            name("ns1." + owner),
            name("hostmaster." + owner),
            1,
            REFRESH_SECONDS,
            RETRY_SECONDS,
            EXPIRE_SECONDS,
            minimumSeconds));
  }
}
