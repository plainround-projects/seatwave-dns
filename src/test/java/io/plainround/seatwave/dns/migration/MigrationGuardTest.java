package io.plainround.seatwave.dns.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.experiment.SimulatedClock;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationGuardTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final InetAddress OLD = TestNames.address("192.0.2.10");
  private static final InetAddress OLD_IPV6 = TestNames.address("2001:db8::10");
  private static final InetAddress NEW = TestNames.address("203.0.113.30");
  private static final InetAddress NEW_IPV6 = TestNames.address("2001:db8::30");
  private static final Instant START = Instant.EPOCH;
  private static final Duration GAP = Duration.ofSeconds(30);
  private static final long HEALTH_STEP = 10;
  private static final long ONE_HOUR = 3_600;
  private static final long LOWERED = 60;
  private static final long FAR = 20_000;

  private final SimulatedClock clock = new SimulatedClock(START);
  private final ZoneStore store = new ZoneStore(TestZones.seatwave(), clock);
  private final HealthHistory health = new HealthHistory();
  private final MigrationGuard guard = new MigrationGuard(store, health, clock, GAP);

  @Test
  void approvesLoweringTheTtlAfterOneOldTtlOfHealthyChecks() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    ApprovedChange change = approved(guard.changeTtl(APEX, LOWERED));
    store.apply(change);

    assertThat(change.description()).isEqualTo("seatwave.example. TTL 3600 -> 60 seconds");
    assertThat(store.current().recordsAt(APEX, RecordType.A))
        .singleElement()
        .satisfies(record -> assertThat(record.ttlSeconds()).isEqualTo(LOWERED));
    assertThat(store.history()).hasSize(2);
  }

  @Test
  void refusesAnyChangeBeforeTheOldTtlWindowHasBeenWatched() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(LOWERED));

    Result<ApprovedChange, MigrationRefusal> early = guard.switchAddress(APEX, OLD, NEW);

    assertThat(((Result.Error<ApprovedChange, MigrationRefusal>) early).error())
        .isInstanceOf(MigrationRefusal.UnhealthySite.class)
        .extracting(MigrationRefusal::describe)
        .asString()
        .contains("192.0.2.10 has no healthy check between");
  }

  @Test
  void refusesASwitchWhenTheNewSiteFailedACheck() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    health.record(START.plusSeconds(ONE_HOUR / 2), NEW, false);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    Result<ApprovedChange, MigrationRefusal> refused = guard.switchAddress(APEX, OLD, NEW);

    assertThat(((Result.Error<ApprovedChange, MigrationRefusal>) refused).error())
        .isEqualTo(
            new MigrationRefusal.UnhealthySite(
                NEW, "203.0.113.30 failed its health check at 1970-01-01T00:30:00Z"));
  }

  @Test
  void refusesSwitchesThatDoNotFitTheZone() {
    healthy(List.of(OLD, OLD_IPV6, NEW, NEW_IPV6), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    assertThat(guard.switchAddress(APEX, NEW, OLD)).isInstanceOf(Result.Error.class);
    assertThat(guard.switchAddress(APEX, OLD, NEW_IPV6)).isInstanceOf(Result.Error.class);
    assertThat(guard.changeTtl(TestNames.name("nowhere.seatwave.example"), LOWERED))
        .isInstanceOf(Result.Error.class);
  }

  @Test
  void waitsTwiceTheOldTtlAfterASwitchWithoutLowering() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    store.apply(approved(guard.switchAddress(APEX, OLD, NEW)));
    Instant switchedAt = clock.instant();

    clock.advanceTo(switchedAt.plusSeconds(1));
    Result<Instant, MigrationRefusal> early = guard.approveRetirement(APEX, OLD);
    clock.advanceTo(switchedAt.plusSeconds(2 * ONE_HOUR));
    Result<Instant, MigrationRefusal> onTime = guard.approveRetirement(APEX, OLD);

    assertThat(((Result.Error<Instant, MigrationRefusal>) early).error())
        .isInstanceOf(MigrationRefusal.TooEarly.class)
        .extracting(refusal -> ((MigrationRefusal.TooEarly) refusal).earliest())
        .isEqualTo(switchedAt.plusSeconds(2 * ONE_HOUR));
    assertThat(onTime).isEqualTo(Result.success(switchedAt.plusSeconds(2 * ONE_HOUR)));
  }

  @Test
  void waitsOnlyTwiceTheLoweredTtlWhenTheLoweringCameOneOldTtlEarly() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    store.apply(approved(guard.changeTtl(APEX, LOWERED)));
    clock.advanceTo(START.plusSeconds(2 * ONE_HOUR));

    assertThat(guard.oldTtlWindowSeconds(APEX)).isEqualTo(ONE_HOUR);
    store.apply(approved(guard.switchAddress(APEX, OLD, NEW)));

    assertThat(guard.earliestRetirement(APEX, OLD))
        .contains(START.plusSeconds(2 * ONE_HOUR + 2 * LOWERED));
    assertThat(guard.oldTtlWindowSeconds(APEX)).isEqualTo(LOWERED);
  }

  @Test
  void refusesToRetireAnAddressThatIsStillPublished() {
    Result<Instant, MigrationRefusal> refused = guard.approveRetirement(APEX, OLD);

    assertThat(((Result.Error<Instant, MigrationRefusal>) refused).error())
        .isInstanceOf(MigrationRefusal.BadRequest.class);
    assertThat(guard.earliestRetirement(APEX, NEW)).isEmpty();
  }

  @Test
  void refusesAnApprovalMadeAgainstAnOlderZone() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    ApprovedChange first = approved(guard.changeTtl(APEX, LOWERED));
    ApprovedChange second = approved(guard.switchAddress(APEX, OLD, NEW));
    store.apply(first);

    Result<Publication, MigrationRefusal> stale = store.apply(second);

    assertThat(((Result.Error<Publication, MigrationRefusal>) stale).error().describe())
        .isEqualTo("refused: the zone changed after this change was approved; approve it again");
  }

  @Test
  void refusesAZeroGapBetweenChecks() {
    assertThatThrownBy(() -> new MigrationGuard(store, health, clock, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void describesEveryRefusal() {
    assertThat(new MigrationRefusal.TooEarly(START, "wait").describe())
        .isEqualTo("refused: wait; allowed from 1970-01-01T00:00:00Z");
    assertThat(new MigrationRefusal.BadRequest("no").describe()).isEqualTo("refused: no");
  }

  private void healthy(List<InetAddress> sites, long untilSeconds) {
    for (long second = 0; second <= untilSeconds; second += HEALTH_STEP) {
      for (InetAddress site : sites) {
        health.record(START.plusSeconds(second), site, true);
      }
    }
  }

  private static ApprovedChange approved(Result<ApprovedChange, MigrationRefusal> decision) {
    return decision.discardError().orElseThrow();
  }
}
