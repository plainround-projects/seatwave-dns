package io.plainround.seatwave.dns.sampling;

import io.plainround.seatwave.dns.zone.Zone;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Prints a sample against the weights the zone configures. A measured share counts as "within" when
 * it is at most three standard errors from the configured share; for a share p over n lookups, one
 * standard error is the square root of p(1 - p) / n. Stateless and thread-safe.
 */
public final class SampleReport {

  // Three standard errors: a correct server lands outside about 3 times in 1,000 samples.
  private static final double STANDARD_ERRORS_ALLOWED = 3;
  private static final double PERCENT = 100;
  private static final double NANOS_PER_MILLI = 1_000_000;

  private SampleReport() {}

  /**
   * Renders the table.
   *
   * @param result the sample
   * @param zone the zone, for the configured weights and the addresses of the name
   * @return the table and the latency line
   */
  public static String render(SampleResult result, Zone zone) {
    List<InetAddress> addresses = zone.addressesOf(result.name());
    int totalWeight =
        addresses.stream()
            .mapToInt(address -> zone.weights().weightOf(result.name(), address))
            .sum();
    StringBuilder table = new StringBuilder();
    table.append(
        String.format(
            Locale.ROOT,
            "%s %d lookups, %d failed%n%-16s %6s %12s %10s %10s %8s  %s%n",
            result.name(),
            result.lookups(),
            result.failures(),
            "address",
            "weight",
            "configured %",
            "measured %",
            "difference",
            "allowed",
            "verdict"));
    for (InetAddress address : addresses) {
      int weight = zone.weights().weightOf(result.name(), address);
      double configured = (double) weight / totalWeight;
      double measured = result.shareOf(address);
      double allowed =
          STANDARD_ERRORS_ALLOWED * Math.sqrt(configured * (1 - configured) / result.lookups());
      double difference = measured - configured;
      table.append(
          String.format(
              Locale.ROOT,
              "%-16s %6d %12.2f %10.2f %+10.2f %8.2f  %s%n",
              address.getHostAddress(),
              weight,
              PERCENT * configured,
              PERCENT * measured,
              PERCENT * difference,
              PERCENT * allowed,
              Math.abs(difference) <= allowed ? "within" : "OUTSIDE"));
    }
    table.append(
        String.format(
            Locale.ROOT,
            "lookup latency over UDP on 127.0.0.1: p50 %.3f ms, p99 %.3f ms, max %.3f ms%n",
            millis(result.latencyP50()),
            millis(result.latencyP99()),
            millis(result.latencyMax())));
    return table.toString();
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
