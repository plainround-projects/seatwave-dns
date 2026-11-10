package io.plainround.seatwave.dns.migration;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.zone.Zone;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The zone a server answers from, and the audit log of every version it has served.
 *
 * <p>The only way to change the zone is {@link #apply}, and it takes only an {@link
 * ApprovedChange}, which only {@link MigrationGuard} can make. A change approved against an older
 * zone is refused, so two approvals can never overwrite each other. Thread-safe: the server reads
 * {@link #current} from its receiving thread while a migration applies a change from another.
 */
public final class ZoneStore {

  private final Clock clock;
  private final List<Publication> history = new ArrayList<>();
  // Read on every query without a lock; written only inside the synchronized apply.
  private volatile Zone current;

  /**
   * Starts serving a zone.
   *
   * @param initial the first zone
   * @param clock the clock that stamps every publication
   */
  public ZoneStore(Zone initial, Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.current = Objects.requireNonNull(initial, "initial");
    history.add(new Publication(initial, clock.instant(), "initial zone"));
  }

  /**
   * Returns the zone being served now.
   *
   * @return the current zone
   */
  public Zone current() {
    return current;
  }

  /**
   * Returns the audit log.
   *
   * @return every publication, oldest first
   */
  public synchronized List<Publication> history() {
    return List.copyOf(history);
  }

  /**
   * Applies an approved change.
   *
   * @param change the change
   * @return the new publication, or a refusal if the zone changed after the approval
   */
  public synchronized Result<Publication, MigrationRefusal> apply(ApprovedChange change) {
    Objects.requireNonNull(change, "change");
    if (change.from() != current) {
      return Result.error(
          new MigrationRefusal.BadRequest(
              "the zone changed after this change was approved; approve it again"));
    }
    Publication publication = new Publication(change.to(), clock.instant(), change.description());
    history.add(publication);
    current = change.to();
    return Result.success(publication);
  }
}
