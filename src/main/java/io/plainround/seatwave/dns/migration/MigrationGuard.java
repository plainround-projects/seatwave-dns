package io.plainround.seatwave.dns.migration;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.zone.Weights;
import io.plainround.seatwave.dns.zone.Zone;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one place a migration step is decided. It enforces the module's invariant: <b>no record
 * change is applied unless every old and new address answered its health checks for the whole old
 * TTL window, and the old address is never approved for retirement before the old TTL has passed
 * twice after the switch.</b>
 *
 * <p>The old TTL window ends now and is as long as the longest TTL the name has carried since its
 * addresses last changed, because a cache may still hold an answer that old. Retirement waits until
 * twice the time from the switch to the moment the last cached old answer can expire. Every
 * approval is an {@link ApprovedChange}, the only thing {@link ZoneStore#apply} accepts.
 * Thread-safe if the store and the health history are, which they are.
 */
public final class MigrationGuard {

  /** Section 9's rule: keep the old address serving until the old TTL has passed twice. */
  public static final int RETIRE_AFTER_OLD_TTLS = 2;

  private final ZoneStore store;
  private final HealthHistory health;
  private final Clock clock;
  private final Duration maxCheckGap;

  /**
   * Creates a guard over one zone.
   *
   * @param store the zone being served
   * @param health the health checks of every site
   * @param clock the clock that says what "now" is
   * @param maxCheckGap the longest time allowed between two healthy checks of a site
   */
  public MigrationGuard(ZoneStore store, HealthHistory health, Clock clock, Duration maxCheckGap) {
    this.store = Objects.requireNonNull(store, "store");
    this.health = Objects.requireNonNull(health, "health");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maxCheckGap = Objects.requireNonNull(maxCheckGap, "maxCheckGap");
    if (maxCheckGap.isNegative() || maxCheckGap.isZero()) {
      throw new IllegalArgumentException("the allowed gap between checks must be positive");
    }
  }

  /**
   * Approves a new TTL for a name's address records, for example lowering 86,400 seconds to 60
   * before a migration.
   *
   * @param name the name
   * @param newTtlSeconds the new TTL
   * @return the approved change, or why it is refused
   */
  public Result<ApprovedChange, MigrationRefusal> changeTtl(DomainName name, long newTtlSeconds) {
    Zone current = store.current();
    List<InetAddress> addresses = current.addressesOf(name);
    if (addresses.isEmpty()) {
      return Result.error(new MigrationRefusal.BadRequest(name + " has no address records"));
    }
    Optional<MigrationRefusal> unhealthy = holeInWindow(name, addresses);
    if (unhealthy.isPresent()) {
      return Result.error(unhealthy.get());
    }
    List<ResourceRecord> changed =
        current.records().stream()
            .map(record -> isAddressOf(record, name) ? record.withTtl(newTtlSeconds) : record)
            .toList();
    String description =
        name + " TTL " + ttlOf(current, name) + " -> " + newTtlSeconds + " seconds";
    return approve(current, changed, current.weights(), description);
  }

  /**
   * Approves moving a name from one address to another.
   *
   * @param name the name
   * @param oldAddress the address it has now
   * @param newAddress the address it moves to
   * @return the approved change, or why it is refused
   */
  public Result<ApprovedChange, MigrationRefusal> switchAddress(
      DomainName name, InetAddress oldAddress, InetAddress newAddress) {
    Zone current = store.current();
    List<InetAddress> addresses = current.addressesOf(name);
    if (!addresses.contains(oldAddress) || addresses.contains(newAddress)) {
      return Result.error(
          new MigrationRefusal.BadRequest(
              name
                  + " must have "
                  + oldAddress.getHostAddress()
                  + " and not yet "
                  + newAddress.getHostAddress()));
    }
    if ((oldAddress instanceof Inet4Address) != (newAddress instanceof Inet4Address)) {
      return Result.error(
          new MigrationRefusal.BadRequest("the old and new addresses must be of one family"));
    }
    Optional<MigrationRefusal> unhealthy = holeInWindow(name, List.of(oldAddress, newAddress));
    if (unhealthy.isPresent()) {
      return Result.error(unhealthy.get());
    }
    List<ResourceRecord> changed =
        current.records().stream()
            .map(record ->
                isAddressOf(record, name)
                        && record.data() instanceof RecordData.Address(var address)
                        && address.equals(oldAddress)
                    ? new ResourceRecord(
                        name,
                        record.type(),
                        record.ttlSeconds(),
                        new RecordData.Address(newAddress))
                    : record)
            .toList();
    String description =
        name + " " + oldAddress.getHostAddress() + " -> " + newAddress.getHostAddress();
    return approve(
        current,
        changed,
        current.weights().withAddressReplaced(name, oldAddress, newAddress),
        description);
  }

  /**
   * Approves turning off the machine behind an old address, only once caches can no longer send
   * anyone there by the TTL rules, with the margin of {@link #RETIRE_AFTER_OLD_TTLS}.
   *
   * @param name the name the address was published under
   * @param oldAddress the old address
   * @return the moment of approval, or why it is refused
   */
  public Result<Instant, MigrationRefusal> approveRetirement(
      DomainName name, InetAddress oldAddress) {
    Optional<Instant> earliest = earliestRetirement(name, oldAddress);
    if (earliest.isEmpty()) {
      return Result.error(
          new MigrationRefusal.BadRequest(
              oldAddress.getHostAddress() + " is still published for " + name + ", or never was"));
    }
    Instant now = clock.instant();
    if (now.isBefore(earliest.get())) {
      return Result.error(
          new MigrationRefusal.TooEarly(
              earliest.get(),
              "caches may still send fans to "
                  + oldAddress.getHostAddress()
                  + "; keep it serving"));
    }
    return Result.success(now);
  }

  /**
   * Computes when an old address may be retired: the switch time plus twice the time from the
   * switch until the last answer that named the old address can expire from a cache.
   *
   * @param name the name
   * @param oldAddress the old address
   * @return the earliest moment, or empty if the address is still published or never was
   */
  public Optional<Instant> earliestRetirement(DomainName name, InetAddress oldAddress) {
    List<Publication> history = store.history();
    int lastWithOld = -1;
    for (int index = 0; index < history.size(); index++) {
      if (history.get(index).zone().addressesOf(name).contains(oldAddress)) {
        lastWithOld = index;
      }
    }
    if (lastWithOld < 0 || lastWithOld == history.size() - 1) {
      return Optional.empty();
    }
    Instant switchedAt = history.get(lastWithOld + 1).from();
    Instant lastExpiry = switchedAt;
    for (int index = 0; index <= lastWithOld; index++) {
      Publication publication = history.get(index);
      if (publication.zone().addressesOf(name).contains(oldAddress)) {
        Instant ended = history.get(index + 1).from();
        Instant expiry = ended.plusSeconds(ttlOf(publication.zone(), name));
        lastExpiry = expiry.isAfter(lastExpiry) ? expiry : lastExpiry;
      }
    }
    Duration oldTtl = Duration.between(switchedAt, lastExpiry);
    return Optional.of(switchedAt.plus(oldTtl.multipliedBy(RETIRE_AFTER_OLD_TTLS)));
  }

  /**
   * Returns the old TTL window of a name: the longest TTL it has carried since its addresses last
   * changed.
   *
   * @param name the name
   * @return the window in seconds
   */
  public long oldTtlWindowSeconds(DomainName name) {
    List<Publication> history = store.history();
    Set<InetAddress> addresses = new HashSet<>(store.current().addressesOf(name));
    long longest = 0;
    for (int index = history.size() - 1; index >= 0; index--) {
      Zone zone = history.get(index).zone();
      if (!new HashSet<>(zone.addressesOf(name)).equals(addresses)) {
        break;
      }
      longest = Math.max(longest, ttlOf(zone, name));
    }
    return longest;
  }

  private Optional<MigrationRefusal> holeInWindow(DomainName name, List<InetAddress> sites) {
    Instant now = clock.instant();
    Instant from = now.minusSeconds(oldTtlWindowSeconds(name));
    for (InetAddress site : sites) {
      Optional<String> hole = health.holeIn(site, from, now, maxCheckGap);
      if (hole.isPresent()) {
        return Optional.of(new MigrationRefusal.UnhealthySite(site, hole.get()));
      }
    }
    return Optional.empty();
  }

  private static Result<ApprovedChange, MigrationRefusal> approve(
      Zone current, List<ResourceRecord> records, Weights weights, String description) {
    return switch (current.with(records, weights)) {
      case Result.Success<Zone, List<String>>(Zone changed) ->
          Result.success(new ApprovedChange(current, changed, description));
      case Result.Error<Zone, List<String>>(List<String> problems) ->
          Result.error(new MigrationRefusal.BadRequest(String.join("; ", problems)));
    };
  }

  private static boolean isAddressOf(ResourceRecord record, DomainName name) {
    return record.owner().equals(name)
        && (record.type() == RecordType.A || record.type() == RecordType.AAAA);
  }

  private static long ttlOf(Zone zone, DomainName name) {
    return zone.records().stream()
        .filter(record -> isAddressOf(record, name))
        .mapToLong(ResourceRecord::ttlSeconds)
        .max()
        .orElse(0);
  }
}
