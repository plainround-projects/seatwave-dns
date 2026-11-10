package io.plainround.seatwave.dns.zone;

import io.plainround.seatwave.dns.message.ResourceRecord;
import java.util.List;
import java.util.Objects;

/** What a zone knows about one question. */
public sealed interface Lookup {

  /**
   * The zone holds the answer: the records, with any CNAME first.
   *
   * @param records the answer records
   */
  record Answer(List<ResourceRecord> records) implements Lookup {

    /** Copies the records. */
    public Answer {
      records = List.copyOf(records);
    }
  }

  /**
   * The name lies in a child zone that this zone delegates: here are the child's name servers and,
   * as glue, their addresses.
   *
   * @param nameServers the NS records of the child zone
   * @param glue the A and AAAA records of those name servers, when this zone has them
   */
  record Referral(List<ResourceRecord> nameServers, List<ResourceRecord> glue) implements Lookup {

    /** Copies the records. */
    public Referral {
      nameServers = List.copyOf(nameServers);
      glue = List.copyOf(glue);
    }
  }

  /**
   * The name exists, but has no record of the type asked for (dig shows NOERROR and no answer).
   *
   * @param soa the zone's SOA, whose minimum field sets how long the "no" may be cached
   */
  record NoData(ResourceRecord soa) implements Lookup {

    /** Checks the field. */
    public NoData {
      Objects.requireNonNull(soa, "soa");
    }
  }

  /**
   * The name does not exist in the zone (dig shows NXDOMAIN).
   *
   * @param soa the zone's SOA, for negative caching (RFC 2308)
   */
  record NameError(ResourceRecord soa) implements Lookup {

    /** Checks the field. */
    public NameError {
      Objects.requireNonNull(soa, "soa");
    }
  }

  /** The name is not in this zone at all, so the server refuses to answer. */
  record OutOfZone() implements Lookup {}
}
