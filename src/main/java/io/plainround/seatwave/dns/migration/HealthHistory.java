package io.plainround.seatwave.dns.migration;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every health check of every site, so the guard can ask "did this site answer for the whole
 * window?". Thread-safe: a checker thread records while the guard reads.
 */
public final class HealthHistory {

  private final List<Check> checks = new ArrayList<>();

  /**
   * Records one health check.
   *
   * @param at when the check finished
   * @param site the address of the site checked
   * @param isHealthy whether the site answered in time
   */
  public synchronized void record(Instant at, InetAddress site, boolean isHealthy) {
    checks.add(
        new Check(
            Objects.requireNonNull(at, "at"), Objects.requireNonNull(site, "site"), isHealthy));
  }

  /**
   * Looks for the first hole in a site's health over a window: a failed check inside it, or a
   * stretch longer than the allowed gap with no healthy check. Every moment of the window must have
   * a healthy check at most one gap before it.
   *
   * @param site the site
   * @param from the start of the window
   * @param to the end of the window
   * @param maxGap the longest time allowed between two healthy checks
   * @return the hole, described in one line, or empty if the site was healthy throughout
   */
  public synchronized Optional<String> holeIn(
      InetAddress site, Instant from, Instant to, Duration maxGap) {
    List<Check> relevant =
        checks.stream()
            .filter(check -> check.site().equals(site))
            .filter(check -> !check.at().isBefore(from.minus(maxGap)) && !check.at().isAfter(to))
            .sorted(Comparator.comparing(Check::at))
            .toList();
    String siteText = site.getHostAddress();
    Optional<Instant> lastHealthy = Optional.empty();
    for (Check check : relevant) {
      if (!check.isHealthy()) {
        if (!check.at().isBefore(from)) {
          return Optional.of(siteText + " failed its health check at " + check.at());
        }
        continue;
      }
      Instant coveredUntil = lastHealthy.map(last -> last.plus(maxGap)).orElse(from);
      if (check.at().isAfter(coveredUntil)) {
        return Optional.of(
            siteText + " has no healthy check between " + coveredUntil + " and " + check.at());
      }
      lastHealthy = Optional.of(check.at());
    }
    if (lastHealthy.isEmpty()) {
      return Optional.of(siteText + " has no healthy check between " + from + " and " + to);
    }
    if (to.isAfter(lastHealthy.get().plus(maxGap))) {
      return Optional.of(siteText + " has no healthy check since " + lastHealthy.get());
    }
    return Optional.empty();
  }

  private record Check(Instant at, InetAddress site, boolean isHealthy) {}
}
