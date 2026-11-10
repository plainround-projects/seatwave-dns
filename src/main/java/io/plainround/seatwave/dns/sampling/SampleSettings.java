package io.plainround.seatwave.dns.sampling;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the {@code sample} command does, read from a settings file with no defaults.
 *
 * @param name the name to look up
 * @param type A or AAAA
 * @param lookups how many measured lookups
 * @param warmupLookups lookups sent first and not counted, so class loading and the JIT compiler
 *     are not in the latency
 * @param timeout the longest wait for each response
 * @param target whether to start a server in this process or ask one already running
 * @param externalPort the port of the running server, when the target is external
 * @param zone the zone: served when the target is local, and always the source of the configured
 *     weights
 * @param randomSeed the seed of a local server's weighted choice
 */
public record SampleSettings(
    DomainName name,
    RecordType type,
    int lookups,
    int warmupLookups,
    Duration timeout,
    Target target,
    int externalPort,
    Zone zone,
    long randomSeed) {

  private static final int MAX_LOOKUPS = 1_000_000;
  private static final int MAX_TIMEOUT_MILLIS = 10_000;
  private static final int HIGHEST_PORT = 65_535;

  /** Checks the fields. */
  public SampleSettings {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(zone, "zone");
    if (type != RecordType.A && type != RecordType.AAAA) {
      throw new IllegalArgumentException("only A and AAAA answers carry addresses to count");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings
   * @return the settings, or one line per problem
   */
  public static Result<SampleSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String nameText = reader.text("lookup.name");
    RecordType type = reader.enumValue("lookup.type", RecordType.class, RecordType.A);
    int lookups = reader.intBetween("lookup.count", 1, MAX_LOOKUPS);
    int warmups = reader.intBetween("lookup.warmup.count", 0, MAX_LOOKUPS);
    Duration timeout = reader.millisBetween("lookup.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
    Target target = reader.enumValue("server.target", Target.class, Target.LOCAL);
    int port =
        reader.intBetween("server.external.port", DnsServer.LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
    long seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<String> problems = new ArrayList<>();
    DomainName name = DomainName.ROOT;
    switch (DomainName.parse(nameText.isEmpty() ? "." : nameText)) {
      case Result.Success<DomainName, String>(DomainName parsed) -> name = parsed;
      case Result.Error<DomainName, String>(String problem) ->
          problems.add("lookup.name: " + problem);
    }
    Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
    problems.addAll(0, reader.problems());
    if (zone instanceof Result.Error<Zone, List<String>>(List<String> zoneProblems)) {
      problems.addAll(zoneProblems);
    }
    if (type != RecordType.A && type != RecordType.AAAA) {
      problems.add("lookup.type must be a or aaaa");
    }
    if (!problems.isEmpty() || !(zone instanceof Result.Success<Zone, List<String>>(Zone loaded))) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(
        new SampleSettings(name, type, lookups, warmups, timeout, target, port, loaded, seed));
  }

  /** Where the sampled server runs. */
  public enum Target {
    /** Start a lab server in this process, on any free port. */
    LOCAL,
    /** Ask a server already running on 127.0.0.1, for example CoreDNS from the Compose file. */
    EXTERNAL
  }
}
