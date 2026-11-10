package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SiteEndpointsTest {

  private static final InetAddress SITE = TestNames.address("192.0.2.10");
  private static final Duration TIMEOUT = Duration.ofMillis(500);
  // Port 0 lets the system pick a free port, so the test never collides with a running lab.
  private static final int ANY_FREE_PORT = 0;

  @Test
  void answersAHealthCheckUntilItIsClosed() throws Exception {
    SiteEndpoints sites = SiteEndpoints.start(Map.of(SITE, ANY_FREE_PORT));

    boolean isUpWhileOpen = sites.isHealthy(SITE, TIMEOUT);
    sites.close();

    assertThat(isUpWhileOpen).isTrue();
    assertThat(sites.isHealthy(SITE, TIMEOUT)).isFalse();
  }

  @Test
  void readsTheLiveSettingsAndListsInvalidOnes() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-live.properties"));
    LiveSettings settings =
        LiveSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
    properties.setProperty("server.port", "53");
    properties.setProperty("migration.old.address", "203.0.113.999");

    List<String> problems =
        ((Result.Error<LiveSettings, List<String>>)
                LiveSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(settings.loweredTtlSeconds()).isEqualTo(2);
    assertThat(problems)
        .containsExactly(
            "server.port must be from 1024 to 65535, but was 53", "not an address: 203.0.113.999");
  }
}
