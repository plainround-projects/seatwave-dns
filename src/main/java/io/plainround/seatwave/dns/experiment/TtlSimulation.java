package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.migration.ApprovedChange;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.MigrationRefusal;
import io.plainround.seatwave.dns.migration.Publication;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import io.plainround.seatwave.dns.resolver.Resolution;
import io.plainround.seatwave.dns.zone.Zone;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The TTL-change experiment as a simulation on a {@link SimulatedClock}: many caching resolvers ask
 * for a name at a steady pace while a migration moves it to a new address through the {@link
 * MigrationGuard}, and the run records when each resolver first gives the new address.
 *
 * <p>The timeline of every arm, in seconds from the start: at 0 the record is published with the
 * arm's starting TTL; each resolver starts asking at a random moment of the first old TTL, so by
 * then every cache is warm and the cache ages are spread evenly; if the arm lowers the TTL, it does
 * so at one old TTL; the switch is at two old TTLs; one second after the switch the guard is asked,
 * too early, to approve retiring the old address. Both sites pass every health check. The
 * resolvers' questions go through the real {@code Responder} and message codec, without a socket.
 * Stateless and thread-safe; each run builds its own objects.
 */
public final class TtlSimulation {

  // Times are reported relative to the start; the absolute date does not matter.
  private static final Instant START = Instant.EPOCH;
  private static final double[] SWITCH_SHARES = {0.50, 0.95, 0.99, 1.00};
  private static final double PERCENT = 100;
  // The run continues this many query intervals past the last moment any cache can expire.
  private static final int TAIL_INTERVALS = 2;

  private TtlSimulation() {}

  /**
   * Runs one arm.
   *
   * @param settings the experiment's settings
   * @param arm the arm
   * @return what the arm measured
   */
  public static ArmResult run(ExperimentSettings settings, Arm arm) {
    long oldTtl = arm.initialTtlSeconds();
    long switchAt = 2 * oldTtl;
    long end =
        switchAt
            + Math.max(oldTtl, arm.pinningHoldSeconds())
            + TAIL_INTERVALS * settings.queryInterval().toSeconds();
    SimulatedClock clock = new SimulatedClock(START);
    ZoneStore store = new ZoneStore(startingZone(settings, oldTtl), clock);
    HealthHistory health = new HealthHistory();
    List<InetAddress> sites = new ArrayList<>(settings.zone().addressesOf(settings.name()));
    sites.add(settings.newAddress());
    for (long second = 0; second <= end; second += settings.healthInterval().toSeconds()) {
      for (InetAddress site : sites) {
        health.record(START.plusSeconds(second), site, true);
      }
    }
    Run run =
        new Run(
            settings,
            arm,
            clock,
            store,
            new MigrationGuard(store, health, clock, settings.maxCheckGap()),
            switchAt);
    run.ask(end);
    return run.result();
  }

  private static Zone startingZone(ExperimentSettings settings, long ttlSeconds) {
    List<ResourceRecord> records =
        settings.zone().records().stream()
            .map(record ->
                record.owner().equals(settings.name())
                        && (record.type() == RecordType.A || record.type() == RecordType.AAAA)
                    ? record.withTtl(ttlSeconds)
                    : record)
            .toList();
    return switch (settings.zone().with(records, settings.zone().weights())) {
      case Result.Success<Zone, List<String>>(Zone zone) -> zone;
      case Result.Error<Zone, List<String>>(List<String> problems) ->
          throw new IllegalStateException("a TTL change broke a valid zone: " + problems);
    };
  }

  /** One arm's moving parts. Confined to one call of {@link #run}. */
  private static final class Run {
    private final ExperimentSettings settings;
    private final Arm arm;
    private final SimulatedClock clock;
    private final ZoneStore store;
    private final MigrationGuard guard;
    private final long switchAt;
    private final InMemoryUpstream upstream;
    private final List<CachingResolver> resolvers = new ArrayList<>();
    private final long[] phases;
    private final long[] firstRounds;
    private final long[] firstNew;
    private final List<String> log = new ArrayList<>();
    private boolean hasLowered;
    private boolean hasSwitched;
    private boolean hasTriedRetire;
    private long windowLookups;
    private long windowQueries;

    Run(
        ExperimentSettings settings,
        Arm arm,
        SimulatedClock clock,
        ZoneStore store,
        MigrationGuard guard,
        long switchAt) {
      this.settings = settings;
      this.arm = arm;
      this.clock = clock;
      this.store = store;
      this.guard = guard;
      this.switchAt = switchAt;
      SplittableRandom random = new SplittableRandom(settings.seed());
      this.upstream = new InMemoryUpstream(store, random.split());
      int pinning = (int) Math.round(settings.resolvers() * arm.pinningPercent() / PERCENT);
      phases = new long[settings.resolvers()];
      firstRounds = new long[settings.resolvers()];
      firstNew = new long[settings.resolvers()];
      Arrays.fill(firstNew, -1);
      for (int index = 0; index < settings.resolvers(); index++) {
        Duration minimumHold =
            index < pinning ? Duration.ofSeconds(arm.pinningHoldSeconds()) : Duration.ZERO;
        CachePolicy policy =
            new CachePolicy(
                minimumHold,
                settings.maximumHold().compareTo(minimumHold) < 0
                    ? minimumHold
                    : settings.maximumHold(),
                settings.maximumNegativeHold(),
                Duration.ZERO);
        resolvers.add(new CachingResolver(upstream, clock, policy));
        phases[index] = random.nextLong(settings.queryInterval().toSeconds());
        // Each resolver first asks at a random moment of the first old TTL, so cache ages are
        // spread as they are on the internet, not all filled at the same second.
        firstRounds[index] =
            random.nextLong(
                Math.max(1, arm.initialTtlSeconds() / settings.queryInterval().toSeconds()));
      }
    }

    void ask(long end) {
      long interval = settings.queryInterval().toSeconds();
      Integer[] order = new Integer[phases.length];
      Arrays.setAll(order, index -> index);
      // Resolvers are visited in the order of their phase, so time only moves forward.
      Arrays.sort(order, (left, right) -> Long.compare(phases[left], phases[right]));
      for (long round = 0; round * interval <= end; round++) {
        for (int index : order) {
          long second = phases[index] + round * interval;
          if (second > end) {
            break;
          }
          if (round < firstRounds[index]) {
            continue;
          }
          act(second);
          clock.advanceTo(START.plusSeconds(second));
          lookUp(index, second);
        }
      }
    }

    private void lookUp(int index, long second) {
      long queriesBefore = upstream.queries();
      Resolution resolution = resolvers.get(index).resolve(settings.name(), RecordType.A);
      if (second >= switchAt - settings.rateWindow().toSeconds() && second < switchAt) {
        windowLookups++;
        windowQueries += upstream.queries() - queriesBefore;
      }
      List<InetAddress> addresses = resolution.addresses();
      boolean isNew = !addresses.isEmpty() && addresses.getFirst().equals(settings.newAddress());
      if (hasSwitched && isNew && firstNew[index] < 0) {
        firstNew[index] = second - switchAt;
      }
    }

    private void act(long second) {
      if (arm.isLowering() && !hasLowered && second >= arm.initialTtlSeconds()) {
        hasLowered = true;
        clock.advanceTo(START.plusSeconds(arm.initialTtlSeconds()));
        record(guard.changeTtl(settings.name(), arm.loweredTtlSeconds()));
      }
      if (!hasSwitched && second >= switchAt) {
        hasSwitched = true;
        clock.advanceTo(START.plusSeconds(switchAt));
        record(guard.switchAddress(settings.name(), settings.oldAddress(), settings.newAddress()));
      }
      if (hasSwitched && !hasTriedRetire && second > switchAt) {
        hasTriedRetire = true;
        clock.advanceTo(START.plusSeconds(switchAt + 1));
        switch (guard.approveRetirement(settings.name(), settings.oldAddress())) {
          case Result.Success<Instant, MigrationRefusal>(Instant approved) ->
              log.add(
                  at(approved)
                      + " retire "
                      + settings.oldAddress().getHostAddress()
                      + ": approved");
          case Result.Error<Instant, MigrationRefusal>(MigrationRefusal refusal) ->
              log.add(
                  at(clock.instant())
                      + " retire "
                      + settings.oldAddress().getHostAddress()
                      + ": "
                      + brief(refusal));
        }
      }
    }

    private void record(Result<ApprovedChange, MigrationRefusal> decision) {
      switch (decision) {
        case Result.Success<ApprovedChange, MigrationRefusal>(ApprovedChange change) -> {
          if (store.apply(change) instanceof Result.Success<Publication, MigrationRefusal>) {
            log.add(at(clock.instant()) + " applied: " + change.description());
          }
        }
        case Result.Error<ApprovedChange, MigrationRefusal>(MigrationRefusal refusal) ->
            log.add(at(clock.instant()) + " " + refusal.describe());
      }
    }

    private String brief(MigrationRefusal refusal) {
      return switch (refusal) {
        case MigrationRefusal.TooEarly(Instant earliest, String reason) ->
            "refused, too early; allowed from " + at(earliest);
        case MigrationRefusal.UnhealthySite unhealthy -> unhealthy.describe();
        case MigrationRefusal.BadRequest bad -> bad.describe();
      };
    }

    private static String at(Instant instant) {
      return String.format(Locale.ROOT, "t=%ds", Duration.between(START, instant).toSeconds());
    }

    ArmResult result() {
      long[] sorted = firstNew.clone();
      // A resolver that never switched sorts last.
      for (int index = 0; index < sorted.length; index++) {
        sorted[index] = sorted[index] < 0 ? Long.MAX_VALUE : sorted[index];
      }
      Arrays.sort(sorted);
      List<Long> shares = new ArrayList<>();
      for (double share : SWITCH_SHARES) {
        long value = sorted[(int) Math.ceil(share * sorted.length) - 1];
        shares.add(value == Long.MAX_VALUE ? -1 : value);
      }
      long retireAfter =
          guard
              .earliestRetirement(settings.name(), settings.oldAddress())
              .map(earliest -> Duration.between(START.plusSeconds(switchAt), earliest).toSeconds())
              .orElse(-1L);
      long stillOld =
          Arrays.stream(firstNew).filter(seen -> seen < 0 || seen > retireAfter).count();
      return new ArmResult(
          arm,
          windowLookups == 0 ? 0 : 1 - (double) windowQueries / windowLookups,
          (double) windowQueries / settings.rateWindow().toSeconds(),
          shares,
          retireAfter,
          PERCENT * stillOld / firstNew.length,
          log);
    }
  }
}
