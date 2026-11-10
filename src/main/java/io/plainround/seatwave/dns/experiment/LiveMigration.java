package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.migration.ApprovedChange;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.MigrationRefusal;
import io.plainround.seatwave.dns.migration.Publication;
import io.plainround.seatwave.dns.migration.ZoneStore;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The loop of the live TTL-change run, on an injected clock and an injected pause, so a test can
 * run it on a {@link SimulatedClock} in milliseconds and the command line on the wall clock.
 *
 * <p>Every tick it checks both sites when a health interval has passed, takes the next migration
 * step the timeline allows, and lets every client look the name up. The timeline: the TTL is
 * lowered at one old TTL after the start, the address is switched at two, the guard is asked at
 * once to retire the old site (and refuses), and asked again when its rule allows. The loop ends
 * when retirement is approved and every client gives the new address, or when the watch time after
 * the switch runs out. Not thread-safe: one run on one thread.
 */
public final class LiveMigration {

  private static final double MILLIS_PER_SECOND = 1_000;

  private final LiveSettings settings;
  private final Parts parts;
  private final Clock clock;
  private final Pause pause;

  /**
   * Prepares a run.
   *
   * @param settings the run's settings
   * @param parts the zone store, health history, guard and site check
   * @param clock the clock
   * @param pause waits between ticks; a simulation advances its clock here
   */
  public LiveMigration(LiveSettings settings, Parts parts, Clock clock, Pause pause) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.parts = Objects.requireNonNull(parts, "parts");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.pause = Objects.requireNonNull(pause, "pause");
  }

  /**
   * Runs the migration and watches the clients.
   *
   * @param clients the clients, in the order they are printed
   * @param log receives every timeline line as it happens
   * @return the seconds after the switch at which each client first gave the new address, by client
   *     name; a client that never did is absent
   * @throws InterruptedException if interrupted while pausing
   */
  public Map<String, Double> run(List<Client> clients, Consumer<String> log)
      throws InterruptedException {
    Instant start = clock.instant();
    long oldTtl = parts.guard().oldTtlWindowSeconds(settings.name());
    Instant lowerAt = start.plusSeconds(oldTtl);
    Instant switchAt = lowerAt.plusSeconds(oldTtl);
    Instant end = switchAt.plus(settings.observeAfterSwitch());
    State state = new State(start, clients.size(), settings.healthInterval());
    while (clock.instant().isBefore(end)) {
      checkSites(state);
      step(state, lowerAt, switchAt, log);
      for (int index = 0; index < clients.size(); index++) {
        watch(clients.get(index), index, state, log);
      }
      boolean isDone = state.hasRetired && state.sawNewAt.size() == clients.size();
      if (isDone) {
        break;
      }
      pause.pause(settings.pollInterval());
    }
    Map<String, Double> seconds = new LinkedHashMap<>();
    state.sawNewAt.forEach(
        (index, at) -> seconds.put(clients.get(index).name(), seconds(state.switchedAt, at)));
    return seconds;
  }

  private void checkSites(State state) {
    Instant now = clock.instant();
    if (now.isBefore(state.lastCheck.plus(settings.healthInterval()))) {
      return;
    }
    state.lastCheck = now;
    for (InetAddress site : List.of(settings.oldAddress(), settings.newAddress())) {
      parts.health().record(now, site, parts.siteCheck().test(site));
    }
  }

  private void step(State state, Instant lowerAt, Instant switchAt, Consumer<String> log) {
    Instant now = clock.instant();
    MigrationGuard guard = parts.guard();
    if (!state.hasLowered && !now.isBefore(lowerAt)) {
      state.hasLowered = true;
      log.accept(
          line(state, apply(guard.changeTtl(settings.name(), settings.loweredTtlSeconds()))));
    }
    if (!state.hasSwitched && !now.isBefore(switchAt)) {
      state.hasSwitched = true;
      state.switchedAt = now;
      log.accept(
          line(
              state,
              apply(
                  guard.switchAddress(
                      settings.name(), settings.oldAddress(), settings.newAddress()))));
      log.accept(line(state, retire(state.start)));
      Optional<Instant> earliest = guard.earliestRetirement(settings.name(), settings.oldAddress());
      state.hasRetireTime = earliest.isPresent();
      state.retireAt = earliest.orElse(now);
    }
    if (!state.hasRetired && state.hasRetireTime && !now.isBefore(state.retireAt)) {
      state.hasRetired = true;
      log.accept(line(state, retire(state.start)));
    }
  }

  private void watch(Client client, int index, State state, Consumer<String> log) {
    String answer = client.lookup().get();
    if (!answer.equals(state.lastAnswers.get(index))) {
      state.lastAnswers.set(index, answer);
      log.accept(
          line(state, String.format(Locale.ROOT, "%-10s now gets %s", client.name(), answer)));
    }
    boolean isNew = state.hasSwitched && answer.equals(settings.newAddress().getHostAddress());
    if (isNew) {
      state.sawNewAt.putIfAbsent(index, clock.instant());
    }
  }

  private String apply(Result<ApprovedChange, MigrationRefusal> decision) {
    return switch (decision) {
      case Result.Success<ApprovedChange, MigrationRefusal>(ApprovedChange change) ->
          switch (parts.store().apply(change)) {
            case Result.Success<Publication, MigrationRefusal> applied ->
                "guard approved, applied: " + change.description();
            case Result.Error<Publication, MigrationRefusal>(MigrationRefusal refusal) ->
                refusal.describe();
          };
      case Result.Error<ApprovedChange, MigrationRefusal>(MigrationRefusal refusal) ->
          refusal.describe();
    };
  }

  private String retire(Instant start) {
    String site = settings.oldAddress().getHostAddress();
    return switch (parts.guard().approveRetirement(settings.name(), settings.oldAddress())) {
      case Result.Success<Instant, MigrationRefusal> approved -> "guard approved: retire " + site;
      case Result.Error<Instant, MigrationRefusal>(
              MigrationRefusal.TooEarly(var earliest, var why)) ->
          String.format(
              Locale.ROOT,
              "retire %s: refused: %s; allowed from t=%.3f s",
              site,
              why,
              seconds(start, earliest));
      case Result.Error<Instant, MigrationRefusal>(MigrationRefusal refusal) ->
          "retire " + site + ": " + refusal.describe();
    };
  }

  private String line(State state, String text) {
    return String.format(Locale.ROOT, "t=%7.3f s  %s", seconds(state.start, clock.instant()), text);
  }

  private static double seconds(Instant from, Instant to) {
    return Duration.between(from, to).toMillis() / MILLIS_PER_SECOND;
  }

  /** Waits between ticks. */
  @FunctionalInterface
  public interface Pause {

    /**
     * Waits.
     *
     * @param duration how long
     * @throws InterruptedException if interrupted while waiting
     */
    void pause(Duration duration) throws InterruptedException;
  }

  /**
   * One client of the run.
   *
   * @param name the name printed for it
   * @param lookup looks the name up once and returns the address it got, or why none
   */
  public record Client(String name, Supplier<String> lookup) {

    /** Checks the fields. */
    public Client {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(lookup, "lookup");
    }
  }

  /**
   * The moving parts a run acts on.
   *
   * @param store the zone being served
   * @param health the health history the guard reads
   * @param guard the migration guard
   * @param siteCheck checks one site, true when it answered
   */
  public record Parts(
      ZoneStore store,
      HealthHistory health,
      MigrationGuard guard,
      Predicate<InetAddress> siteCheck) {

    /** Checks the fields. */
    public Parts {
      Objects.requireNonNull(store, "store");
      Objects.requireNonNull(health, "health");
      Objects.requireNonNull(guard, "guard");
      Objects.requireNonNull(siteCheck, "siteCheck");
    }
  }

  /** The run's progress. Confined to one call of {@link #run}. */
  private static final class State {
    private final Instant start;
    private final List<String> lastAnswers = new ArrayList<>();
    // Client index to the first moment it gave the new address; absent until it does.
    private final Map<Integer, Instant> sawNewAt = new HashMap<>();
    private Instant switchedAt;
    private Instant lastCheck;
    private Instant retireAt;
    private boolean hasLowered;
    private boolean hasSwitched;
    private boolean hasRetireTime;
    private boolean hasRetired;

    State(Instant start, int clients, Duration healthInterval) {
      this.start = start;
      this.switchedAt = start;
      this.retireAt = start;
      // One interval in the past, so the first tick checks both sites at once.
      this.lastCheck = start.minus(healthInterval);
      for (int index = 0; index < clients; index++) {
        lastAnswers.add("");
      }
    }
  }
}
