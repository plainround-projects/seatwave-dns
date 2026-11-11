# Tests of the `config`, `experiment`, `jdk`, `migration` and `sampling` packages, file by file

## What it is

This file walks through eight test classes: `SettingsReaderTest` (4 tests, package `config`), `LiveMigrationTest` (2), `SiteEndpointsTest` (2) and `TtlSimulationTest` (6) in `experiment`, `LabResolverTest` (4) in `jdk`, `HealthHistoryTest` (4) and `MigrationGuardTest` (10) in `migration`, and `SamplerTest` (5) in `sampling`. That is 37 of the 107 tests. The other 70 are in `tests-message-and-zone-walkthrough.md` (43) and `tests-server-and-resolver-walkthrough.md` (27).

An **automated test** runs a piece of the program and checks the result (`junit.md` and `assertj.md` explain the tools). Most tests here are **unit tests** with no network. Three use real sockets on the loopback address `127.0.0.1`, the address that always means "this computer": `SiteEndpointsTest`, `LabResolverTest` and the last test of `SamplerTest`.

## Why this repository uses it

Each package carries one promise of the lesson. `config` promises that a mistake in a settings file is reported completely and never fixed silently. `migration` promises the module's invariant: no record change is applied unless the old and new addresses passed their health checks for the whole old TTL window, and the old address is not approved for retirement before the old TTL has passed twice after the switch. `experiment` promises that the TTL-change simulation and the live run give the numbers the README quotes, and that they are repeatable. `sampling` promises that the weighted-routing test counts what it says. `jdk` promises that the JDK's own name lookup can be pointed at the lab server and refuses everything else.

## Where it appears

| Path | What it tests |
| --- | --- |
| `src/test/java/io/plainround/seatwave/dns/config/SettingsReaderTest.java` | `SettingsReader`: every kind of value, every kind of problem, the file loader. |
| `src/test/java/io/plainround/seatwave/dns/experiment/LiveMigrationTest.java` | `LiveMigration`: the whole live migration on a simulated clock. |
| `src/test/java/io/plainround/seatwave/dns/experiment/SiteEndpointsTest.java` | `SiteEndpoints` health checks and `LiveSettings`. |
| `src/test/java/io/plainround/seatwave/dns/experiment/TtlSimulationTest.java` | `TtlSimulation`, `TtlExperimentCli.render`, `ExperimentSettings`, `Arm`, `SimulatedClock`. |
| `src/test/java/io/plainround/seatwave/dns/jdk/LabResolverTest.java` | `LabResolver` and `LabResolverProvider`. |
| `src/test/java/io/plainround/seatwave/dns/migration/HealthHistoryTest.java` | `HealthHistory.holeIn`: gaps and failures in a window. |
| `src/test/java/io/plainround/seatwave/dns/migration/MigrationGuardTest.java` | `MigrationGuard`, `ZoneStore`, `MigrationRefusal`. |
| `src/test/java/io/plainround/seatwave/dns/sampling/SamplerTest.java` | `Sampler`, `SampleResult`, `SampleReport`, `SampleCli.run`, `SampleSettings`. |

## How it works underneath

**TTL** (time to live) is the number of seconds a copy of a DNS answer may be kept. **Migration** here means moving the name `seatwave.example` from the old address `192.0.2.10` to the new address `203.0.113.30` without users noticing. The safe order, from Module 6, is: lower the TTL one old TTL ahead; switch the address; keep the old address serving until the old TTL has passed twice; then retire it. A **health check** is a small test that a site is up; `HealthHistory` stores them. The **guard** (`MigrationGuard`) is the only code that approves a change, and `ZoneStore.apply` accepts nothing but an `ApprovedChange`, so the rule cannot be bypassed.

**How a test of a network program with TTLs stays deterministic.** Real DNS needs hours; a test needs milliseconds and the same answer every time. Four devices do it.

1. **An injected clock.** Code that needs the time takes a `java.time.Clock` instead of calling the system. The tests give it a `SimulatedClock` that moves only when told (`clock.advanceTo(...)`) and never goes back. An hour passes in one call, and every boundary ("exactly at the TTL") is exact.
2. **An injected pause.** `LiveMigration` takes a function that waits. The command line passes a real sleep; the test passes `duration -> clock.advanceTo(clock.instant().plus(duration))`, which only moves the simulated clock. The wait costs nothing.
3. **A fixed seed.** Random choices (which address is first, when each resolver asks) draw from `SplittableRandom` with a fixed seed (`new SplittableRandom(1)`, or the seed in a settings file, `20261110`), so every run draws the same numbers.
4. **Loopback and free ports.** The three tests with sockets bind port 0, so the system picks a free one, and talk only to `127.0.0.1`.

## Walkthrough of this repository's use

### `SettingsReaderTest` (4 tests)

```java
package io.plainround.seatwave.dns.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.RecordType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final int TEN = 10;
```

`SettingsReader` reads typed values from a `Properties` object, the JDK's key-value store for text settings (a file of lines such as `count=7`). The README says every key of every settings file is required and a mistake is reported with its key name, all in one run. `TEN` is the upper limit used in several checks.

```java
  @Test
  void readsEveryKindOfValue() {
    SettingsReader reader =
        reader("count", "7", "wait.millis", "40", "type", "aaaa", "names", "root, tld");

    assertThat(reader.intBetween("count", 1, TEN)).isEqualTo(7);
    assertThat(reader.millisBetween("wait.millis", 0, Integer.MAX_VALUE))
        .isEqualTo(Duration.ofMillis(40));
    assertThat(reader.enumValue("type", RecordType.class, RecordType.A)).isEqualTo(RecordType.AAAA);
    assertThat(reader.wordsIn("names", List.of("root", "tld"))).containsExactly("root", "tld");
    assertThat(reader.problems()).isEmpty();
  }
```

**Proves:** the four readers work on good input: a whole number between bounds (`intBetween`), a time in milliseconds turned into a `Duration` (`millisBetween`), an enum value read from text in any case (`enumValue`: `aaaa` becomes `RecordType.AAAA`) and a comma list limited to allowed words (`wordsIn`). After all that `problems()` is empty.

```java
  @Test
  void reportsEveryProblemInOneRun() {
    SettingsReader reader =
        reader("count", "70", "word", "seven", "type", "mx", "names", "root,,tld", "other", "x");

    reader.intBetween("count", 1, TEN);
    reader.intBetween("word", 1, TEN);
    reader.intBetween("absent", 1, TEN);
    reader.enumValue("type", RecordType.class, RecordType.A);
    reader.wordsIn("names", List.of("root", "tld"));
    reader.wordsIn("other", List.of("root"));

    assertThat(reader.problems())
        .containsExactly(
            "count must be from 1 to 10, but was 70",
            "word must be a whole number, but was seven",
            "absent is missing",
            "type has an unknown value: mx",
            "names has an empty item",
            "other has x, but allows only root");
  }
```

**Proves:** every kind of problem is collected, in order, and none stops the reading. The six calls produce six messages: a number out of range, a word where a number belongs, a missing key, an unknown enum value, an empty item in a list, and a word that is not allowed. **Why it matters:** a person fixing a settings file sees all mistakes in one run, not one per run.

```java
  @Test
  void refusesTwoConstantsWhereOneIsExpected() {
    SettingsReader reader = reader("type", "a,aaaa");

    assertThat(reader.enumValue("type", RecordType.class, RecordType.TXT))
        .isEqualTo(RecordType.TXT);
    assertThat(reader.problems()).containsExactly("type takes one value, but has 2");
  }
```

**Proves:** where one constant is expected and two are given (`a,aaaa`), the reader reports `type takes one value, but has 2` and returns the fallback given by the caller (`TXT`), so the program can keep reading.

```java
  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }
```

**Proves:** `SettingsReader.load` reads a file as UTF-8 and ignores comment lines. `@TempDir` gives the test its own empty folder, deleted afterwards (`junit.md`).

```java
  private static SettingsReader reader(String... keysAndValues) {
    Properties properties = new Properties();
    List<String> pairs = List.of(keysAndValues);
    for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex += 2) {
      properties.setProperty(pairs.get(pairIndex), pairs.get(pairIndex + 1));
    }
    return new SettingsReader(properties);
  }
```

The helper `reader(String... keysAndValues)` builds a `Properties` from alternating keys and values. `List.of(keysAndValues)` makes a list of the arguments, the loop steps through it two at a time (`pairIndex += 2`) and `setProperty` stores each pair.

### `HealthHistoryTest` (4 tests)

```java
package io.plainround.seatwave.dns.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HealthHistoryTest {

  private static final InetAddress SITE = TestNames.address("192.0.2.10");
  private static final InetAddress OTHER = TestNames.address("203.0.113.30");
  private static final Instant START = Instant.EPOCH;
  private static final Duration GAP = Duration.ofSeconds(30);
  private static final long STEP_SECONDS = 10;
  private static final long WINDOW_SECONDS = 100;
```

`HealthHistory` stores health checks: a time, a site address and "healthy or not". `holeIn(site, from, to, maxGap)` looks for the first **hole** in a window: a failed check inside it, or a stretch longer than `maxGap` with no healthy check. It returns an `Optional<String>` that describes the hole, or empty if there is none. The constants: a start at the epoch, a gap of 30 seconds, a step of 10 seconds and a window of 100 seconds.

```java
  @Test
  void acceptsASiteHealthyThroughoutTheWindow() {
    HealthHistory health = checksEvery(STEP_SECONDS, WINDOW_SECONDS);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS), GAP)).isEmpty();
  }
```

**Proves:** checks every 10 seconds, all healthy, over a window of 100 seconds leave no hole.

```java
  @Test
  void findsAFailedCheckInsideTheWindowButIgnoresOneBeforeIt() {
    HealthHistory health = checksEvery(STEP_SECONDS, WINDOW_SECONDS);
    health.record(START.minusSeconds(STEP_SECONDS), SITE, false);
    health.record(START.plusSeconds(55), SITE, false);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS), GAP))
        .contains("192.0.2.10 failed its health check at 1970-01-01T00:00:55Z");
    assertThat(health.holeIn(SITE, START, START.plusSeconds(50), GAP)).isEmpty();
  }
```

**Proves:** a failed check at second 55 inside the window is found, and the message names the address and the time (`192.0.2.10 failed its health check at 1970-01-01T00:00:55Z`), while a failed check before the window (at -10 s) is ignored, and so is a failure after a window that ends earlier (the second `holeIn` stops at second 50).

```java
  @Test
  void findsAGapLongerThanAllowedAndAWindowWithNoChecks() {
    HealthHistory health = new HealthHistory();
    health.record(START, SITE, true);
    health.record(START.plusSeconds(STEP_SECONDS), SITE, true);
    health.record(START.plusSeconds(60), SITE, true);
    health.record(START.plusSeconds(70), SITE, true);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(70), GAP))
        .contains(
            "192.0.2.10 has no healthy check between 1970-01-01T00:00:40Z and 1970-01-01T00:01:00Z");
    assertThat(health.holeIn(SITE, START, START.plusSeconds(WINDOW_SECONDS + STEP_SECONDS), GAP))
        .isPresent();
    assertThat(health.holeIn(OTHER, START, START.plusSeconds(STEP_SECONDS), GAP))
        .contains(
            "203.0.113.30 has no healthy check between 1970-01-01T00:00:00Z and 1970-01-01T00:00:10Z");
  }
```

**Proves:** a gap is a hole. The checks are at 0, 10, 60 and 70 seconds; the 50 seconds between 10 and 60 are longer than the allowed 30, and the message names the stretch (`no healthy check between ...:00:40Z and ...:01:00Z`, which is the last check plus the gap, and the next check). A window that continues beyond the last check is also a hole, and an address with no checks at all (`OTHER`) is a hole from the start. **Why it matters:** "healthy throughout" must mean that a check was made recently at every moment, not merely that no check failed.

```java
  @Test
  void needsAHealthyCheckNearTheStartOfTheWindow() {
    HealthHistory health = new HealthHistory();
    health.record(START.plusSeconds(STEP_SECONDS), SITE, true);

    assertThat(health.holeIn(SITE, START, START.plusSeconds(STEP_SECONDS), GAP)).isPresent();
  }
```

**Proves:** a window needs a healthy check near its start. A single check at second 10 does not cover a window that starts at second 0.

```java
  private static HealthHistory checksEvery(long stepSeconds, long untilSeconds) {
    HealthHistory health = new HealthHistory();
    for (long second = 0; second <= untilSeconds; second += stepSeconds) {
      health.record(START.plusSeconds(second), SITE, true);
    }
    return health;
  }
```

The helper records a healthy check every `stepSeconds` from 0 to `untilSeconds`.

### `MigrationGuardTest` (10 tests)

```java
package io.plainround.seatwave.dns.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.experiment.SimulatedClock;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationGuardTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final InetAddress OLD = TestNames.address("192.0.2.10");
  private static final InetAddress OLD_IPV6 = TestNames.address("2001:db8::10");
  private static final InetAddress NEW = TestNames.address("203.0.113.30");
  private static final InetAddress NEW_IPV6 = TestNames.address("2001:db8::30");
  private static final Instant START = Instant.EPOCH;
  private static final Duration GAP = Duration.ofSeconds(30);
  private static final long HEALTH_STEP = 10;
  private static final long ONE_HOUR = 3_600;
  private static final long LOWERED = 60;
  private static final long FAR = 20_000;

  private final SimulatedClock clock = new SimulatedClock(START);
  private final ZoneStore store = new ZoneStore(TestZones.seatwave(), clock);
  private final HealthHistory health = new HealthHistory();
  private final MigrationGuard guard = new MigrationGuard(store, health, clock, GAP);
```

The setup is the whole system in miniature: the real seatwave zone in a `ZoneStore`, a `HealthHistory`, a `SimulatedClock` starting at the epoch and a `MigrationGuard` with a gap of 30 seconds. JUnit builds these fields anew for each test (`junit.md`), so each test starts at time zero with a clean store. The addresses: the old pair `192.0.2.10` and `2001:db8::10`, and the new pair `203.0.113.30` and `2001:db8::30`.

```java
  @Test
  void approvesLoweringTheTtlAfterOneOldTtlOfHealthyChecks() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    ApprovedChange change = approved(guard.changeTtl(APEX, LOWERED));
    store.apply(change);

    assertThat(change.description()).isEqualTo("seatwave.example. TTL 3600 -> 60 seconds");
    assertThat(store.current().recordsAt(APEX, RecordType.A))
        .singleElement()
        .satisfies(record -> assertThat(record.ttlSeconds()).isEqualTo(LOWERED));
    assertThat(store.history()).hasSize(2);
  }
```

**Proves:** lowering the TTL is approved after one old TTL of healthy checks. The test records healthy checks for the old pair and the new address (every 10 s up to 20,000 s), moves the clock to one hour (the old TTL, 3,600 s), asks the guard to lower the TTL to 60 and applies the approved change. The change describes itself as `seatwave.example. TTL 3600 -> 60 seconds`; the stored A record now has TTL 60; and the store's history has two entries (the first publication and this one).

```java
  @Test
  void refusesAnyChangeBeforeTheOldTtlWindowHasBeenWatched() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(LOWERED));

    Result<ApprovedChange, MigrationRefusal> early = guard.switchAddress(APEX, OLD, NEW);

    assertThat(((Result.Error<ApprovedChange, MigrationRefusal>) early).error())
        .isInstanceOf(MigrationRefusal.UnhealthySite.class)
        .extracting(MigrationRefusal::describe)
        .asString()
        .contains("192.0.2.10 has no healthy check between");
  }
```

**Proves:** a change is refused before the old TTL window has been watched. At 60 seconds, a switch from the old to the new address returns `UnhealthySite` whose text contains `192.0.2.10 has no healthy check between`: the window is the whole 3,600 seconds and only 60 have been lived.

```java
  @Test
  void refusesASwitchWhenTheNewSiteFailedACheck() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    health.record(START.plusSeconds(ONE_HOUR / 2), NEW, false);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    Result<ApprovedChange, MigrationRefusal> refused = guard.switchAddress(APEX, OLD, NEW);

    assertThat(((Result.Error<ApprovedChange, MigrationRefusal>) refused).error())
        .isEqualTo(
            new MigrationRefusal.UnhealthySite(
                NEW, "203.0.113.30 failed its health check at 1970-01-01T00:30:00Z"));
  }
```

**Proves:** one failed check on the new site, at half an hour, refuses the switch with `UnhealthySite(NEW, "203.0.113.30 failed its health check at 1970-01-01T00:30:00Z")`. The record equality compares the address and the message exactly.

```java
  @Test
  void refusesSwitchesThatDoNotFitTheZone() {
    healthy(List.of(OLD, OLD_IPV6, NEW, NEW_IPV6), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));

    assertThat(guard.switchAddress(APEX, NEW, OLD)).isInstanceOf(Result.Error.class);
    assertThat(guard.switchAddress(APEX, OLD, NEW_IPV6)).isInstanceOf(Result.Error.class);
    assertThat(guard.changeTtl(TestNames.name("nowhere.seatwave.example"), LOWERED))
        .isInstanceOf(Result.Error.class);
  }
```

**Proves:** switches that do not fit the zone are refused as errors: from an address the name does not have (`NEW` to `OLD`), to an address of another family (`OLD` to the IPv6 address `NEW_IPV6`), and a TTL change on a name that does not exist.

```java
  @Test
  void waitsTwiceTheOldTtlAfterASwitchWithoutLowering() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    store.apply(approved(guard.switchAddress(APEX, OLD, NEW)));
    Instant switchedAt = clock.instant();

    clock.advanceTo(switchedAt.plusSeconds(1));
    Result<Instant, MigrationRefusal> early = guard.approveRetirement(APEX, OLD);
    clock.advanceTo(switchedAt.plusSeconds(2 * ONE_HOUR));
    Result<Instant, MigrationRefusal> onTime = guard.approveRetirement(APEX, OLD);

    assertThat(((Result.Error<Instant, MigrationRefusal>) early).error())
        .isInstanceOf(MigrationRefusal.TooEarly.class)
        .extracting(refusal -> ((MigrationRefusal.TooEarly) refusal).earliest())
        .isEqualTo(switchedAt.plusSeconds(2 * ONE_HOUR));
    assertThat(onTime).isEqualTo(Result.success(switchedAt.plusSeconds(2 * ONE_HOUR)));
  }
```

**Proves:** the retirement rule after a switch without lowering. The switch is applied at 3,600 s. One second later, `approveRetirement` answers `TooEarly` with the earliest allowed time, switch time plus 2 × 3,600 s. At exactly that instant, it approves, and returns that instant as a success. This is "the old TTL has passed twice".

```java
  @Test
  void waitsOnlyTwiceTheLoweredTtlWhenTheLoweringCameOneOldTtlEarly() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    store.apply(approved(guard.changeTtl(APEX, LOWERED)));
    clock.advanceTo(START.plusSeconds(2 * ONE_HOUR));

    assertThat(guard.oldTtlWindowSeconds(APEX)).isEqualTo(ONE_HOUR);
    store.apply(approved(guard.switchAddress(APEX, OLD, NEW)));

    assertThat(guard.earliestRetirement(APEX, OLD))
        .contains(START.plusSeconds(2 * ONE_HOUR + 2 * LOWERED));
    assertThat(guard.oldTtlWindowSeconds(APEX)).isEqualTo(LOWERED);
  }
```

**Proves:** if the TTL was lowered one old TTL earlier, retirement waits only twice the lowered TTL. The sequence: lower to 60 at 3,600 s; at 7,200 s `oldTtlWindowSeconds` is still 3,600; switch; then the earliest retirement is 7,200 + 2 × 60 and the window is now 60. **Why it matters:** this is the benefit of lowering the TTL, and the numbers match the README's table (`allowed from t=7320s`).

```java
  @Test
  void refusesToRetireAnAddressThatIsStillPublished() {
    Result<Instant, MigrationRefusal> refused = guard.approveRetirement(APEX, OLD);

    assertThat(((Result.Error<Instant, MigrationRefusal>) refused).error())
        .isInstanceOf(MigrationRefusal.BadRequest.class);
    assertThat(guard.earliestRetirement(APEX, NEW)).isEmpty();
  }
```

**Proves:** you cannot retire an address that is still published (`BadRequest`), and an address that was never switched has no retirement time (`earliestRetirement` is empty).

```java
  @Test
  void refusesAnApprovalMadeAgainstAnOlderZone() {
    healthy(List.of(OLD, OLD_IPV6, NEW), FAR);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    ApprovedChange first = approved(guard.changeTtl(APEX, LOWERED));
    ApprovedChange second = approved(guard.switchAddress(APEX, OLD, NEW));
    store.apply(first);

    Result<Publication, MigrationRefusal> stale = store.apply(second);

    assertThat(((Result.Error<Publication, MigrationRefusal>) stale).error().describe())
        .isEqualTo("refused: the zone changed after this change was approved; approve it again");
  }
```

**Proves:** an approval is only good for the zone it was made against. The test makes two approvals (`first` and `second`) against the same zone, applies the first, then tries the second: the store refuses with `refused: the zone changed after this change was approved; approve it again`. **Why it matters:** the guard's decision rests on the zone it looked at; if the zone moved on, the decision is stale.

```java
  @Test
  void refusesAZeroGapBetweenChecks() {
    assertThatThrownBy(() -> new MigrationGuard(store, health, clock, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a gap of zero between checks is refused when building the guard.

```java
  @Test
  void describesEveryRefusal() {
    assertThat(new MigrationRefusal.TooEarly(START, "wait").describe())
        .isEqualTo("refused: wait; allowed from 1970-01-01T00:00:00Z");
    assertThat(new MigrationRefusal.BadRequest("no").describe()).isEqualTo("refused: no");
  }
```

**Proves:** the texts of the refusals: `TooEarly` prints `refused: wait; allowed from 1970-01-01T00:00:00Z`, and `BadRequest` prints `refused: no`.

```java
  private void healthy(List<InetAddress> sites, long untilSeconds) {
    for (long second = 0; second <= untilSeconds; second += HEALTH_STEP) {
      for (InetAddress site : sites) {
        health.record(START.plusSeconds(second), site, true);
      }
    }
  }

  private static ApprovedChange approved(Result<ApprovedChange, MigrationRefusal> decision) {
    return decision.discardError().orElseThrow();
  }
```

Helpers. `healthy(sites, untilSeconds)` records a healthy check for each site every 10 seconds. `approved(decision)` unwraps an approval and throws if the guard said no, so a test fails clearly when an approval it expected is missing.

### `TtlSimulationTest` (6 tests)

```java
package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TtlSimulationTest {

  private static final long ONE_HOUR = 3_600;
  private static final long LOWERED = 60;
  private static final long INTERVAL = 10;
  private static final int FIFTY = 0;
  private static final int NINETY_FIVE = 1;
  private static final int ALL = 3;
```

`TtlSimulation` is the model behind the README's "TTL-change experiment": many caching resolvers ask for a name at a steady pace while a migration runs through the guard on a `SimulatedClock`, and the run records when each resolver first sees the new address. The constants: one hour, a lowered TTL of 60, an asking interval of 10 s, and the indexes of the columns of `switchSeconds` (the time by which 50, 95, 99 and 100 percent of the resolvers have switched). The tests use `FIFTY = 0`, `NINETY_FIVE = 1`, `ALL = 3`.

```java
  @Test
  void aChangeWithoutLoweringTakesUpToTheOldTtlToReachEveryone() throws Exception {
    ExperimentSettings settings = settings("one-hour");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.switchSeconds().get(NINETY_FIVE)).isGreaterThan(ONE_HOUR * 9 / 10);
    assertThat(result.switchSeconds().get(ALL)).isLessThanOrEqualTo(ONE_HOUR + INTERVAL);
    assertThat(result.retireAfterSeconds()).isEqualTo(2 * ONE_HOUR);
    assertThat(result.cacheHitRatio()).isGreaterThan(0.99);
  }
```

**Proves:** without lowering, a change takes up to the whole old TTL to reach everyone. 95 percent of 200 resolvers switch after more than 90 percent of the hour, all within the hour plus one asking interval, retirement is allowed after 2 hours, and the cache hit ratio is above 0.99 (long TTLs mean few server queries). The settings come from the helper at the bottom of the file.

```java
  @Test
  void loweringOneOldTtlAheadMakesTheSwitchTakeOneShortTtl() throws Exception {
    ExperimentSettings settings = settings("one-hour-lowered");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.switchSeconds().get(FIFTY)).isLessThanOrEqualTo(LOWERED);
    assertThat(result.switchSeconds().get(ALL)).isLessThanOrEqualTo(LOWERED + INTERVAL);
    assertThat(result.retireAfterSeconds()).isEqualTo(2 * LOWERED);
    assertThat(result.onOldAtRetirePercent()).isZero();
    assertThat(result.guardLog())
        .containsExactly(
            "t=3600s applied: seatwave.example. TTL 3600 -> 60 seconds",
            "t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30",
            "t=7201s retire 192.0.2.10: refused, too early; allowed from t=7320s");
  }
```

**Proves:** lowering one old TTL ahead makes the switch take one short TTL: half the resolvers within 60 s, all within 70 s, retirement after 120 s, no resolver on the old address at the retirement time, and the exact guard log (the three decisions the README prints, `t=3600s`, `t=7200s`, `t=7201s ... allowed from t=7320s`).

```java
  @Test
  void pinningResolversAreStillOnTheOldAddressWhenTheTtlRuleSaysRetire() throws Exception {
    ExperimentSettings settings = settings("lowered-pinning");

    ArmResult result = TtlSimulation.run(settings, settings.arms().getFirst());

    assertThat(result.onOldAtRetirePercent()).isGreaterThan(1.0);
    assertThat(result.switchSeconds().get(ALL)).isGreaterThan(LOWERED * 10);
  }
```

**Proves:** the failure mode: 5 percent of the resolvers pin their answers, so when the TTL rule says "retire", more than 1 percent are still on the old address, and the last resolver takes more than ten times the lowered TTL (600 s) to switch.

```java
  @Test
  void printsAModelTableWithEveryGuardDecision() throws Exception {
    String table = TtlExperimentCli.render(settings("one-hour-lowered"));

    assertThat(table)
        .startsWith("MODEL, not a measurement.")
        .contains("one-hour-lowered")
        .contains("guard decisions:");
  }
```

**Proves:** the printed table starts with `MODEL, not a measurement.`, names the arm and lists the `guard decisions:`. The first words are a requirement of the lesson: a simulation must say that it is one.

```java
  @Test
  void listsEveryInvalidExperimentSetting() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-experiment.properties"));
    properties.setProperty("arm.one-hour.lowered.ttl.seconds", "7200");
    properties.setProperty("migration.new.address", "not-an-address");
    properties.remove("rate.window.seconds");

    List<String> problems =
        ((Result.Error<ExperimentSettings, List<String>>)
                ExperimentSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "rate.window.seconds is missing",
            "arm.one-hour.lowered.ttl.seconds must not exceed arm.one-hour.ttl.seconds",
            "not an address: not-an-address");
  }
```

**Proves:** three mistakes in the experiment settings are listed together: a missing key (`rate.window.seconds is missing`), a lowered TTL above the original TTL, and `not-an-address` as an address. The test edits a loaded `Properties` with `setProperty` and `remove`.

```java
  @Test
  void refusesAnArmThatRaisesTheTtlAndAClockThatGoesBack() {
    assertThatThrownBy(() -> new Arm("x", LOWERED, ONE_HOUR, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    SimulatedClock clock = new SimulatedClock(Instant.EPOCH.plusSeconds(1));
    assertThatThrownBy(() -> clock.advanceTo(Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> clock.withZone(ZoneOffset.UTC))
        .isInstanceOf(UnsupportedOperationException.class);
  }
```

**Proves:** an `Arm` cannot raise the TTL; a `SimulatedClock` cannot go back (`advanceTo(EPOCH)` from one second after the epoch throws); and its `withZone` is not supported.

```java
  private static ExperimentSettings settings(String arm) throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-experiment.properties"));
    properties.setProperty("resolvers.count", "200");
    properties.setProperty("arm.names", arm);
    return ExperimentSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
  }
```

The helper loads `config/ttl-experiment.properties` and overrides two keys: 200 resolvers instead of 1,000 and a single arm, so each test runs in a fraction of a second.

### `LiveMigrationTest` (2 tests)

```java
package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class LiveMigrationTest {

  private static final Duration DAY = Duration.ofDays(1);
  private static final double LOWERED_TTL_PLUS_ONE_POLL = 2.25;

  private final SimulatedClock clock = new SimulatedClock(Instant.EPOCH);
```

The live run, `ttlLive`, takes about half a minute on the wall clock. This test runs the same loop on a `SimulatedClock`, in milliseconds. `LOWERED_TTL_PLUS_ONE_POLL = 2.25` is the lowered TTL (2 s) plus one poll interval (0.25 s): the longest an honest client can need after the switch.

```java
  @Test
  void runsTheWholeMigrationAndRetiresOnlyWhenTheRuleAllows() throws Exception {
    LiveSettings settings = settings();
    List<String> log = new ArrayList<>();

    Map<String, Double> sawNew = run(settings, site -> true, log);

    assertThat(log)
        .contains("t= 10.000 s  guard approved, applied: seatwave.example. TTL 10 -> 2 seconds")
        .contains(
            "t= 20.000 s  guard approved, applied: seatwave.example. 192.0.2.10 -> 203.0.113.30")
        .contains(
            "t= 20.000 s  retire 192.0.2.10: refused: caches may still send fans to 192.0.2.10;"
                + " keep it serving; allowed from t=24.000 s")
        .contains("t= 24.000 s  guard approved: retire 192.0.2.10");
    assertThat(sawNew.get("honest")).isLessThanOrEqualTo(LOWERED_TTL_PLUS_ONE_POLL);
    assertThat(sawNew.get("pinning")).isGreaterThan(sawNew.get("honest"));
  }
```

**Proves:** the whole timeline, with the exact log lines at exact simulated times: the guard lowers the TTL from 10 to 2 at 10 s; switches the address at 20 s; refuses retirement at 20 s and says it is allowed from 24 s (twice the 2-second lowered TTL after the switch); approves retirement at 24 s. The honest client sees the new address within 2.25 s; the pinning client later than the honest one.

```java
  @Test
  void refusesTheSwitchWhenTheNewSiteIsDown() throws Exception {
    LiveSettings settings = settings();
    List<String> log = new ArrayList<>();

    Map<String, Double> sawNew = run(settings, site -> !site.equals(settings.newAddress()), log);

    assertThat(log)
        .anyMatch(line -> line.contains("refused: 203.0.113.30 failed its health check"));
    assertThat(sawNew).isEmpty();
  }
```

**Proves:** if the new site fails its health checks the switch is refused (`refused: 203.0.113.30 failed its health check`) and no client ever sees the new address (`sawNew` is empty). The test replaces the real health check with a lambda `site -> !site.equals(settings.newAddress())` that says "every site is up except the new one".

```java
  private Map<String, Double> run(
      LiveSettings settings, Predicate<InetAddress> siteCheck, List<String> log)
      throws InterruptedException {
    ZoneStore store = new ZoneStore(settings.zone(), clock);
    HealthHistory health = new HealthHistory();
    MigrationGuard guard = new MigrationGuard(store, health, clock, settings.maxCheckGap());
    InMemoryUpstream upstream = new InMemoryUpstream(store, new SplittableRandom(1));
    List<LiveMigration.Client> clients =
        List.of(
            client("honest", upstream, Duration.ZERO, settings),
            client("pinning", upstream, settings.pinningHold(), settings));
    LiveMigration migration =
        new LiveMigration(
            settings,
            new LiveMigration.Parts(store, health, guard, siteCheck),
            clock,
            duration -> clock.advanceTo(clock.instant().plus(duration)));
    return migration.run(clients, log::add);
  }
```

The helper `run` builds everything and runs the migration. The pause is the simulated one (the last argument of the constructor), which makes the run instant. `log::add` is a **method reference**: it hands every log line to the list so the test can read them.

```java
  private LiveMigration.Client client(
      String name, InMemoryUpstream upstream, Duration minimumHold, LiveSettings settings) {
    CachingResolver resolver =
        new CachingResolver(upstream, clock, new CachePolicy(minimumHold, DAY, DAY, Duration.ZERO));
    return new LiveMigration.Client(
        name,
        () ->
            resolver
                .resolve(settings.name(), RecordType.A)
                .addresses()
                .getFirst()
                .getHostAddress());
  }

  private static LiveSettings settings() throws Exception {
    return LiveSettings.from(
            new SettingsReader(SettingsReader.load(Path.of("config/ttl-live.properties"))))
        .discardError()
        .orElseThrow();
  }
```

`client(...)` builds one caching client: a resolver with a minimum hold (zero for the honest one, 30 seconds for the pinning one) that looks up the name and returns the first address as text. `settings()` loads `config/ttl-live.properties`.

### `SiteEndpointsTest` (2 tests)

```java
package io.plainround.seatwave.dns.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SiteEndpointsTest {

  private static final InetAddress SITE = TestNames.address("192.0.2.10");
  private static final Duration TIMEOUT = Duration.ofMillis(500);
  // Port 0 lets the system pick a free port, so the test never collides with a running lab.
  private static final int ANY_FREE_PORT = 0;
```

```java
  @Test
  void answersAHealthCheckUntilItIsClosed() throws Exception {
    SiteEndpoints sites = SiteEndpoints.start(Map.of(SITE, ANY_FREE_PORT));

    boolean isUpWhileOpen = sites.isHealthy(SITE, TIMEOUT);
    sites.close();

    assertThat(isUpWhileOpen).isTrue();
    assertThat(sites.isHealthy(SITE, TIMEOUT)).isFalse();
  }
```

**Proves:** a health check is a real TCP connection. `SiteEndpoints.start(Map.of(SITE, 0))` opens a listener on a free port for the site `192.0.2.10` (a documentation address that nothing answers, so it is mapped to a local port). While it is open, `isHealthy(SITE, TIMEOUT)` is true; after `close()` it is false. The test reads the first result before closing, because a check after the close must fail.

```java
  @Test
  void readsTheLiveSettingsAndListsInvalidOnes() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/ttl-live.properties"));
    LiveSettings settings =
        LiveSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
    properties.setProperty("server.port", "53");
    properties.setProperty("migration.old.address", "203.0.113.999");

    List<String> problems =
        ((Result.Error<LiveSettings, List<String>>)
                LiveSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(settings.loweredTtlSeconds()).isEqualTo(2);
    assertThat(problems)
        .containsExactly(
            "server.port must be from 1024 to 65535, but was 53", "not an address: 203.0.113.999");
  }
```

**Proves:** the live settings load (the lowered TTL is 2 seconds), and two bad values are reported together: port 53 is below 1024, and `203.0.113.999` is not an address.

### `LabResolverTest` (4 tests)

```java
package io.plainround.seatwave.dns.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LabResolverTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final InetAddress IPV4 = TestNames.address("192.0.2.10");
  private static final InetAddress IPV6 = TestNames.address("2001:db8::10");
```

`LabResolver` plugs the lab server into the JDK's own name lookup, `InetAddress.getByName`, through the resolver service-provider interface of JDK 18 and later. It answers names under `seatwave.example` from the lab server and refuses every other name, so no lookup leaves the machine. A `LookupPolicy` is the JDK's request: which address families to return and in which order.

```java
  @Test
  void resolvesTheLabZoneInTheOrderTheJdkAsksFor() throws Exception {
    try (DnsServer server = start()) {
      LabResolver resolver = new LabResolver(server.port(), TIMEOUT);

      assertThat(resolver.lookupByName("seatwave.example", LookupPolicy.of(LookupPolicy.IPV4)))
          .containsExactly(IPV4);
      assertThat(
              resolver.lookupByName(
                  "www.seatwave.example", LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6)))
          .containsExactly(IPV4, IPV6);
      assertThat(
              resolver.lookupByName(
                  "seatwave.example",
                  LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV6_FIRST)))
          .containsExactly(IPV6, IPV4);
    }
  }
```

**Proves:** the order the JDK asks for is the order it gets. IPv4 only returns the A record; IPv4 and IPv6 for `www.seatwave.example` returns the IPv4 address then the IPv6 address (the alias is followed); with the flag `IPV6_FIRST` the order is reversed. The flags are numbers joined with `|`.

```java
  @Test
  void refusesNamesOutsideTheLabAndNamesThatDoNotExist() throws Exception {
    try (DnsServer server = start()) {
      LabResolver resolver = new LabResolver(server.port(), TIMEOUT);
      LookupPolicy policy = LookupPolicy.of(LookupPolicy.IPV4);

      assertThatThrownBy(() -> resolver.lookupByName("example.com", policy))
          .isInstanceOf(UnknownHostException.class)
          .hasMessage("the lab resolver answers only names under seatwave.example.");
      assertThatThrownBy(() -> resolver.lookupByName("nowhere.seatwave.example", policy))
          .isInstanceOf(UnknownHostException.class);
      assertThatThrownBy(() -> resolver.lookupByName("bad..name", policy))
          .isInstanceOf(UnknownHostException.class);
      assertThatThrownBy(() -> resolver.lookupByAddress(IPV4.getAddress()))
          .isInstanceOf(UnknownHostException.class);
    }
  }
```

**Proves:** `example.com` is refused with `the lab resolver answers only names under seatwave.example.`; a missing name, a bad name (`bad..name`) and a reverse lookup (`lookupByAddress`) all throw `UnknownHostException`. This is the "no lookup ever leaves the machine" rule as a test.

```java
  @Test
  void reportsAServerThatDoesNotAnswer() throws Exception {
    int closedPort;
    try (DnsServer server = start()) {
      closedPort = server.port();
    }
    LabResolver resolver = new LabResolver(closedPort, TIMEOUT);

    assertThatThrownBy(
            () -> resolver.lookupByName("seatwave.example", LookupPolicy.of(LookupPolicy.IPV4)))
        .isInstanceOf(UnknownHostException.class)
        .hasMessageStartingWith("seatwave.example: the server is unreachable");
  }
```

**Proves:** a server that is gone is reported, not hidden. The test starts a server only to learn a port, closes it (the `try` ends), then looks up through that port: the exception text starts with `seatwave.example: the server is unreachable`.

```java
  @Test
  void namesItselfForTheServiceLoader() {
    // The JDK's provider configuration is a sealed type, so choosing the resolver is shown by the
    // jdkLookup and ttlLive runs, not here.
    assertThat(new LabResolverProvider().name()).isEqualTo("seatwave-lab");
  }

  private static DnsServer start() throws Exception {
    return DnsServer.start(
        DnsServer.ANY_FREE_PORT,
        new ZoneStore(TestZones.seatwave(), Clock.systemUTC()),
        new SplittableRandom(1));
  }
```

**Proves:** the provider names itself `seatwave-lab`. The comment says what is missing: the JDK's provider configuration is a sealed type that a test cannot build, so choosing the resolver is shown by the `jdkLookup` and `ttlLive` runs, not here. (JaCoCo reports the provider's `get` method as not run, `jacoco.md`.) The helper `start()` starts a server on a free port.

### `SamplerTest` (5 tests)

```java
package io.plainround.seatwave.dns.sampling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import io.plainround.seatwave.dns.zone.TestZones;
import io.plainround.seatwave.dns.zone.Zone;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SamplerTest {

  private static final DomainName BOOK = TestNames.name("book.seatwave.example");
  private static final int LOOKUPS = 10_000;
  private static final int FEW = 3;
  private static final long SEED = 20_261_110;
```

`Sampler` sends the same question many times and counts which address each answer puts first. `SEED = 20_261_110` is the date of the lab; `LOOKUPS = 10_000` the sample size.

```java
  @Test
  void measuresTheConfiguredSplitWithinThreeStandardErrors() {
    Zone zone = TestZones.seatwaveNinetyTen();
    InMemoryUpstream upstream =
        new InMemoryUpstream(new ZoneStore(zone, Clock.systemUTC()), new SplittableRandom(SEED));

    SampleResult result = Sampler.sample(upstream, BOOK, RecordType.A, LOOKUPS);
    String report = SampleReport.render(result, zone);

    assertThat(result.failures()).isZero();
    assertThat(report).contains("192.0.2.10").contains("198.51.100.20").doesNotContain("OUTSIDE");
    assertThat(report).containsPattern("192\\.0\\.2\\.10 +90 +90\\.00 ");
  }
```

**Proves:** with the 90-10 weights, the sampler runs with no failures, the rendered report mentions both addresses, does not contain the flag `OUTSIDE` (a share outside its tolerance), and has a row that matches the pattern `192.0.2.10 +90 +90.00 `: weight 90, measured 90 percent to two decimals. The upstream is an `InMemoryUpstream` (no socket) with a seeded generator, so the 10,000 picks are the same on every run.

```java
  @Test
  void countsEveryFailedLookupAndFlagsAShareOutsideItsTolerance() {
    SampleResult result =
        Sampler.sample(
            question -> Result.error(new LookupProblem.TimedOut(Duration.ofMillis(1))),
            BOOK,
            RecordType.A,
            FEW);
    SampleResult skewed =
        new SampleResult(
            BOOK,
            LOOKUPS,
            Map.of(TestNames.address("192.0.2.10"), LOOKUPS),
            0,
            Duration.ZERO,
            Duration.ZERO,
            Duration.ZERO);

    assertThat(result.failures()).isEqualTo(FEW);
    assertThat(result.shareOf(TestNames.address("192.0.2.10"))).isZero();
    assertThat(SampleReport.render(skewed, TestZones.seatwave())).contains("OUTSIDE");
  }
```

**Proves:** failures are counted, not hidden (an upstream that always times out gives `failures() == 3` and a share of zero), and a result that puts all 10,000 lookups on one address is flagged `OUTSIDE` in the report for a 50-50 zone.

```java
  @Test
  void refusesCountsThatDoNotAddUp() {
    assertThatThrownBy(() ->
            new SampleResult(BOOK, FEW, Map.of(), 1, Duration.ZERO, Duration.ZERO, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            Sampler.sample(
                question -> Result.error(new LookupProblem.TimedOut(Duration.ZERO)),
                BOOK,
                RecordType.A,
                0))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a `SampleResult` whose counts do not add up is refused, and so is a sample size of zero.

```java
  @Test
  void runsTheProjectsSampleAgainstALocalServerOverUdp() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/sample-90-10.properties"));
    properties.setProperty("lookup.count", "2000");
    properties.setProperty("lookup.warmup.count", "10");
    SampleSettings settings =
        SampleSettings.from(new SettingsReader(properties)).discardError().orElseThrow();

    String report = SampleCli.run(settings);

    assertThat(report).contains("the lab server on 127.0.0.1:").contains("2000 lookups, 0 failed");
  }
```

**Proves:** the whole `sample` task end to end over UDP. The test loads `config/sample-90-10.properties`, overrides the count (2,000 lookups) and the warm-up (10), runs `SampleCli.run`, which starts a local server and asks it, and checks the report text: `the lab server on 127.0.0.1:` and `2000 lookups, 0 failed`. The port is chosen by the system, so the report is checked only for what is stable.

```java
  @Test
  void listsEveryInvalidSampleSetting() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/sample.properties"));
    properties.setProperty("lookup.type", "txt");
    properties.setProperty("lookup.name", "bad..name");
    properties.setProperty("server.external.port", "53");

    List<String> problems =
        ((Result.Error<SampleSettings, List<String>>)
                SampleSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "server.external.port must be from 1024 to 65535, but was 53",
            "lookup.name: a name must not have an empty label: bad..name",
            "lookup.type must be a or aaaa");
  }
```

**Proves:** three mistakes in the sample settings are listed together: the external port is 53 (below 1024), the name has an empty label, and the type `txt` is not `a` or `aaaa`.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Run the five packages and count:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.dns.config.*' --tests 'io.plainround.seatwave.dns.experiment.*' --tests 'io.plainround.seatwave.dns.jdk.*' --tests 'io.plainround.seatwave.dns.migration.*' --tests 'io.plainround.seatwave.dns.sampling.*' | grep -c PASSED
```

   Output: `37`.

2. Run the live migration on its simulated clock and watch how fast it is:

```
./gradlew --offline cleanTest test --tests '*LiveMigrationTest'
```

   Output: two `PASSED` lines and `BUILD SUCCESSFUL`, in about a second (the whole command took 1.4 s here), although the same timeline takes about 30 seconds on the wall clock when you run `ttlLive`.

3. Break the invariant on purpose (scratch copy only). In `MigrationGuardTest`, change `clock.advanceTo(switchedAt.plusSeconds(2 * ONE_HOUR))` on line 100 to `clock.advanceTo(switchedAt.plusSeconds(ONE_HOUR))` and run `./gradlew --offline cleanTest test --tests '*MigrationGuardTest.waitsTwiceTheOldTtlAfterASwitchWithoutLowering'`. Output: `MigrationGuardTest > waitsTwiceTheOldTtlAfterASwitchWithoutLowering() FAILED` with `AssertionFailedError at MigrationGuardTest.java:107`, `expected: Success[result=1970-01-01T03:00:00Z]` and ` but was: Error[error=TooEarly[earliest=1970-01-01T03:00:00Z, reason=caches may still send fans to 192.0.2.10; keep it serving]]`: after only one TTL the guard says it is too early. Undo the edit afterwards.

## Mistakes people make

- **Sleeping in a test of time.** Use an injected clock and an injected pause.
- **An unseeded random generator.** The test then passes on one run and fails on the next. Every generator here has a seed.
- **A real sleep in the code under test.** `LiveMigration` takes the pause as a parameter so a test can replace it.
- **Checking only "no failed check".** `HealthHistoryTest` shows that a long stretch with no check is also a hole.
- **Letting the guard and the store disagree.** `ZoneStore.apply` accepts only an `ApprovedChange`, and refuses an approval made against an older zone.
- **Sharing a port between tests.** Tests that open sockets use port 0.

## Check yourself

1. *Why does `LiveMigrationTest` finish in milliseconds?* It uses a `SimulatedClock` and a pause that only advances that clock.
2. *When may the old address be retired after a switch without lowering?* After twice the old TTL has passed since the switch.
3. *What happens when the new site fails one health check in the window?* The guard refuses the switch with `UnhealthySite`.
4. *What is a "hole" in `HealthHistory`?* A failed check inside the window, or a stretch longer than the allowed gap with no healthy check.
5. *Why is a stale approval refused?* It was decided against an older zone, and the store requires the zone to be the one the guard looked at.

## Further reading

- RFC 1034, domain names, concepts and facilities (zones, caching): <https://www.rfc-editor.org/rfc/rfc1034>
- `java.time.Clock` in Java 25: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html>
- JEP 418, the internet-address resolution SPI: <https://openjdk.org/jeps/418>
- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
