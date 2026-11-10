package io.plainround.seatwave.dns.resolver;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How long a caching resolver keeps answers. An honest resolver keeps a positive answer for its
 * TTL; one with a minimum hold above the TTL is a client that "pins" old answers (failure mode 4).
 *
 * @param minimumHold the shortest time a positive answer is kept, even if its TTL is shorter
 * @param maximumHold the longest time a positive answer is kept, even if its TTL is longer
 * @param maximumNegativeHold the longest time a "no such name" or "no data" answer is kept
 * @param serveStaleFor how long after expiry an answer may still be served when the server cannot
 *     be reached (RFC 8767); zero turns it off
 */
public record CachePolicy(
    Duration minimumHold,
    Duration maximumHold,
    Duration maximumNegativeHold,
    Duration serveStaleFor) {

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if a duration is negative or the minimum exceeds the maximum
   */
  public CachePolicy {
    for (Duration duration :
        List.of(minimumHold, maximumHold, maximumNegativeHold, serveStaleFor)) {
      Objects.requireNonNull(duration, "duration");
      if (duration.isNegative()) {
        throw new IllegalArgumentException("a cache duration must not be negative");
      }
    }
    if (minimumHold.compareTo(maximumHold) > 0) {
      throw new IllegalArgumentException("the minimum hold must not exceed the maximum hold");
    }
  }

  /**
   * Returns how long a positive answer with this TTL is kept.
   *
   * @param ttl the answer's TTL
   * @return the TTL, raised to the minimum hold or lowered to the maximum hold
   */
  public Duration holdFor(Duration ttl) {
    if (ttl.compareTo(minimumHold) < 0) {
      return minimumHold;
    }
    return ttl.compareTo(maximumHold) > 0 ? maximumHold : ttl;
  }
}
