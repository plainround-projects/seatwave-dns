package io.plainround.seatwave.dns.message;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Objects;

/**
 * One resource record: an owner name, a type, a time to live and the data. The class is always IN
 * (the internet), the only class this lab serves.
 *
 * @param owner the name the record belongs to
 * @param type the record type
 * @param ttlSeconds how long a cache may keep the record, 0 to 2,147,483,647 seconds (RFC 2181
 *     section 8)
 * @param data the data, whose variant must match the type
 */
public record ResourceRecord(DomainName owner, RecordType type, long ttlSeconds, RecordData data) {

  /** The largest TTL a record may carry (RFC 2181 section 8): 2^31 - 1 seconds. */
  public static final long MAX_TTL_SECONDS = Integer.MAX_VALUE;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the TTL is out of range or the data does not fit the type
   */
  public ResourceRecord {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(data, "data");
    if (ttlSeconds < 0 || ttlSeconds > MAX_TTL_SECONDS) {
      throw new IllegalArgumentException("a TTL must be from 0 to 2147483647 seconds");
    }
    boolean isMatching =
        switch (type) {
          case A ->
              data instanceof RecordData.Address(var address) && address instanceof Inet4Address;
          case AAAA ->
              data instanceof RecordData.Address(var address) && address instanceof Inet6Address;
          case NS, CNAME -> data instanceof RecordData.Host;
          case SOA -> data instanceof RecordData.Authority;
          case TXT -> data instanceof RecordData.Text;
        };
    if (!isMatching) {
      throw new IllegalArgumentException("the data does not match the record type " + type);
    }
  }

  /**
   * Returns the same record with another TTL.
   *
   * @param newTtlSeconds the new TTL
   * @return the changed copy
   */
  public ResourceRecord withTtl(long newTtlSeconds) {
    return new ResourceRecord(owner, type, newTtlSeconds, data);
  }

  /**
   * Writes the record the way a zone file and dig write it.
   *
   * @return for example {@code seatwave.example. 3600 IN A 192.0.2.10}
   */
  @Override
  public String toString() {
    String text =
        switch (data) {
          case RecordData.Address(var address) -> address.getHostAddress();
          case RecordData.Host(var target) -> target.toString();
          case RecordData.Authority authority ->
              String.join(
                  " ",
                  authority.primaryServer().toString(),
                  authority.responsibleMailbox().toString(),
                  Long.toString(authority.serial()),
                  Long.toString(authority.refreshSeconds()),
                  Long.toString(authority.retrySeconds()),
                  Long.toString(authority.expireSeconds()),
                  Long.toString(authority.minimumSeconds()));
          case RecordData.Text(var strings) ->
              String.join(" ", strings.stream().map(value -> "\"" + value + "\"").toList());
        };
    return owner + " " + ttlSeconds + " IN " + type + " " + text;
  }
}
