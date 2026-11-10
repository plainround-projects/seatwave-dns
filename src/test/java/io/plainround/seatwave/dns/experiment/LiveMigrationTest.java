package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LiveMigrationTest {

  private static final Duration DAY = Duration.ofDays(1);
  private static final double LOWERED_TTL_PLUS_ONE_POLL = 2.25;

  private final SimulatedClock clock = new SimulatedClock(Instant.EPOCH);

  @Test
  void runsTheWholeMigrationAndRetiresOnlyWhenTheRuleAllows() throws Exception {
    LiveSettings settings = settings();
    List<String> log = new ArrayList<>();

    Map<String, Double> sawNew = run(settings, site -> true, log);

    assertThat(log)
        .contains("t= 10.000 s  guard approved, applied: seatwave.example. TTL 10 -> 2 seconds")
        .contains(
            "t= 20.000 s  guard approved, applied: seatwave.example. 192.0.2.10 -> 203.0.113.30")
        .contains(
            "t= 20.000 s  retire 192.0.2.10: refused: caches may still send fans to 192.0.2.10;"
                + " keep it serving; allowed from t=24.000 s")
        .contains("t= 24.000 s  guard approved: retire 192.0.2.10");
    assertThat(sawNew.get("honest")).isLessThanOrEqualTo(LOWERED_TTL_PLUS_ONE_POLL);
    assertThat(sawNew.get("pinning")).isGreaterThan(sawNew.get("honest"));
  }

  @Test
  void refusesTheSwitchWhenTheNewSiteIsDown() throws Exception {
    LiveSettings settings = settings();
    List<String> log = new ArrayList<>();

    Map<String, Double> sawNew = run(settings, site -> !site.equals(settings.newAddress()), log);

    assertThat(log)
        .anyMatch(line -> line.contains("refused: 203.0.113.30 failed its health check"));
    assertThat(sawNew).isEmpty();
  }

  private Map<String, Double> run(
      LiveSettings settings, Predicate<InetAddress> siteCheck, List<String> log)
      throws InterruptedException {
    ZoneStore store = new ZoneStore(settings.zone(), clock);
    HealthHistory health = new HealthHistory();
    MigrationGuard guard = new MigrationGuard(store, health, clock, settings.maxCheckGap());
    InMemoryUpstream upstream = new InMemoryUpstream(store, new SplittableRandom(1));
    List<LiveMigration.Client> clients =
        List.of(
            client("honest", upstream, Duration.ZERO, settings),
            client("pinning", upstream, settings.pinningHold(), settings));
    LiveMigration migration =
        new LiveMigration(
            settings,
            new LiveMigration.Parts(store, health, guard, siteCheck),
            clock,
            duration -> clock.advanceTo(clock.instant().plus(duration)));
    return migration.run(clients, log::add);
  }

  private LiveMigration.Client client(
      String name, InMemoryUpstream upstream, Duration minimumHold, LiveSettings settings) {
    CachingResolver resolver =
        new CachingResolver(upstream, clock, new CachePolicy(minimumHold, DAY, DAY, Duration.ZERO));
    return new LiveMigration.Client(
        name,
        () ->
            resolver
                .resolve(settings.name(), RecordType.A)
                .addresses()
                .getFirst()
                .getHostAddress());
  }

  private static LiveSettings settings() throws Exception {
    return LiveSettings.from(
            new SettingsReader(SettingsReader.load(Path.of("config/ttl-live.properties"))))
        .discardError()
        .orElseThrow();
  }
}
