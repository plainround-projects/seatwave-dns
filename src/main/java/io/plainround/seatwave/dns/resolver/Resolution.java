package io.plainround.seatwave.dns.resolver;

import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/**
 * The result of one lookup through a caching resolver.
 *
 * @param source where the answer came from
 * @param code the response code; {@link ResponseCode#SERVER_FAILURE} when no server answered
 * @param records the answer records, empty for a negative answer or a failure
 */
public record Resolution(Source source, ResponseCode code, List<ResourceRecord> records) {

  /** Checks and copies the fields. */
  public Resolution {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(code, "code");
    records = List.copyOf(records);
  }

  /**
   * Returns the addresses in the answer, in answer order.
   *
   * @return the A and AAAA addresses; empty if there are none
   */
  public List<InetAddress> addresses() {
    return records.stream()
        .map(ResourceRecord::data)
        .filter(RecordData.Address.class::isInstance)
        .map(data -> ((RecordData.Address) data).address())
        .toList();
  }

  /** Where an answer came from. */
  public enum Source {
    /** The resolver asked the server, because it had no valid copy. */
    SERVER,
    /** The resolver answered from its cache, inside the hold time. */
    CACHE,
    /** The server could not be reached, and the resolver served an expired copy (RFC 8767). */
    STALE,
    /** The server could not be reached, and the resolver had nothing to serve. */
    FAILED
  }
}
