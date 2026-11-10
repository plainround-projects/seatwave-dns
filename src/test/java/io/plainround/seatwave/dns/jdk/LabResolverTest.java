package io.plainround.seatwave.dns.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LabResolverTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final InetAddress IPV4 = TestNames.address("192.0.2.10");
  private static final InetAddress IPV6 = TestNames.address("2001:db8::10");

  @Test
  void resolvesTheLabZoneInTheOrderTheJdkAsksFor() throws Exception {
    try (DnsServer server = start()) {
      LabResolver resolver = new LabResolver(server.port(), TIMEOUT);

      assertThat(resolver.lookupByName("seatwave.example", LookupPolicy.of(LookupPolicy.IPV4)))
          .containsExactly(IPV4);
      assertThat(
              resolver.lookupByName(
                  "www.seatwave.example", LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6)))
          .containsExactly(IPV4, IPV6);
      assertThat(
              resolver.lookupByName(
                  "seatwave.example",
                  LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV6_FIRST)))
          .containsExactly(IPV6, IPV4);
    }
  }

  @Test
  void refusesNamesOutsideTheLabAndNamesThatDoNotExist() throws Exception {
    try (DnsServer server = start()) {
      LabResolver resolver = new LabResolver(server.port(), TIMEOUT);
      LookupPolicy policy = LookupPolicy.of(LookupPolicy.IPV4);

      assertThatThrownBy(() -> resolver.lookupByName("example.com", policy))
          .isInstanceOf(UnknownHostException.class)
          .hasMessage("the lab resolver answers only names under seatwave.example.");
      assertThatThrownBy(() -> resolver.lookupByName("nowhere.seatwave.example", policy))
          .isInstanceOf(UnknownHostException.class);
      assertThatThrownBy(() -> resolver.lookupByName("bad..name", policy))
          .isInstanceOf(UnknownHostException.class);
      assertThatThrownBy(() -> resolver.lookupByAddress(IPV4.getAddress()))
          .isInstanceOf(UnknownHostException.class);
    }
  }

  @Test
  void reportsAServerThatDoesNotAnswer() throws Exception {
    int closedPort;
    try (DnsServer server = start()) {
      closedPort = server.port();
    }
    LabResolver resolver = new LabResolver(closedPort, TIMEOUT);

    assertThatThrownBy(
            () -> resolver.lookupByName("seatwave.example", LookupPolicy.of(LookupPolicy.IPV4)))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageStartingWith("seatwave.example: the server is unreachable");
  }

  @Test
  void namesItselfForTheServiceLoader() {
    // The JDK's provider configuration is a sealed type, so choosing the resolver is shown by the
    // jdkLookup and ttlLive runs, not here.
    assertThat(new LabResolverProvider().name()).isEqualTo("seatwave-lab");
  }

  private static DnsServer start() throws Exception {
    return DnsServer.start(
        DnsServer.ANY_FREE_PORT,
        new ZoneStore(TestZones.seatwave(), Clock.systemUTC()),
        new SplittableRandom(1));
  }
}
