package io.plainround.seatwave.dns.experiment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock that moves only when told to, so a simulation of hours runs in a second and every run
 * gives the same numbers. Not thread-safe: one simulation owns it.
 */
public final class SimulatedClock extends Clock {

  private Instant now;

  /**
   * Starts the clock.
   *
   * @param start the first instant
   */
  public SimulatedClock(Instant start) {
    this.now = Objects.requireNonNull(start, "start");
  }

  /**
   * Moves the clock forward.
   *
   * @param later the new instant, not before the current one
   */
  public void advanceTo(Instant later) {
    Objects.requireNonNull(later, "later");
    if (later.isBefore(now)) {
      throw new IllegalArgumentException("a simulated clock never goes back");
    }
    now = later;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the simulation works in UTC only");
  }
}
