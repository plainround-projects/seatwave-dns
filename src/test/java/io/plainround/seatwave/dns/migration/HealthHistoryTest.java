package io.plainround.seatwave.dns.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HealthHistoryTest {

  private static final InetAddress SITE = TestNames.address("192.0.2.10");
  private static final InetAddress OTHER = TestNames.address("203.0.113.30");
  private static final Instant START = Instant.EPOCH;
  private static final Duration GAP = Duration.ofSeconds(30);
  private static final long STEP_SECONDS = 10;
  private static final long WINDOW_SECONDS = 100;

  @Test
  void acceptsASiteHealthyThroughoutTheWindow() {
    HealthHistory health = checksEvery(STEP_SECONDS, WINDOW_SECONDS);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS), GAP)).isEmpty();
  }

  @Test
  void findsAFailedCheckInsideTheWindowButIgnoresOneBeforeIt() {
    HealthHistory health = checksEvery(STEP_SECONDS, WINDOW_SECONDS);
    health.record(START.minusSeconds(STEP_SECONDS), SITE, false);
    health.record(START.plusSeconds(55), SITE, false);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS), GAP))
        .contains("192.0.2.10 failed its health check at 1970-01-01T00:00:55Z");
    assertThat(health.holeIn(SITE, START, START.plusSeconds(50), GAP)).isEmpty();
  }

  @Test
  void findsAGapLongerThanAllowedAndAWindowWithNoChecks() {
    HealthHistory health = new HealthHistory();
    health.record(START, SITE, true);
    health.record(START.plusSeconds(STEP_SECONDS), SITE, true);
    health.record(START.plusSeconds(60), SITE, true);
    health.record(START.plusSeconds(70), SITE, true);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(70), GAP))
        .contains(
            "192.0.2.10 has no healthy check between 1970-01-01T00:00:40Z and 1970-01-01T00:01:00Z");
    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS + STEP_SECONDS), GAP))
        .isPresent();
    assertThat(health.holeIn(OTHER, START, START.plusSeconds(STEP_SECONDS), GAP))
        .contains(
            "203.0.113.30 has no healthy check between 1970-01-01T00:00:00Z and 1970-01-01T00:00:10Z");
  }

  @Test
  void needsAHealthyCheckNearTheStartOfTheWindow() {
    HealthHistory health = new HealthHistory();
    health.record(START.plusSeconds(STEP_SECONDS), SITE, true);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(STEP_SECONDS), GAP)).isPresent();
  }

  private static HealthHistory checksEvery(long stepSeconds, long untilSeconds) {
    HealthHistory health = new HealthHistory();
    for (long second = 0; second <= untilSeconds; second += stepSeconds) {
      health.record(START.plusSeconds(second), SITE, true);
    }
    return health;
  }
}
