package io.plainround.seatwave.dns.experiment;

import java.util.List;
import java.util.Objects;

/**
 * What one arm of the TTL-change experiment measured. A switch time of -1 means that share of
 * resolvers never saw the new address before the run ended.
 *
 * @param arm the arm
 * @param cacheHitRatio the share of resolver lookups answered from cache in the rate window
 * @param serverQueriesPerSecond the authoritative server's query rate in the rate window
 * @param switchSeconds the seconds after the switch at which 50, 95, 99 and 100 percent of
 *     resolvers first gave the new address
 * @param retireAfterSeconds the seconds after the switch from which the guard approves retiring the
 *     old address
 * @param onOldAtRetirePercent the percent of resolvers still giving the old address at that moment
 * @param guardLog every guard decision, in order
 */
public record ArmResult(
    Arm arm,
    double cacheHitRatio,
    double serverQueriesPerSecond,
    List<Long> switchSeconds,
    long retireAfterSeconds,
    double onOldAtRetirePercent,
    List<String> guardLog) {

  /** Checks and copies the fields. */
  public ArmResult {
    Objects.requireNonNull(arm, "arm");
    switchSeconds = List.copyOf(switchSeconds);
    guardLog = List.copyOf(guardLog);
  }
}
