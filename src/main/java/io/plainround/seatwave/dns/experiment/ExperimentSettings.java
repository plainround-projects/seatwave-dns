package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything the simulated TTL-change experiment needs, read from a settings file with no defaults.
 *
 * @param zone the zone at the start
 * @param name the name that moves
 * @param oldAddress the address it moves from
 * @param newAddress the address it moves to
 * @param resolvers how many caching resolvers ask for the name
 * @param queryInterval how often each resolver is asked by its fans
 * @param healthInterval how often each site's health is checked
 * @param maxCheckGap the longest gap between healthy checks the guard accepts
 * @param maximumHold the longest time an honest resolver keeps an answer
 * @param maximumNegativeHold the longest time a resolver keeps a negative answer
 * @param rateWindow the window before the switch over which the server's query rate is measured
 * @param seed the seed of every random choice, so a run repeats exactly
 * @param arms the arms, in order
 */
public record ExperimentSettings(
    Zone zone,
    DomainName name,
    InetAddress oldAddress,
    InetAddress newAddress,
    int resolvers,
    Duration queryInterval,
    Duration healthInterval,
    Duration maxCheckGap,
    Duration maximumHold,
    Duration maximumNegativeHold,
    Duration rateWindow,
    long seed,
    List<Arm> arms) {

  private static final Pattern ARM_NAME = Pattern.compile("[a-z][a-z0-9-]{0,29}");
  private static final int MAX_RESOLVERS = 100_000;
  private static final int MAX_SECONDS = 604_800;
  private static final int MAX_TTL_SECONDS = Integer.MAX_VALUE;
  private static final int ALL_PERCENT = 100;

  /** Checks and copies the fields. */
  public ExperimentSettings {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(oldAddress, "oldAddress");
    Objects.requireNonNull(newAddress, "newAddress");
    arms = List.copyOf(arms);
    if (arms.isEmpty() || resolvers < 1) {
      throw new IllegalArgumentException("at least one arm and one resolver are needed");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings
   * @return the settings, or one line per problem
   */
  public static Result<ExperimentSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String nameText = reader.text("migration.name");
    String oldText = reader.text("migration.old.address");
    String newText = reader.text("migration.new.address");
    int resolvers = reader.intBetween("resolvers.count", 1, MAX_RESOLVERS);
    Duration interval = seconds(reader, "resolvers.query.interval.seconds");
    Duration healthInterval = seconds(reader, "health.interval.seconds");
    Duration maxGap = seconds(reader, "health.max.gap.seconds");
    Duration maximumHold = seconds(reader, "resolvers.maximum.hold.seconds");
    Duration maximumNegative = seconds(reader, "resolvers.maximum.negative.hold.seconds");
    Duration window = seconds(reader, "rate.window.seconds");
    long seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<Arm> arms = new ArrayList<>();
    List<String> armNames =
        reader.wordsIn(
            "arm.names",
            List.of(reader.text("arm.names").split(" *, *")).stream()
                .filter(armName -> ARM_NAME.matcher(armName).matches())
                .toList());
    List<String> problems = new ArrayList<>();
    for (String armName : armNames) {
      String prefix = "arm." + armName + ".";
      int initial = reader.intBetween(prefix + "ttl.seconds", 1, MAX_TTL_SECONDS);
      int lowered = reader.intBetween(prefix + "lowered.ttl.seconds", 1, MAX_TTL_SECONDS);
      int pinning = reader.intBetween(prefix + "pinning.percent", 0, ALL_PERCENT);
      int hold = reader.intBetween(prefix + "pinning.hold.seconds", 0, MAX_TTL_SECONDS);
      if (lowered > initial) {
        problems.add(prefix + "lowered.ttl.seconds must not exceed " + prefix + "ttl.seconds");
      } else {
        arms.add(new Arm(armName, initial, lowered, pinning, hold));
      }
    }
    Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
    List<String> all = new ArrayList<>(reader.problems());
    all.addAll(problems);
    if (zone instanceof Result.Error<Zone, List<String>>(List<String> zoneProblems)) {
      all.addAll(zoneProblems);
    }
    Parsed parsed = parse(nameText, oldText, newText, all);
    if (!all.isEmpty() || !(zone instanceof Result.Success<Zone, List<String>>(Zone loaded))) {
      return Result.error(List.copyOf(all));
    }
    if (!loaded.addressesOf(parsed.name()).contains(parsed.oldAddress())) {
      return Result.error(List.of("the zone does not give " + parsed.name() + " the old address"));
    }
    return Result.success(
        new ExperimentSettings(
            loaded,
            parsed.name(),
            parsed.oldAddress(),
            parsed.newAddress(),
            resolvers,
            interval,
            healthInterval,
            maxGap,
            maximumHold,
            maximumNegative,
            window,
            seed,
            arms));
  }

  static Parsed parse(String nameText, String oldText, String newText, List<String> problems) {
    DomainName name = DomainName.ROOT;
    if (!nameText.isEmpty()) {
      switch (DomainName.parse(nameText)) {
        case Result.Success<DomainName, String>(DomainName parsed) -> name = parsed;
        case Result.Error<DomainName, String>(String problem) ->
            problems.add("migration.name: " + problem);
      }
    }
    return new Parsed(name, address(oldText, problems), address(newText, problems));
  }

  private static InetAddress address(String text, List<String> problems) {
    if (text.isEmpty()) {
      // The reader has already recorded the missing key; the stand-in is never used.
      return InetAddress.getLoopbackAddress();
    }
    try {
      // ofLiteral never looks a name up.
      return InetAddress.ofLiteral(text);
    } catch (IllegalArgumentException notAnAddress) {
      problems.add("not an address: " + text);
      return InetAddress.getLoopbackAddress();
    }
  }

  private static Duration seconds(SettingsReader reader, String key) {
    return Duration.ofSeconds(reader.intBetween(key, 1, MAX_SECONDS));
  }

  /**
   * The three migration values once parsed.
   *
   * @param name the name that moves
   * @param oldAddress the address it moves from
   * @param newAddress the address it moves to
   */
  record Parsed(DomainName name, InetAddress oldAddress, InetAddress newAddress) {}
}
