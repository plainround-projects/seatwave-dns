# The `migration` package, line by line

## What it is

This file reads the Java code of the `migration` package, one line at a time. The package has six files: `ZoneStore.java`, `ApprovedChange.java`, `MigrationGuard.java`, `HealthHistory.java`, `Publication.java` and `MigrationRefusal.java`. Together they decide **whether a change to the served zone is allowed**, and they hold the zone the server answers from.

A **migration** here means moving a name from one address to another: SeatWave moves from the machine at `192.0.2.10` to a new one at `203.0.113.30`. A **guard** is code that checks a rule before it lets something happen. An **audit log** is a record of every change, in order, with the time.

## Why this repository uses it

Module 6 says that moving a name safely needs two rules.

1. **Do not change a record unless both the old and the new site have answered their health checks for the whole old TTL window.** The old TTL window is as long as the longest TTL the name has carried since its addresses last changed, because a cache may still hold an answer that old. If the new site was down in that window, some fans would be sent to a dead address; if the old site was down, fans would lose service during the move.
2. **Do not approve turning off the old machine until the old TTL has passed twice after the switch.** Caches that hold the old address (and some that hold it longer than they should) must have run out first.

A rule written in a document is easy to break. This package makes it **impossible to break from the code**: the only way to change the served zone is a method that accepts only an object that the guard creates after checking the rule. The next sections show how.

## Where it appears

| File | What it does |
|---|---|
| `migration/ZoneStore.java` | Holds the zone being served and the audit log. The only way to change the zone is `apply`, which takes an `ApprovedChange`. |
| `migration/ApprovedChange.java` | A change the guard has approved. Its constructor is package-private. |
| `migration/MigrationGuard.java` | Decides each step: lower a TTL, switch an address, approve retirement. |
| `migration/HealthHistory.java` | Every health check of every site, and the question "was this site healthy for the whole window?". |
| `migration/Publication.java` | One version of the zone in the audit log: the zone, when it started, and why. |
| `migration/MigrationRefusal.java` | Why the guard said no, as values. |

Used by `server` (`DnsServer` reads `ZoneStore.current()`), `resolver` (`InMemoryUpstream`), `experiment` (`TtlSimulation`, `LiveMigration`, `TtlLiveCli`) and the tests.

## How it works underneath

**The steps of a migration, in time** (the numbers are from the simulated experiment with a one-hour TTL lowered to 60 seconds; the README shows the same lines):

| Time (seconds from the start) | What happens |
|---|---|
| 0 | The zone is published with a TTL of 3600. This is the first entry of the audit log. |
| 3600 | The guard approves lowering the TTL to 60 (both sites were healthy for the 3600-second window). The store applies it. |
| 7200 | The guard approves switching the address (both sites healthy for the window, which is still 3600 because the old TTL of 3600 is in the history). The store applies it. |
| 7201 | The guard is asked to approve retiring the old address. It refuses: allowed from 7320. |
| 7320 | Twice the time from the switch (7200) to the moment the last old answer can expire (7260) is 120 seconds; so retirement is allowed from 7200 + 120 = 7320. |

Why 7260? The guard looks at every version of the zone that held the old address. The first version (TTL 3600) ended at second 3600, when the lowering was applied, so answers taken from it can live until 3600 + 3600 = 7200. The second version (TTL 60) ended at second 7200, the switch, so answers taken from it can live until 7200 + 60 = 7260. The latest of these is 7260.

**Why this is "impossible to bypass".** Java has four access levels. `public` means any class may use it. `private` means only the same class. **Package-private** (no keyword) means only classes in the same package. `ApprovedChange` has a package-private constructor, and the only class in the package that calls it is `MigrationGuard`. `ZoneStore.apply` is public but takes an `ApprovedChange` and nothing else. So code outside the package cannot make an `ApprovedChange`, and cannot change the zone without one. The compiler enforces it. I tried it in a scratch file outside the package:

```text
Forge.java:4: error: ApprovedChange(Zone,Zone,String) is not public in ApprovedChange; cannot be accessed from outside package
    ApprovedChange fake = new ApprovedChange(null, null, "trust me");
                          ^
1 error
```

This is a rule of the compiler. It does not stop code that uses reflection (a way to call hidden members at run time), and it does not stop a class that declares itself in the same package name. A real system would add a module boundary. For this lab the compiler's check is the point of the lesson.

**A change approved against an old zone is refused.** The approval remembers the zone it was made against (`from`). `apply` compares it with the zone that is current now. If another change was applied in between, the comparison fails and nothing is changed, so two approvals can never overwrite each other.

**Threads.** The server's receiving thread reads the zone for every packet while a migration may apply a change from another thread. The zone field is `volatile` and `apply` is `synchronized`; see `ZoneStore` below.

## Walkthrough of this repository's use

### `migration/ApprovedChange.java`

```java
package io.plainround.seatwave.dns.migration;

import io.plainround.seatwave.dns.zone.Zone;
import java.util.Objects;

/**
 * A zone change the {@link MigrationGuard} has approved. Its constructor is package-private, so no
 * code outside this package can make one, and {@link ZoneStore#apply} accepts nothing else: that is
 * what makes the guard impossible to bypass. Immutable.
 */
public final class ApprovedChange {
```

- The imports are the zone and `Objects`. The Javadoc states the point of the class: its constructor is package-private, so no code outside this package can make one, and `ZoneStore.apply` accepts nothing else. That is what makes the guard impossible to bypass.
- The class is `final` and immutable: all fields are `final`.

```java
  private final Zone from;
  private final Zone to;
  private final String description;

  ApprovedChange(Zone from, Zone to, String description) {
    this.from = Objects.requireNonNull(from, "from");
    this.to = Objects.requireNonNull(to, "to");
    this.description = Objects.requireNonNull(description, "description");
  }
```

- Three fields: the zone the change was approved against (`from`), the zone after the change (`to`), and a description for the audit log.
- `ApprovedChange(Zone from, Zone to, String description)` has no access keyword. It is package-private. `this.from = Objects.requireNonNull(from, "from")` assigns the field and checks for null in one step.

```java
  /**
   * Returns the zone the change was approved against.
   *
   * @return the zone that must still be current when the change is applied
   */
  public Zone from() {
    return from;
  }

  /**
   * Returns the zone after the change.
   *
   * @return the new zone
   */
  public Zone to() {
    return to;
  }

  /**
   * Returns what the change does, for the audit log.
   *
   * @return for example "seatwave.example. A 192.0.2.10 -> 203.0.113.30"
   */
  public String description() {
    return description;
  }
}
```

- Three public accessors. `from()` is the zone that must still be current when the change is applied; `to()` is the new zone; `description()` is for the audit log, for example "seatwave.example. A 192.0.2.10 -> 203.0.113.30".

### `migration/Publication.java`

```java
package io.plainround.seatwave.dns.migration;

import io.plainround.seatwave.dns.zone.Zone;
import java.time.Instant;
import java.util.Objects;

/**
 * One version of a zone in the audit log: the zone, when it started being served, and why.
 *
 * @param zone the zone served from that moment
 * @param from when the server started serving it
 * @param description the change that produced it
 */
public record Publication(Zone zone, Instant from, String description) {

  /** Checks the fields. */
  public Publication {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(description, "description");
  }
}
```

- A record with three components: the zone, the moment it started being served (`Instant`), and why. The compact constructor refuses nulls. The audit log is a list of these.

### `migration/MigrationRefusal.java`

```java
package io.plainround.seatwave.dns.migration;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

/** Why the migration guard refused a step. Every variant is an expected outcome, not a bug. */
public sealed interface MigrationRefusal {

  /**
   * Says why, in one line.
   *
   * @return the reason
   */
  String describe();
```

- A sealed interface with three variants, each with a `describe()` text. The Javadoc says that every variant is an expected outcome, not a bug.

```java
  /**
   * A site did not answer its health checks for the whole window the step needs.
   *
   * @param site the address of the site
   * @param reason the failed or missing check
   */
  record UnhealthySite(InetAddress site, String reason) implements MigrationRefusal {

    /** Checks the fields. */
    public UnhealthySite {
      Objects.requireNonNull(site, "site");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason;
    }
  }
```

- `UnhealthySite` means a site did not answer its health checks for the whole window the step needs. It holds the site's address and the failed or missing check. Its `describe()` starts with `refused:`.

```java
  /**
   * The step is allowed, but not yet.
   *
   * @param earliest the first moment it will be allowed
   * @param reason why it must wait
   */
  record TooEarly(Instant earliest, String reason) implements MigrationRefusal {

    /** Checks the fields. */
    public TooEarly {
      Objects.requireNonNull(earliest, "earliest");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason + "; allowed from " + earliest;
    }
  }
```

- `TooEarly` means the step is allowed, but not yet. It holds the first moment it will be allowed (`earliest`) and why it must wait. Its text ends with `allowed from` and the time.

```java
  /**
   * The step does not make sense for the zone as it is now.
   *
   * @param reason what does not fit
   */
  record BadRequest(String reason) implements MigrationRefusal {

    /** Checks the field. */
    public BadRequest {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason;
    }
  }
```

- `BadRequest` means the step does not make sense for the zone as it is now (for example, the old address is not in the zone).

### `migration/HealthHistory.java`

```java
package io.plainround.seatwave.dns.migration;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Every health check of every site, so the guard can ask "did this site answer for the whole
 * window?". Thread-safe: a checker thread records while the guard reads.
 */
public final class HealthHistory {

  private final List<Check> checks = new ArrayList<>();
```

- A **health check** is a test that a site is alive: here, a TCP connection to it that must open within a timeout. `HealthHistory` is the log of every check of every site, kept in a list.

```java
  /**
   * Records one health check.
   *
   * @param at when the check finished
   * @param site the address of the site checked
   * @param isHealthy whether the site answered in time
   */
  public synchronized void record(Instant at, InetAddress site, boolean isHealthy) {
    checks.add(
        new Check(
            Objects.requireNonNull(at, "at"), Objects.requireNonNull(site, "site"), isHealthy));
  }
```

- `record(at, site, isHealthy)` adds one check. `synchronized` means only one thread at a time may run any synchronized method of this object. A checker thread records while the guard reads, and the lock keeps the list consistent. (`Check` is a private record defined at the end.)

```java
  /**
   * Looks for the first hole in a site's health over a window: a failed check inside it, or a
   * stretch longer than the allowed gap with no healthy check. Every moment of the window must have
   * a healthy check at most one gap before it.
   *
   * @param site the site
   * @param from the start of the window
   * @param to the end of the window
   * @param maxGap the longest time allowed between two healthy checks
   * @return the hole, described in one line, or empty if the site was healthy throughout
   */
  public synchronized Optional<String> holeIn(
      InetAddress site, Instant from, Instant to, Duration maxGap) {
    List<Check> relevant =
        checks.stream()
            .filter(check -> check.site().equals(site))
            .filter(check -> !check.at().isBefore(from.minus(maxGap)) && !check.at().isAfter(to))
            .sorted(Comparator.comparing(Check::at))
            .toList();
    String siteText = site.getHostAddress();
    Optional<Instant> lastHealthy = Optional.empty();
    for (Check check : relevant) {
      if (!check.isHealthy()) {
        if (!check.at().isBefore(from)) {
          return Optional.of(siteText + " failed its health check at " + check.at());
        }
        continue;
      }
      Instant coveredUntil = lastHealthy.map(last -> last.plus(maxGap)).orElse(from);
      if (check.at().isAfter(coveredUntil)) {
        return Optional.of(
            siteText + " has no healthy check between " + coveredUntil + " and " + check.at());
      }
      lastHealthy = Optional.of(check.at());
    }
    if (lastHealthy.isEmpty()) {
      return Optional.of(siteText + " has no healthy check between " + from + " and " + to);
    }
    if (to.isAfter(lastHealthy.get().plus(maxGap))) {
      return Optional.of(siteText + " has no healthy check since " + lastHealthy.get());
    }
    return Optional.empty();
  }
```

- `holeIn` looks for the first **hole** in a site's health over a window: a failed check inside it, or a stretch longer than the allowed gap with no healthy check. In plain words: at every moment of the window, there must be a healthy check at most one gap earlier.
- `relevant` keeps the checks of this site that are no earlier than `from - maxGap` and no later than `to`, sorted by time. The stream steps are `filter`, `filter`, `sorted(Comparator.comparing(Check::at))`, `toList()`. A check just before the window counts, because it still covers the start.
- The loop goes through the checks in time order, remembering the last healthy time:
  - A failed check inside the window (not before `from`) is a hole: the answer names the site and the time. A failed check before the window is ignored (`continue`).
  - For a healthy check, `coveredUntil` is the previous healthy check plus the gap, or `from` if there was none. If this check is later than that, nobody vouched for the time between: a hole.
  - Otherwise this check becomes the last healthy one.
- After the loop: no healthy check at all is a hole, and so is a last healthy check older than one gap before the end of the window (`to`).
- `Optional<String>` is the result: the hole in one line, or empty when the site was healthy throughout.

```java
  private record Check(Instant at, InetAddress site, boolean isHealthy) {}
}
```

- `Check` is a private nested record of the time, the site and the result.

### `migration/ZoneStore.java`

```java
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
```

- The Javadoc: the zone a server answers from, and the audit log of every version it has served. The only way to change the zone is `apply`, which takes only an `ApprovedChange`.

```java
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
```

- The fields: a clock (to stamp each publication), the history, and the current zone.
- `volatile` on `current` means every read sees the latest write. Without it, a thread could keep reading an old copy kept in a processor's cache. The comment says it is read on every query without a lock, and written only inside the synchronized `apply`.
- The constructor stores the first zone and adds the first publication, "initial zone", stamped with the clock.

```java
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
```

- `current()` returns the zone being served. `history()` returns a copy of the audit log, oldest first; it is `synchronized` because the list can change while it is copied, and `List.copyOf` makes the copy unmodifiable.

```java
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
```

- `apply` is the only way to change the zone. It is `synchronized`, so two threads cannot apply changes at the same moment.
- `change.from() != current` compares the **identity** of the two zone objects (`!=` on objects compares whether they are the same object). If the zone changed after the approval, the change is refused with a `BadRequest` and nothing is changed.
- Otherwise it makes a `Publication`, stamped with the clock, adds it to the history, and only then sets `current`. The server's thread sees the new zone from its next packet on.

### `migration/MigrationGuard.java`

```java
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
```

- Imports: the message types, `Weights` and `Zone`, the family class `Inet4Address`, and the time classes. `Clock` is the source of "now".

```java
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
```

- The Javadoc is the invariant, in one sentence: no record change is applied unless every old and new address answered its health checks for the whole old TTL window, and the old address is never approved for retirement before the old TTL has passed twice after the switch. Every approval is an `ApprovedChange`.
- `RETIRE_AFTER_OLD_TTLS` is 2. The four fields are the store, the health history, the clock and the largest gap allowed between two healthy checks.

```java
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
```

- The constructor refuses nulls and a gap that is zero or negative (`isNegative()` and `isZero()` are `Duration` methods).

```java
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
```

- `changeTtl` approves a new TTL for a name's address records, for example lowering 86,400 seconds to 60 before a migration.
- It reads the current zone and the name's addresses. A name with no address records is a `BadRequest`.
- `holeInWindow` checks the health of every address over the window. If there is a hole, the answer is an error holding the refusal.
- `changed` is the list of all records, with the TTL replaced on the name's A and AAAA records: `map(record -> isAddressOf(record, name) ? record.withTtl(newTtlSeconds) : record)`.
- The description records the old and new TTL: `ttlOf(current, name)` is the highest TTL the name's address records have now.
- `approve` validates the changed zone and builds the `ApprovedChange`.

```java
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
```

- `switchAddress` approves moving a name from `oldAddress` to `newAddress`.
- Refused as a `BadRequest` when the name does not have the old address or already has the new one, and when the two addresses are of different families (`instanceof Inet4Address` tested for both and compared with `!=`: an IPv4 address cannot replace an IPv6 one in an A record).
- It checks the health of **both** addresses over the window.
- `changed` replaces, in each address record of the name that holds the old address, the data with the new address, keeping the TTL and type. The condition uses a record pattern in `instanceof`: `record.data() instanceof RecordData.Address(var address) && address.equals(oldAddress)`.
- The weights file keeps working: `current.weights().withAddressReplaced(name, oldAddress, newAddress)` gives the new address the old one's weight.

```java
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
```

- `approveRetirement` approves turning off the machine behind the old address. `earliestRetirement` gives the first moment. If it is empty, the address is still published (or never was): a `BadRequest`.
- If `now` is before that moment, the answer is `TooEarly` with the moment and the reason "caches may still send fans to ... keep it serving". Otherwise it returns the moment of approval.

```java
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
```

- `earliestRetirement` is the arithmetic of the retirement rule: the switch time plus twice the time from the switch until the last cached answer that named the old address can expire.
- It scans the history for the last version that held the old address (`lastWithOld`). If none, or it is the current zone (the address is still published), the answer is empty.
- `switchedAt` is the start of the version after it. Then for each version up to `lastWithOld` that held the old address: it ended when the next version started, and its answers can live until `ended` plus the TTL of that version. `lastExpiry` is the latest of these.
- `oldTtl` is the time from the switch to `lastExpiry`; the earliest retirement is the switch plus `oldTtl` times 2. `multipliedBy` multiplies a `Duration` by a whole number.

```java
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
```

- `oldTtlWindowSeconds` is the **old TTL window**: the longest TTL the name has carried since its addresses last changed. It walks the history from the newest version backward while the set of addresses equals the current set (`HashSet` equality ignores order), and takes the largest TTL. The loop stops at the first version with different addresses (`break`).

```java
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
```

- `holeInWindow` makes the window end now and start `oldTtlWindowSeconds` before now, and asks the health history about each site. The first hole becomes an `UnhealthySite` refusal.

```java
  private static Result<ApprovedChange, MigrationRefusal> approve(
      Zone current, List<ResourceRecord> records, Weights weights, String description) {
    return switch (current.with(records, weights)) {
      case Result.Success<Zone, List<String>>(Zone changed) ->
          Result.success(new ApprovedChange(current, changed, description));
      case Result.Error<Zone, List<String>>(List<String> problems) ->
          Result.error(new MigrationRefusal.BadRequest(String.join("; ", problems)));
    };
  }
```

- `approve` builds the changed zone with `current.with(records, weights)`, which checks it like any other zone. If it is valid, the guard makes the `ApprovedChange` (the only place in the program where one is made); if not, the problems are joined in one `BadRequest`. A migration therefore cannot produce an invalid zone either.

```java
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
```

- `isAddressOf` tests whether a record is an A or AAAA of the name. `ttlOf` is the highest TTL among the name's address records (`mapToLong(...).max().orElse(0)`).

## Try it yourself

All commands run in the repository folder.

**1. See the guard's decisions in the simulation.** No socket is used.

```text
./gradlew -q ttlExperiment --args='config/ttl-experiment.properties'
```

The end of the output is the audit of the guard:

```text
guard decisions:
  one-hour: t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-hour: t=7201s retire 192.0.2.10: refused, too early; allowed from t=14400s
  one-hour-lowered: t=3600s applied: seatwave.example. TTL 3600 -> 60 seconds
  one-hour-lowered: t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-hour-lowered: t=7201s retire 192.0.2.10: refused, too early; allowed from t=7320s
```

Check the arithmetic against this file: without lowering, the first version ended at 7200 and held a TTL of 3600, so the last old answer can live until 10800; the old TTL is 10800 - 7200 = 3600; twice that after the switch is 7200 + 7200 = 14400. With lowering, the latest expiry is 7260, the old TTL is 60, and the answer is 7200 + 120 = 7320.

**2. Watch the live migration.** Run in one terminal, and watch the lines for the guard:

```text
./gradlew -q ttlLive --args='config/ttl-live.properties'
```

It takes about half a minute. Lines such as `guard approved, applied: seatwave.example. TTL 10 -> 2 seconds`, `guard approved, applied: seatwave.example. 192.0.2.10 -> 203.0.113.30`, a refusal `retire 192.0.2.10: refused: caches may still send fans to 192.0.2.10; keep it serving; allowed from t=24.094 s`, and finally `guard approved: retire 192.0.2.10` appear in that order (the README shows a full run).

**3. Try to forge an approval.** In a scratch folder, a class outside the package:

```text
import io.plainround.seatwave.dns.migration.ApprovedChange;
public class Forge {
  public static void main(String[] a) {
    ApprovedChange fake = new ApprovedChange(null, null, "trust me");
  }
}
```

Compile it against the compiled classes (`javac -cp build/classes/java/main:<path to result4j-1.2.0.jar> Forge.java`). The compiler printed the error quoted above and stopped. Delete the file afterwards.

**4. Read the tests.** `src/test/java/io/plainround/seatwave/dns/migration/MigrationGuardTest.java` shows the same rules as test cases.

## Mistakes people make

- **Lowering the TTL and switching at once.** Caches that hold the old long TTL stay on the old address until it expires. The guard's window is the longest TTL the name carried, so the lowering has to happen one old TTL before the switch.
- **Switching to a site that has not been healthy.** The guard checks both sites for the whole window.
- **Turning off the old machine at once.** Some caches still send fans there. Retirement waits until twice the old TTL has passed.
- **Two people approving at the same time.** The second approval is refused because the zone changed after the first. It must be approved again.
- **Letting a list be changed by its owner and by readers at once.** `HealthHistory` and `ZoneStore` use `synchronized`; `ZoneStore.current` is `volatile` for the lock-free reader.
- **Trusting the compiler's access check as a security boundary.** Package-private access is a design aid. Reflection or a class in the same package can still reach it.

## Check yourself

1. Why can no code outside the `migration` package change the served zone? (`ZoneStore.apply` needs an `ApprovedChange`, and its constructor is package-private; only `MigrationGuard` calls it.)
2. What is the old TTL window? (The longest TTL the name has carried since its addresses last changed.)
3. When is a site healthy for the window? (There is no failed check inside it, and a healthy check lies at most one allowed gap before every moment of it.)
4. With a one-hour TTL that was never lowered and a switch at second 7200, when may the old address be retired? (From second 14400: 7200 plus twice 3600.)
5. What does `volatile` on `ZoneStore.current` do? (A reader thread always sees the newest zone, without taking a lock.)

## Further reading

- The Java Language Specification, Java SE 25, access control (chapter 6.6): https://docs.oracle.com/javase/specs/jls/se25/html/jls-6.html
- The Java SE 25 API, `java.time.Clock` and `java.time.Duration`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html
- The Java Language Specification, Java SE 25, `volatile` fields and the memory model (chapter 17): https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html
- RFC 2181, Clarifications to the DNS Specification: https://www.rfc-editor.org/rfc/rfc2181
- RFC 1035, section 3.2.1 and 4 (TTL, caching): https://www.rfc-editor.org/rfc/rfc1035
