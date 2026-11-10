package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.jdk.LabResolverProvider;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.server.DnsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Command-line entry point of the live TTL-change run: a real UDP server on 127.0.0.1, two site
 * stand-ins with real TCP health checks, and clients that look the name up on the wall clock:
 * honest caching resolvers, one that pins answers longer than the TTL, and the JVM's own {@code
 * InetAddress} with its own cache. {@link LiveMigration} runs the timeline. Exit status 2 means bad
 * usage or settings; 1 means the run failed.
 */
public final class TtlLiveCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  // An honest client keeps an answer for its TTL, up to a day.
  private static final Duration ONE_DAY = Duration.ofDays(1);

  private TtlLiveCli() {}

  /**
   * Runs the live experiment.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: ttlLive <ttl-live.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> run(settings);
        case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("the live run failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static void run(LiveSettings settings) throws IOException, InterruptedException {
    // Both must be set before the first lookup: the JVM reads its cache policy and loads the
    // resolver provider once, at the first lookup.
    Security.setProperty(
        "networkaddress.cache.ttl", Long.toString(settings.jvmCacheTtl().toSeconds()));
    System.setProperty(LabResolverProvider.PORT_PROPERTY, Integer.toString(settings.serverPort()));
    System.setProperty(
        LabResolverProvider.TIMEOUT_PROPERTY, Long.toString(settings.lookupTimeout().toMillis()));
    Clock clock = Clock.systemUTC();
    ZoneStore store = new ZoneStore(settings.zone(), clock);
    HealthHistory health = new HealthHistory();
    Map<InetAddress, Integer> sitePorts = new LinkedHashMap<>();
    sitePorts.put(settings.oldAddress(), settings.oldSitePort());
    sitePorts.put(settings.newAddress(), settings.newSitePort());
    List<DnsClient> sockets = new ArrayList<>();
    try (SiteEndpoints sites = SiteEndpoints.start(sitePorts);
        DnsServer server =
            DnsServer.start(
                settings.serverPort(), store, new SplittableRandom(settings.randomSeed()))) {
      MigrationGuard guard = new MigrationGuard(store, health, clock, settings.maxCheckGap());
      LiveMigration.Parts parts =
          new LiveMigration.Parts(
              store, health, guard, site -> sites.isHealthy(site, settings.healthTimeout()));
      List<LiveMigration.Client> clients = clients(settings, server.port(), clock, sockets);
      System.out.print(header(settings, guard.oldTtlWindowSeconds(settings.name())));
      Map<String, Double> sawNew =
          new LiveMigration(settings, parts, clock, Thread::sleep)
              .run(clients, System.out::println);
      System.out.printf(
          Locale.ROOT,
          "%nfirst answer with %s, seconds after the switch:%n",
          settings.newAddress().getHostAddress());
      for (LiveMigration.Client client : clients) {
        System.out.printf(
            Locale.ROOT,
            "  %-10s %s%n",
            client.name(),
            sawNew.containsKey(client.name())
                ? String.format(Locale.ROOT, "%.3f", sawNew.get(client.name()))
                : "never");
      }
    } finally {
      sockets.forEach(DnsClient::close);
    }
  }

  private static List<LiveMigration.Client> clients(
      LiveSettings settings, int port, Clock clock, List<DnsClient> sockets) {
    List<LiveMigration.Client> clients = new ArrayList<>();
    for (int index = 0; index <= settings.honestClients(); index++) {
      boolean isPinning = index == settings.honestClients();
      DnsClient socket = new DnsClient(port, settings.lookupTimeout());
      sockets.add(socket);
      Duration minimumHold = isPinning ? settings.pinningHold() : Duration.ZERO;
      CachingResolver resolver =
          new CachingResolver(
              socket, clock, new CachePolicy(minimumHold, ONE_DAY, ONE_DAY, Duration.ZERO));
      String name = isPinning ? "pinning" : "honest-" + (index + 1);
      clients.add(new LiveMigration.Client(name, () -> resolverAnswer(resolver, settings.name())));
    }
    clients.add(new LiveMigration.Client("jvm", () -> jvmAnswer(settings.name())));
    return clients;
  }

  private static String resolverAnswer(CachingResolver resolver, DomainName name) {
    List<InetAddress> addresses = resolver.resolve(name, RecordType.A).addresses();
    return addresses.isEmpty() ? "no address" : addresses.getFirst().getHostAddress();
  }

  private static String jvmAnswer(DomainName name) {
    try {
      return InetAddress.getByName(name.toString()).getHostAddress();
    } catch (UnknownHostException failed) {
      return "UnknownHostException " + failed.getMessage();
    }
  }

  private static String header(LiveSettings settings, long oldTtl) {
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s; lab server on 127.0.0.1:%d (UDP); %s A %s, TTL %d s;"
            + " site stand-ins on 127.0.0.1:%d and :%d (TCP)%n"
            + "clients: %d honest, 1 pinning (keeps answers %d s at least), 1 JVM InetAddress"
            + " (networkaddress.cache.ttl=%d); each looks up every %d ms%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        settings.serverPort(),
        settings.name(),
        settings.oldAddress().getHostAddress(),
        oldTtl,
        settings.oldSitePort(),
        settings.newSitePort(),
        settings.honestClients(),
        settings.pinningHold().toSeconds(),
        settings.jvmCacheTtl().toSeconds(),
        settings.pollInterval().toMillis());
  }
}
