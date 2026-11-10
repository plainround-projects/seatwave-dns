package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point that serves the lab zones on 127.0.0.1 for a fixed time, so a reader can
 * query them with {@code dig @127.0.0.1 -p <port>}. Exit status 2 means bad usage, settings or zone
 * files; 1 means a port could not be bound.
 */
public final class ServeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;

  private ServeCli() {}

  /**
   * Serves the zones named in a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <serve.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (ServeSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<ServeSettings, List<String>>(ServeSettings settings) -> serve(settings);
        case Result.Error<ServeSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("serving failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }

  private static void serve(ServeSettings settings) throws IOException, InterruptedException {
    SplittableRandom seeds = new SplittableRandom(settings.randomSeed());
    List<DnsServer> servers = new ArrayList<>();
    try {
      for (ServeSettings.Listener listener : settings.listeners()) {
        // Each server's thread gets its own generator, split from one seed, so runs repeat.
        servers.add(
            DnsServer.start(
                listener.port(), new ZoneStore(listener.zone(), Clock.systemUTC()), seeds.split()));
      }
      System.out.printf(
          Locale.ROOT, "serving on 127.0.0.1 (UDP) for %d s:%n", settings.serveFor().toSeconds());
      for (ServeSettings.Listener listener : settings.listeners()) {
        System.out.printf(
            Locale.ROOT,
            "  %-9s port %d  zone %-18s %d records%n",
            listener.name(),
            listener.port(),
            listener.zone().origin(),
            listener.zone().records().size());
      }
      Thread.sleep(settings.serveFor());
      for (int index = 0; index < servers.size(); index++) {
        System.out.printf(
            Locale.ROOT,
            "  %-9s answered %d, dropped %d%n",
            settings.listeners().get(index).name(),
            servers.get(index).answered(),
            servers.get(index).dropped());
      }
    } finally {
      servers.forEach(DnsServer::close);
    }
  }
}
