package io.plainround.seatwave.dns.sampling;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.server.DnsServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point of the weighted-routing test: it looks one name up many times through a
 * chosen server and prints the share of answers per address against the configured weights.
 * Everything stays on 127.0.0.1. Exit status 2 means bad usage or settings; 1 means a share fell
 * outside its tolerance or the server could not be started.
 */
public final class SampleCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;

  private SampleCli() {}

  /**
   * Runs the sample.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: sample <sample.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (SampleSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<SampleSettings, List<String>>(SampleSettings settings) -> {
          String report = run(settings);
          System.out.print(report);
          if (report.contains("OUTSIDE")) {
            System.exit(EXIT_FAILED);
          }
        }
        case Result.Error<SampleSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("the sample failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  static String run(SampleSettings settings) throws IOException {
    return switch (settings.target()) {
      case LOCAL -> {
        try (DnsServer server =
            DnsServer.start(
                DnsServer.ANY_FREE_PORT,
                new ZoneStore(settings.zone(), Clock.systemUTC()),
                new SplittableRandom(settings.randomSeed()))) {
          yield header("the lab server", server.port(), settings) + sample(server.port(), settings);
        }
      }
      case EXTERNAL ->
          header("an external server", settings.externalPort(), settings)
              + sample(settings.externalPort(), settings);
    };
  }

  private static String sample(int port, SampleSettings settings) {
    try (DnsClient client = new DnsClient(port, settings.timeout())) {
      if (settings.warmupLookups() > 0) {
        Sampler.sample(client, settings.name(), settings.type(), settings.warmupLookups());
      }
      SampleResult result =
          Sampler.sample(client, settings.name(), settings.type(), settings.lookups());
      return SampleReport.render(result, settings.zone());
    }
  }

  private static String header(String what, int port, SampleSettings settings) {
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s; %s on 127.0.0.1:%d (UDP); %s %s; %d warm-up lookups first%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        what,
        port,
        settings.type(),
        settings.name(),
        settings.warmupLookups());
  }
}
