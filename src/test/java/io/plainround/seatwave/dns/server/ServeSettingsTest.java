package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ServeSettingsTest {

  private static final int ROOT_PORT = 5301;
  private static final int SEATWAVE_PORT = 5303;
  private static final Duration FIVE_MINUTES = Duration.ofSeconds(300);

  @Test
  void readsTheThreeLabListeners() throws Exception {
    ServeSettings settings = read("config/serve.properties").discardError().orElseThrow();

    assertThat(settings.listeners())
        .extracting(ServeSettings.Listener::name)
        .containsExactly("root", "tld", "seatwave");
    assertThat(settings.listeners().getFirst().port()).isEqualTo(ROOT_PORT);
    assertThat(settings.listeners().getLast().port()).isEqualTo(SEATWAVE_PORT);
    assertThat(settings.serveFor()).isEqualTo(FIVE_MINUTES);
  }

  @Test
  void refusesTheBrokenZoneWithItsFourMistakes() throws Exception {
    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>) read("config/serve-broken.properties"))
            .error();

    assertThat(problems).hasSize(4).allMatch(problem -> problem.startsWith("seatwave: "));
  }

  @Test
  void refusesAPrivilegedPortAndMissingKeys() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("listener.root.port", "53");
    properties.remove("listener.tld.zone.file");

    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>)
                ServeSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "listener.root.port must be from 1024 to 65535, but was 53",
            "listener.tld.zone.file is missing");
  }

  @Test
  void refusesTwoListenersOnOnePort() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("listener.tld.port", String.valueOf(ROOT_PORT));

    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>)
                ServeSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems).containsExactly("two listeners share a port");
  }

  private static Result<ServeSettings, List<String>> read(String file) throws Exception {
    return ServeSettings.from(new SettingsReader(SettingsReader.load(Path.of(file))));
  }
}
