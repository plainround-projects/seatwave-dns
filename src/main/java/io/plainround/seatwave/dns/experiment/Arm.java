package io.plainround.seatwave.dns.experiment;

import java.util.Objects;

/**
 * One arm of the TTL-change experiment: the TTL the record starts with, the TTL it is lowered to
 * one old TTL before the switch (the same value means "not lowered"), and the share of resolvers
 * that pin answers for longer than the TTL.
 *
 * @param name the arm's name in the settings and the table
 * @param initialTtlSeconds the TTL the record carries at the start
 * @param loweredTtlSeconds the TTL after lowering; equal to the initial TTL for no lowering
 * @param pinningPercent the percent of resolvers that keep every answer at least the pinning hold
 * @param pinningHoldSeconds how long a pinning resolver keeps an answer at least
 */
public record Arm(
    String name,
    long initialTtlSeconds,
    long loweredTtlSeconds,
    int pinningPercent,
    long pinningHoldSeconds) {

  private static final int ALL_PERCENT = 100;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the lowered TTL is above the initial one, or a value is out
   *     of range
   */
  public Arm {
    Objects.requireNonNull(name, "name");
    if (initialTtlSeconds < 1 || loweredTtlSeconds < 1 || loweredTtlSeconds > initialTtlSeconds) {
      throw new IllegalArgumentException("the lowered TTL must be from 1 to the initial TTL");
    }
    if (pinningPercent < 0 || pinningPercent > ALL_PERCENT || pinningHoldSeconds < 0) {
      throw new IllegalArgumentException(
          "the pinning share is a percent; the hold is not negative");
    }
  }

  /**
   * Says whether this arm lowers the TTL before the switch.
   *
   * @return true if the lowered TTL is below the initial one
   */
  public boolean isLowering() {
    return loweredTtlSeconds < initialTtlSeconds;
  }
}
