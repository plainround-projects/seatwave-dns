package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of the simulated TTL-change experiment. It prints one row per arm and
 * every guard decision. It is a model, and says so in its first line. Exit status 2 means bad usage
 * or settings.
 */
public final class TtlExperimentCli {

  private static final int EXIT_BAD_USAGE = 2;

  private TtlExperimentCli() {}

  /**
   * Runs every arm.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: ttlExperiment <ttl-experiment.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (ExperimentSettings.from(
          new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<ExperimentSettings, List<String>>(ExperimentSettings settings) ->
            System.out.print(render(settings));
        case Result.Error<ExperimentSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException unreadable) {
      System.err.println("cannot read the settings: " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }

  static String render(ExperimentSettings settings) {
    StringBuilder out = new StringBuilder();
    out.append(
        String.format(
            Locale.ROOT,
            "MODEL, not a measurement. %d caching resolvers, each asked for %s every %d s at a"
                + " random phase (seed %d);%n"
                + "the switch %s -> %s is at 2 old TTLs, a lowering at 1 old TTL; both sites pass"
                + " every health check (every %d s).%n"
                + "Server rate and hit ratio are measured over the %d s before the switch."
                + " Times are seconds after the switch; -1 means never within the run.%n%n",
            settings.resolvers(),
            settings.name(),
            settings.queryInterval().toSeconds(),
            settings.seed(),
            settings.oldAddress().getHostAddress(),
            settings.newAddress().getHostAddress(),
            settings.healthInterval().toSeconds(),
            settings.rateWindow().toSeconds()));
    out.append(
        String.format(
            Locale.ROOT,
            "%-18s %8s %8s %8s %9s %9s %7s %7s %7s %8s %9s %10s%n",
            "arm",
            "TTL s",
            "lowered",
            "pinning",
            "server/s",
            "hit ratio",
            "50% s",
            "95% s",
            "99% s",
            "100% s",
            "retire s",
            "old at ret"));
    List<String> logs = new ArrayList<>();
    for (Arm arm : settings.arms()) {
      ArmResult result = TtlSimulation.run(settings, arm);
      out.append(row(result));
      result.guardLog().forEach(line -> logs.add(arm.name() + ": " + line));
    }
    out.append(String.format(Locale.ROOT, "%nguard decisions:%n"));
    logs.forEach(line -> out.append("  ").append(line).append(System.lineSeparator()));
    return out.toString();
  }

  private static String row(ArmResult result) {
    Arm arm = result.arm();
    return String.format(
        Locale.ROOT,
        "%-18s %8d %8s %7d%% %9.2f %9.4f %7d %7d %7d %8d %9d %9.1f%%%n",
        arm.name(),
        arm.initialTtlSeconds(),
        arm.isLowering() ? Long.toString(arm.loweredTtlSeconds()) : "no",
        arm.pinningPercent(),
        result.serverQueriesPerSecond(),
        result.cacheHitRatio(),
        result.switchSeconds().get(0),
        result.switchSeconds().get(1),
        result.switchSeconds().get(2),
        result.switchSeconds().get(3),
        result.retireAfterSeconds(),
        result.onOldAtRetirePercent());
  }
}
