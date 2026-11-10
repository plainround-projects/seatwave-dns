package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TtlSimulationTest {

  private static final long ONE_HOUR = 3_600;
  private static final long LOWERED = 60;
  private static final long INTERVAL = 10;
  private static final int FIFTY = 0;
  private static final int NINETY_FIVE = 1;
  private static final int ALL = 3;

  @Test
  void aChangeWithoutLoweringTakesUpToTheOldTtlToReachEveryone() throws Exception {
    ExperimentSettings settings = settings("one-hour");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.switchSeconds().get(NINETY_FIVE)).isGreaterThan(ONE_HOUR * 9 / 10);
    assertThat(result.switchSeconds().get(ALL)).isLessThanOrEqualTo(ONE_HOUR + INTERVAL);
    assertThat(result.retireAfterSeconds()).isEqualTo(2 * ONE_HOUR);
    assertThat(result.cacheHitRatio()).isGreaterThan(0.99);
  }

  @Test
  void loweringOneOldTtlAheadMakesTheSwitchTakeOneShortTtl() throws Exception {
    ExperimentSettings settings = settings("one-hour-lowered");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.switchSeconds().get(FIFTY)).isLessThanOrEqualTo(LOWERED);
    assertThat(result.switchSeconds().get(ALL)).isLessThanOrEqualTo(LOWERED + INTERVAL);
    assertThat(result.retireAfterSeconds()).isEqualTo(2 * LOWERED);
    assertThat(result.onOldAtRetirePercent()).isZero();
    assertThat(result.guardLog())
        .containsExactly(
            "t=3600s applied: seatwave.example. TTL 3600 -> 60 seconds",
            "t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30",
            "t=7201s retire 192.0.2.10: refused, too early; allowed from t=7320s");
  }

  @Test
  void pinningResolversAreStillOnTheOldAddressWhenTheTtlRuleSaysRetire() throws Exception {
    ExperimentSettings settings = settings("lowered-pinning");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.onOldAtRetirePercent()).isGreaterThan(1.0);
    assertThat(result.switchSeconds().get(ALL)).isGreaterThan(LOWERED * 10);
  }

  @Test
  void printsAModelTableWithEveryGuardDecision() throws Exception {
    String table = TtlExperimentCli.render(settings("one-hour-lowered"));

    assertThat(table)
        .startsWith("MODEL, not a measurement.")
        .contains("one-hour-lowered")
        .contains("guard decisions:");
  }

  @Test
  void listsEveryInvalidExperimentSetting() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-experiment.properties"));
    properties.setProperty("arm.one-hour.lowered.ttl.seconds", "7200");
    properties.setProperty("migration.new.address", "not-an-address");
    properties.remove("rate.window.seconds");

    List<String> problems =
        ((Result.Error<ExperimentSettings, List<String>>)
                ExperimentSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "rate.window.seconds is missing",
            "arm.one-hour.lowered.ttl.seconds must not exceed arm.one-hour.ttl.seconds",
            "not an address: not-an-address");
  }

  @Test
  void refusesAnArmThatRaisesTheTtlAndAClockThatGoesBack() {
    assertThatThrownBy(() -> new Arm("x", LOWERED, ONE_HOUR, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    SimulatedClock clock = new SimulatedClock(Instant.EPOCH.plusSeconds(1));
    assertThatThrownBy(() -> clock.advanceTo(Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.withZone(ZoneOffset.UTC))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static ExperimentSettings settings(String arm) throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-experiment.properties"));
    properties.setProperty("resolvers.count", "200");
    properties.setProperty("arm.names", arm);
    return ExperimentSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
  }
}
