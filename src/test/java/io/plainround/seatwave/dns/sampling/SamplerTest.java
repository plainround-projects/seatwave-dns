package io.plainround.seatwave.dns.sampling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import io.plainround.seatwave.dns.zone.TestZones;
import io.plainround.seatwave.dns.zone.Zone;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SamplerTest {

  private static final DomainName BOOK = TestNames.name("book.seatwave.example");
  private static final int LOOKUPS = 10_000;
  private static final int FEW = 3;
  private static final long SEED = 20_261_110;

  @Test
  void measuresTheConfiguredSplitWithinThreeStandardErrors() {
    Zone zone = TestZones.seatwaveNinetyTen();
    InMemoryUpstream upstream =
        new InMemoryUpstream(new ZoneStore(zone, Clock.systemUTC()), new SplittableRandom(SEED));

    SampleResult result = Sampler.sample(upstream, BOOK, RecordType.A, LOOKUPS);
    String report = SampleReport.render(result, zone);

    assertThat(result.failures()).isZero();
    assertThat(report).contains("192.0.2.10").contains("198.51.100.20").doesNotContain("OUTSIDE");
    assertThat(report).containsPattern("192\\.0\\.2\\.10 +90 +90\\.00 ");
  }

  @Test
  void countsEveryFailedLookupAndFlagsAShareOutsideItsTolerance() {
    SampleResult result =
        Sampler.sample(
            question -> Result.error(new LookupProblem.TimedOut(Duration.ofMillis(1))),
            BOOK,
            RecordType.A,
            FEW);
    SampleResult skewed =
        new SampleResult(
            BOOK,
            LOOKUPS,
            Map.of(TestNames.address("192.0.2.10"), LOOKUPS),
            0,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ZERO);

    assertThat(result.failures()).isEqualTo(FEW);
    assertThat(result.shareOf(TestNames.address("192.0.2.10"))).isZero();
    assertThat(SampleReport.render(skewed, TestZones.seatwave())).contains("OUTSIDE");
  }

  @Test
  void refusesCountsThatDoNotAddUp() {
    assertThatThrownBy(() ->
            new SampleResult(BOOK, FEW, Map.of(), 1, Duration.ZERO, Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            Sampler.sample(
                question -> Result.error(new LookupProblem.TimedOut(Duration.ZERO)),
                BOOK,
                RecordType.A,
                0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void runsTheProjectsSampleAgainstALocalServerOverUdp() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/sample-90-10.properties"));
    properties.setProperty("lookup.count", "2000");
    properties.setProperty("lookup.warmup.count", "10");
    SampleSettings settings =
        SampleSettings.from(new SettingsReader(properties)).discardError().orElseThrow();

    String report = SampleCli.run(settings);

    assertThat(report).contains("the lab server on 127.0.0.1:").contains("2000 lookups, 0 failed");
  }

  @Test
  void listsEveryInvalidSampleSetting() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/sample.properties"));
    properties.setProperty("lookup.type", "txt");
    properties.setProperty("lookup.name", "bad..name");
    properties.setProperty("server.external.port", "53");

    List<String> problems =
        ((Result.Error<SampleSettings, List<String>>)
                SampleSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "server.external.port must be from 1024 to 65535, but was 53",
            "lookup.name: a name must not have an empty label: bad..name",
            "lookup.type must be a or aaaa");
  }
}
