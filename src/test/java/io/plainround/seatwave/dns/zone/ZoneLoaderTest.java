package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ZoneLoaderTest {

  @Test
  void loadsTheThreeLabZones() {
    assertThat(TestZones.root().records()).hasSize(5);
    assertThat(TestZones.example().records()).hasSize(5);
    assertThat(TestZones.seatwave().records()).hasSize(11);
  }

  @Test
  void reportsAMissingFileABadOriginAndBrokenWeights() {
    Result<Zone, List<String>> missing =
        ZoneLoader.load("seatwave.example.", Path.of("zones/absent.zone"), ZoneLoader.NO_WEIGHTS);
    Result<Zone, List<String>> badOrigin =
        ZoneLoader.load(
            "bad..origin", Path.of("zones/seatwave.example.zone"), ZoneLoader.NO_WEIGHTS);
    Result<Zone, List<String>> broken =
        ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/broken/seatwave.example.zone"),
            "zones/broken/seatwave.example.weights");

    assertThat(((Result.Error<Zone, List<String>>) missing).error())
        .singleElement()
        .asString()
        .startsWith("cannot read zones/absent.zone");
    assertThat(((Result.Error<Zone, List<String>>) badOrigin).error())
        .contains("origin: a name must not have an empty label: bad..origin");
    assertThat(((Result.Error<Zone, List<String>>) broken).error()).hasSize(4);
  }

  @Test
  void leavesMissingKeysToTheReader() {
    SettingsReader reader = new SettingsReader(new Properties());

    Result<Zone, List<String>> loaded = ZoneLoader.load(reader, "zone");

    assertThat(((Result.Error<Zone, List<String>>) loaded).error()).isEmpty();
    assertThat(reader.problems())
        .containsExactly(
            "zone.origin is missing", "zone.file is missing", "zone.weights is missing");
  }

  @Test
  void reportsZoneFileProblemsWithTheFileName() {
    Result<Zone, List<String>> loaded =
        ZoneLoader.load(
            "seatwave.example.", Path.of("config/serve.properties"), ZoneLoader.NO_WEIGHTS);

    assertThat(((Result.Error<Zone, List<String>>) loaded).error())
        .allMatch(problem -> problem.startsWith("serve.properties line "));
  }
}
