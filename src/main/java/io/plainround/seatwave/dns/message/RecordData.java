package io.plainround.seatwave.dns.message;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** The data part of a record; one variant per family of record types this lab serves. */
public sealed interface RecordData
    permits RecordData.Address, RecordData.Host, RecordData.Authority, RecordData.Text {

  /**
   * The data of an A record (an IPv4 address) or an AAAA record (an IPv6 address).
   *
   * @param address the address
   */
  record Address(InetAddress address) implements RecordData {

    /** Checks the field. */
    public Address {
      Objects.requireNonNull(address, "address");
    }
  }

  /**
   * The data of an NS record (a name server) or a CNAME record (the name this one aliases).
   *
   * @param target the name pointed at
   */
  record Host(DomainName target) implements RecordData {

    /** Checks the field. */
    public Host {
      Objects.requireNonNull(target, "target");
    }
  }

  /**
   * The data of an SOA record (RFC 1035 section 3.3.13). The last field is the TTL of negative
   * answers (RFC 2308 section 4).
   *
   * @param primaryServer the zone's primary name server
   * @param responsibleMailbox the mailbox of the person responsible, written as a name
   * @param serial the version of the zone, raised on every change
   * @param refreshSeconds how often secondary servers check the serial
   * @param retrySeconds how soon a secondary retries a failed check
   * @param expireSeconds how long a secondary keeps answering without reaching the primary
   * @param minimumSeconds the TTL for negative answers
   */
  record Authority(
      DomainName primaryServer,
      DomainName responsibleMailbox,
      long serial,
      long refreshSeconds,
      long retrySeconds,
      long expireSeconds,
      long minimumSeconds)
      implements RecordData {

    // Every SOA number is an unsigned 32-bit value on the wire.
    private static final long MAX_UNSIGNED_32 = 0xFFFF_FFFFL;

    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException if a number does not fit in 32 unsigned bits
     */
    public Authority {
      Objects.requireNonNull(primaryServer, "primaryServer");
      Objects.requireNonNull(responsibleMailbox, "responsibleMailbox");
      for (long value :
          List.of(serial, refreshSeconds, retrySeconds, expireSeconds, minimumSeconds)) {
        if (value < 0 || value > MAX_UNSIGNED_32) {
          throw new IllegalArgumentException("an SOA number must fit in 32 unsigned bits");
        }
      }
    }
  }

  /**
   * The data of a TXT record: one or more strings of at most 255 octets each.
   *
   * @param strings the strings, in order
   */
  record Text(List<String> strings) implements RecordData {

    // Each string is sent after a one-octet length (RFC 1035 section 3.3.14).
    private static final int MAX_STRING_OCTETS = 255;

    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException if there is no string or one is too long
     */
    public Text {
      strings = List.copyOf(strings);
      if (strings.isEmpty()) {
        throw new IllegalArgumentException("a TXT record needs at least one string");
      }
      for (String text : strings) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_OCTETS) {
          throw new IllegalArgumentException("a TXT string is longer than 255 octets");
        }
      }
    }
  }
}
