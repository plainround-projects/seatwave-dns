package io.plainround.seatwave.dns.sampling;

import io.plainround.seatwave.dns.message.DomainName;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What many lookups of one name returned: how often each address came first, how many lookups
 * failed, and the lookup latency.
 *
 * @param name the name looked up
 * @param lookups how many lookups were sent
 * @param firstCounts how many answers had each address first, in the order first seen
 * @param failures lookups with no usable answer
 * @param latencyP50 the median lookup time
 * @param latencyP99 the lookup time 99 percent of lookups beat
 * @param latencyMax the slowest lookup
 */
public record SampleResult(
    DomainName name,
    int lookups,
    Map<InetAddress, Integer> firstCounts,
    int failures,
    Duration latencyP50,
    Duration latencyP99,
    Duration latencyMax) {

  /**
   * Checks and copies the fields.
   *
   * @throws IllegalArgumentException if the counts do not add up to the lookups
   */
  public SampleResult {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(latencyP50, "latencyP50");
    Objects.requireNonNull(latencyP99, "latencyP99");
    Objects.requireNonNull(latencyMax, "latencyMax");
    firstCounts = Collections.unmodifiableMap(new LinkedHashMap<>(firstCounts));
    int counted = firstCounts.values().stream().mapToInt(Integer::intValue).sum();
    if (lookups < 1 || counted + failures != lookups) {
      throw new IllegalArgumentException("every lookup must be counted once");
    }
  }

  /**
   * Returns the share of answers that had an address first.
   *
   * @param address the address
   * @return the share from 0 to 1, of all lookups
   */
  public double shareOf(InetAddress address) {
    return (double) firstCounts.getOrDefault(address, 0) / lookups;
  }
}
