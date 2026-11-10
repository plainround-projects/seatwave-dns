package io.plainround.seatwave.dns.jdk;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.Security;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point that measures the JDK's own name lookup, {@code InetAddress.getByName},
 * against the lab server through {@link LabResolverProvider}: the first lookup, repeated lookups
 * served by the JVM's cache, and the same for a name that does not exist. It also counts how many
 * queries reached the server, which shows the cache at work. Exit status 2 means bad usage or
 * settings; 1 means the run failed.
 */
public final class JdkLookupCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_LOOKUPS = 100_000;
  private static final int MAX_SECONDS = 600;
  private static final int MAX_TIMEOUT_MILLIS = 10_000;
  private static final double NANOS_PER_MICRO = 1_000;
  // The names looked up here are not weighted, so the seed changes no answer; it only has to be
  // fixed.
  private static final int SEED = 1;

  private JdkLookupCli() {}

  /**
   * Runs the measurement.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: jdkLookup <jdk-lookup.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int port = reader.intBetween("server.port", DnsServer.LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      int cacheSeconds = reader.intBetween("jvm.cache.ttl.seconds", 0, MAX_SECONDS);
      int negativeSeconds = reader.intBetween("jvm.cache.negative.ttl.seconds", 0, MAX_SECONDS);
      int lookups = reader.intBetween("lookup.count", 1, MAX_LOOKUPS);
      int timeoutMillis = reader.intBetween("lookup.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
      String name = reader.text("lookup.name");
      String missing = reader.text("lookup.missing.name");
      Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
      List<String> problems = new ArrayList<>(reader.problems());
      if (zone instanceof Result.Error<Zone, List<String>>(List<String> zoneProblems)) {
        problems.addAll(zoneProblems);
      }
      if (!problems.isEmpty()
          || !(zone instanceof Result.Success<Zone, List<String>>(Zone loaded))) {
        problems.forEach(problem -> System.err.println("invalid: " + problem));
        System.exit(EXIT_BAD_USAGE);
        return;
      }
      // Set before the first lookup: the JVM reads its cache policy and loads providers once.
      Security.setProperty("networkaddress.cache.ttl", Integer.toString(cacheSeconds));
      Security.setProperty("networkaddress.cache.negative.ttl", Integer.toString(negativeSeconds));
      System.setProperty(LabResolverProvider.PORT_PROPERTY, Integer.toString(port));
      System.setProperty(LabResolverProvider.TIMEOUT_PROPERTY, Integer.toString(timeoutMillis));
      try (DnsServer server =
          DnsServer.start(
              port, new ZoneStore(loaded, Clock.systemUTC()), new SplittableRandom(SEED))) {
        System.out.printf(
            Locale.ROOT,
            "Java %s on %s %s; InetAddress.getByName through the lab resolver to 127.0.0.1:%d;"
                + " networkaddress.cache.ttl=%d, networkaddress.cache.negative.ttl=%d%n",
            Runtime.version(),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            port,
            cacheSeconds,
            negativeSeconds);
        System.out.printf(
            Locale.ROOT,
            "%-28s %-14s %8s %12s %12s %15s%n",
            "lookup",
            "result",
            "lookups",
            "first us",
            "median us",
            "server queries");
        System.out.print(measure(server, name, lookups));
        System.out.print(measure(server, missing, lookups));
      }
    } catch (IOException failed) {
      System.err.println("the lookup run failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static String measure(DnsServer server, String name, int lookups) {
    long queriesBefore = server.answered();
    long[] nanos = new long[lookups];
    String result = "";
    for (int lookupIndex = 0; lookupIndex < lookups; lookupIndex++) {
      long started = System.nanoTime();
      result = lookUp(name);
      nanos[lookupIndex] = System.nanoTime() - started;
    }
    long first = nanos[0];
    long[] repeated = Arrays.copyOfRange(nanos, 1, nanos.length);
    Arrays.sort(repeated);
    double median = repeated.length == 0 ? 0 : repeated[repeated.length / 2] / NANOS_PER_MICRO;
    return String.format(
        Locale.ROOT,
        "%-28s %-14s %8d %12.1f %12.1f %15d%n",
        name,
        result,
        lookups,
        first / NANOS_PER_MICRO,
        median,
        server.answered() - queriesBefore);
  }

  private static String lookUp(String name) {
    try {
      return InetAddress.getByName(name).getHostAddress();
    } catch (UnknownHostException noSuchName) {
      // A name that does not exist is an expected result here, and the JVM caches it too.
      return "UnknownHost";
    }
  }
}
