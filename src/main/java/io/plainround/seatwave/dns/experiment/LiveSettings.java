package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything the live TTL-change run needs, read from a settings file with no defaults. The live
 * run uses seconds where the simulation uses hours, so it ends in about a minute.
 *
 * @param serverPort the lab server's UDP port on 127.0.0.1, where dig can ask it too
 * @param zone the zone at the start
 * @param name the name that moves
 * @param oldAddress the address it moves from
 * @param newAddress the address it moves to
 * @param loweredTtlSeconds the TTL it is lowered to one old TTL before the switch
 * @param oldSitePort the loopback TCP port that stands for the old site
 * @param newSitePort the loopback TCP port that stands for the new site
 * @param healthInterval how often each site is checked
 * @param maxCheckGap the longest gap between healthy checks the guard accepts
 * @param healthTimeout the longest wait for one health check
 * @param pollInterval how often every client looks the name up
 * @param honestClients how many clients honor the TTL
 * @param pinningHold how long the pinning client keeps every answer at least
 * @param jvmCacheTtl the JVM's own cache time for successful lookups, {@code
 *     networkaddress.cache.ttl}
 * @param lookupTimeout the longest wait for each DNS response
 * @param observeAfterSwitch how long to keep watching after the switch
 * @param randomSeed the seed of the server's weighted choice
 */
public record LiveSettings(
    int serverPort,
    Zone zone,
    DomainName name,
    InetAddress oldAddress,
    InetAddress newAddress,
    long loweredTtlSeconds,
    int oldSitePort,
    int newSitePort,
    Duration healthInterval,
    Duration maxCheckGap,
    Duration healthTimeout,
    Duration pollInterval,
    int honestClients,
    Duration pinningHold,
    Duration jvmCacheTtl,
    Duration lookupTimeout,
    Duration observeAfterSwitch,
    long randomSeed) {

  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_MILLIS = 60_000;
  private static final int MAX_SECONDS = 600;
  private static final int MAX_CLIENTS = 20;

  /** Checks the fields. */
  public LiveSettings {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(oldAddress, "oldAddress");
    Objects.requireNonNull(newAddress, "newAddress");
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings
   * @return the settings, or one line per problem
   */
  public static Result<LiveSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int lowest = DnsServer.LOWEST_UNPRIVILEGED_PORT;
    int serverPort = reader.intBetween("server.port", lowest, HIGHEST_PORT);
    String nameText = reader.text("migration.name");
    String oldText = reader.text("migration.old.address");
    String newText = reader.text("migration.new.address");
    int lowered = reader.intBetween("migration.lowered.ttl.seconds", 1, MAX_SECONDS);
    int oldSite = reader.intBetween("site.old.port", lowest, HIGHEST_PORT);
    int newSite = reader.intBetween("site.new.port", lowest, HIGHEST_PORT);
    Duration health = reader.millisBetween("health.interval.millis", 1, MAX_MILLIS);
    Duration gap = reader.millisBetween("health.max.gap.millis", 1, MAX_MILLIS);
    Duration healthTimeout = reader.millisBetween("health.timeout.millis", 1, MAX_MILLIS);
    Duration poll = reader.millisBetween("poll.interval.millis", 1, MAX_MILLIS);
    int honest = reader.intBetween("clients.honest.count", 0, MAX_CLIENTS);
    Duration pin =
        Duration.ofSeconds(reader.intBetween("clients.pinning.hold.seconds", 0, MAX_SECONDS));
    Duration jvm = Duration.ofSeconds(reader.intBetween("jvm.cache.ttl.seconds", 0, MAX_SECONDS));
    Duration lookup = reader.millisBetween("lookup.timeout.millis", 1, MAX_MILLIS);
    Duration observe =
        Duration.ofSeconds(reader.intBetween("observe.after.switch.seconds", 1, MAX_SECONDS));
    long seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
    List<String> problems = new ArrayList<>(reader.problems());
    if (zone instanceof Result.Error<Zone, List<String>>(List<String> zoneProblems)) {
      problems.addAll(zoneProblems);
    }
    ExperimentSettings.Parsed parsed =
        ExperimentSettings.parse(nameText, oldText, newText, problems);
    if (!problems.isEmpty() || !(zone instanceof Result.Success<Zone, List<String>>(Zone loaded))) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(
        new LiveSettings(
            serverPort,
            loaded,
            parsed.name(),
            parsed.oldAddress(),
            parsed.newAddress(),
            lowered,
            oldSite,
            newSite,
            health,
            gap,
            healthTimeout,
            poll,
            honest,
            pin,
            jvm,
            lookup,
            observe,
            seed));
  }
}
