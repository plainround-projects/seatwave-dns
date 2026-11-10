package io.plainround.seatwave.dns.migration;

import io.plainround.seatwave.dns.zone.Zone;
import java.util.Objects;

/**
 * A zone change the {@link MigrationGuard} has approved. Its constructor is package-private, so no
 * code outside this package can make one, and {@link ZoneStore#apply} accepts nothing else: that is
 * what makes the guard impossible to bypass. Immutable.
 */
public final class ApprovedChange {

  private final Zone from;
  private final Zone to;
  private final String description;

  ApprovedChange(Zone from, Zone to, String description) {
    this.from = Objects.requireNonNull(from, "from");
    this.to = Objects.requireNonNull(to, "to");
    this.description = Objects.requireNonNull(description, "description");
  }

  /**
   * Returns the zone the change was approved against.
   *
   * @return the zone that must still be current when the change is applied
   */
  public Zone from() {
    return from;
  }

  /**
   * Returns the zone after the change.
   *
   * @return the new zone
   */
  public Zone to() {
    return to;
  }

  /**
   * Returns what the change does, for the audit log.
   *
   * @return for example "seatwave.example. A 192.0.2.10 -> 203.0.113.30"
   */
  public String description() {
    return description;
  }
}
