package io.plainround.seatwave.dns.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.experiment.SimulatedClock;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.zone.TestZones;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CachingResolverTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final Instant START = Instant.EPOCH;
  private static final long ONE_HOUR = 3_600;
  private static final double TOLERANCE = 1e-9;
  private static final long NEGATIVE_TTL = 300;
  private static final Duration DAY = Duration.ofDays(1);
  private static final Duration PIN = Duration.ofHours(2);
  private static final Duration STALE = Duration.ofMinutes(30);
  private static final CachePolicy HONEST = new CachePolicy(Duration.ZERO, DAY, DAY, Duration.ZERO);

  private final SimulatedClock clock = new SimulatedClock(START);
  private final InMemoryUpstream upstream =
      new InMemoryUpstream(new ZoneStore(TestZones.seatwave(), clock), new SplittableRandom(1));

  @Test
  void answersFromTheCacheUntilTheTtlEnds() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);

    Resolution first = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR - 1));
    Resolution cached = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    Resolution expired = resolver.resolve(APEX, RecordType.A);

    assertThat(first.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(cached.source()).isEqualTo(Resolution.Source.CACHE);
    assertThat(expired.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(cached.addresses()).containsExactly(TestNames.address("192.0.2.10"));
    assertThat(resolver.serverQueries()).isEqualTo(2);
    assertThat(resolver.cacheHitRatio()).isCloseTo(1.0 / 3, offset(TOLERANCE));
  }

  @Test
  void keepsAnAnswerLongerThanItsTtlWhenThePolicyPinsIt() {
    CachingResolver pinning =
        new CachingResolver(upstream, clock, new CachePolicy(PIN, DAY, DAY, Duration.ZERO));

    pinning.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR + 1));

    assertThat(pinning.resolve(APEX, RecordType.A).source()).isEqualTo(Resolution.Source.CACHE);
  }

  @Test
  void capsTheHoldAtTheMaximum() {
    CachingResolver capped =
        new CachingResolver(
            upstream,
            clock,
            new CachePolicy(Duration.ZERO, Duration.ofSeconds(10), DAY, Duration.ZERO));

    capped.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(10));

    assertThat(capped.resolve(APEX, RecordType.A).source()).isEqualTo(Resolution.Source.SERVER);
  }

  @Test
  void cachesANoForTheSoaMinimum() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);
    DomainName missing = TestNames.name("nowhere.seatwave.example");

    Resolution first = resolver.resolve(missing, RecordType.A);
    clock.advanceTo(START.plusSeconds(NEGATIVE_TTL - 1));
    Resolution cached = resolver.resolve(missing, RecordType.A);
    clock.advanceTo(START.plusSeconds(NEGATIVE_TTL));
    Resolution again = resolver.resolve(missing, RecordType.A);

    assertThat(first.code()).isEqualTo(ResponseCode.NAME_ERROR);
    assertThat(cached.source()).isEqualTo(Resolution.Source.CACHE);
    assertThat(again.source()).isEqualTo(Resolution.Source.SERVER);
  }

  @Test
  void servesAStaleAnswerWhileTheServerIsDownAndOnlyForAWhile() {
    AtomicBoolean isDown = new AtomicBoolean();
    Upstream flaky =
        question ->
            isDown.get()
                ? Result.error(new LookupProblem.TimedOut(Duration.ofSeconds(1)))
                : upstream.ask(question);
    CachingResolver resolver =
        new CachingResolver(flaky, clock, new CachePolicy(Duration.ZERO, DAY, DAY, STALE));

    resolver.resolve(APEX, RecordType.A);
    isDown.set(true);
    clock.advanceTo(START.plusSeconds(ONE_HOUR + 1));
    Resolution stale = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR).plus(STALE));
    Resolution failed = resolver.resolve(APEX, RecordType.A);

    assertThat(stale.source()).isEqualTo(Resolution.Source.STALE);
    assertThat(stale.addresses()).containsExactly(TestNames.address("192.0.2.10"));
    assertThat(failed.source()).isEqualTo(Resolution.Source.FAILED);
    assertThat(failed.code()).isEqualTo(ResponseCode.SERVER_FAILURE);
  }

  @Test
  void doesNotCacheARefusal() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);
    DomainName outside = TestNames.name("example.com");

    Resolution first = resolver.resolve(outside, RecordType.A);
    Resolution second = resolver.resolve(outside, RecordType.A);

    assertThat(first.code()).isEqualTo(ResponseCode.REFUSED);
    assertThat(second.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(upstream.queries()).isEqualTo(2);
  }

  @Test
  void reportsAZeroHitRatioBeforeTheFirstLookup() {
    assertThat(new CachingResolver(upstream, clock, HONEST).cacheHitRatio()).isZero();
  }

  @Test
  void refusesANegativeOrInvertedPolicy() {
    assertThatThrownBy(() -> new CachePolicy(Duration.ofSeconds(-1), DAY, DAY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CachePolicy(DAY.plusSeconds(1), DAY, DAY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
