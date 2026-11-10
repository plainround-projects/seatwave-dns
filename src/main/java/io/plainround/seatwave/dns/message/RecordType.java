package io.plainround.seatwave.dns.message;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The record types this lab serves, with their type codes from RFC 1035 section 3.2.2 (A, NS,
 * CNAME, SOA, TXT) and RFC 3596 (AAAA). A query for any other type is answered, but never with
 * data.
 */
public enum RecordType {
  /** An IPv4 address. */
  A(1),
  /** A name server for the zone that starts at the owner name. */
  NS(2),
  /** An alias: the owner name is another name for the target. */
  CNAME(5),
  /** The start of a zone's authority, with the timers that include the negative-caching TTL. */
  SOA(6),
  /** Free text, used for ownership proofs and notes. */
  TXT(16),
  /** An IPv6 address. */
  AAAA(28);

  private final int code;

  RecordType(int code) {
    this.code = code;
  }

  /**
   * Returns the type code on the wire.
   *
   * @return for example 1 for A
   */
  public int code() {
    return code;
  }

  /**
   * Finds a type by its wire code.
   *
   * @param code the code
   * @return the type, or empty if this lab does not serve it
   */
  public static Optional<RecordType> fromCode(int code) {
    return Arrays.stream(values()).filter(type -> type.code == code).findFirst();
  }

  /**
   * Finds a type by its name in a zone file, in any case.
   *
   * @param name for example {@code aaaa}
   * @return the type, or empty if this lab does not serve it
   */
  public static Optional<RecordType> fromName(String name) {
    return Arrays.stream(values())
        .filter(type -> type.name().equals(name.toUpperCase(Locale.ROOT)))
        .findFirst();
  }
}
