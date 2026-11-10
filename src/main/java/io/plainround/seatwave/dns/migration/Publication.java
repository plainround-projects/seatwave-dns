package io.plainround.seatwave.dns.migration;

import io.plainround.seatwave.dns.zone.Zone;
import java.time.Instant;
import java.util.Objects;

/**
 * One version of a zone in the audit log: the zone, when it started being served, and why.
 *
 * @param zone the zone served from that moment
 * @param from when the server started serving it
 * @param description the change that produced it
 */
public record Publication(Zone zone, Instant from, String description) {

  /** Checks the fields. */
  public Publication {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(description, "description");
  }
}
