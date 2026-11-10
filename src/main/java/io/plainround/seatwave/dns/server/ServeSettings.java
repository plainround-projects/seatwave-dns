package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the {@code serve} command runs: one listener per zone, each on its own port, for a fixed
 * time. Read from a settings file with no defaults.
 *
 * @param listeners the listeners, in file order
 * @param serveFor how long to serve before stopping
 * @param randomSeed the seed of the generator that picks weighted answers, so a run can be repeated
 *     exactly
 */
public record ServeSettings(List<Listener> listeners, Duration serveFor, long randomSeed) {

  private static final Pattern LISTENER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");
  private static final int MAX_SERVE_SECONDS = 3_600;
  private static final int HIGHEST_PORT = 65_535;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if there is no listener or two share a port
   */
  public ServeSettings {
    listeners = List.copyOf(listeners);
    Objects.requireNonNull(serveFor, "serveFor");
    if (listeners.isEmpty()) {
      throw new IllegalArgumentException("at least one listener is needed");
    }
    if (listeners.stream().map(Listener::port).distinct().count() != listeners.size()) {
      throw new IllegalArgumentException("two listeners share a port");
    }
  }

  /**
   * Reads the settings and loads every zone they name.
   *
   * @param reader the settings
   * @return the settings, or one line per problem in the settings or in a zone
   */
  public static Result<ServeSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    List<String> names =
        reader.wordsIn(
            "listeners",
            List.of(reader.text("listeners").split(" *, *")).stream()
                .filter(name -> LISTENER_NAME.matcher(name).matches())
                .toList());
    int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
    long seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<Listener> listeners = new ArrayList<>();
    List<String> zoneProblems = new ArrayList<>();
    for (String name : names) {
      int port =
          reader.intBetween(
              "listener." + name + ".port", DnsServer.LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      switch (ZoneLoader.load(reader, "listener." + name + ".zone")) {
        case Result.Success<Zone, List<String>>(Zone zone) ->
            listeners.add(new Listener(name, port, zone));
        case Result.Error<Zone, List<String>>(List<String> problems) ->
            problems.forEach(problem -> zoneProblems.add(name + ": " + problem));
      }
    }
    List<String> problems = new ArrayList<>(reader.problems());
    problems.addAll(zoneProblems);
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(new ServeSettings(listeners, Duration.ofSeconds(seconds), seed));
  }

  /**
   * One zone on one port.
   *
   * @param name the listener's name in the settings
   * @param port the UDP port on 127.0.0.1
   * @param zone the zone it serves
   */
  public record Listener(String name, int port, Zone zone) {

    /** Checks the fields. */
    public Listener {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(zone, "zone");
    }
  }
}
