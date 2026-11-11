# The `experiment`, `sampling` and `config` packages, line by line

## What it is

This file reads the Java code of three packages, one line at a time.

- The `config` package has one file, `SettingsReader.java`. It reads typed values from a settings file and collects every mistake.
- The `sampling` package has five files: `Sampler.java`, `SampleResult.java`, `SampleReport.java`, `SampleSettings.java` and `SampleCli.java`. Together they are the **weighted-routing test**: look one name up 10,000 times and count how often each address comes first.
- The `experiment` package has ten files: `SimulatedClock.java`, `Arm.java`, `ArmResult.java`, `ExperimentSettings.java`, `TtlSimulation.java`, `TtlExperimentCli.java` (the simulated TTL-change experiment), and `LiveSettings.java`, `LiveMigration.java`, `SiteEndpoints.java`, `TtlLiveCli.java` (the live run).

An **experiment** here is a program that sets up a situation, lets it run, and prints numbers. A **simulation** is an experiment on a model: the program pretends to be many resolvers and a clock that moves only when told to. A **live run** uses the real clock and real sockets, with seconds-long TTLs.

## Why this repository uses it

Module 6 makes three claims that the code must back with numbers.

1. **Weighted DNS answers share traffic by the weights.** The sampling test sends 10,000 queries and checks that each address comes first about as often as its weight says, within three standard errors.
2. **A long TTL makes a move slow, and a short TTL costs load.** The simulation runs 1,000 caching resolvers through five arms (one-hour and one-day TTLs, lowered or not, with and without resolvers that pin) and prints how long until 50, 95, 99 and 100 percent of them give the new address, and how many queries the server sees.
3. **What a real program does matches the model.** The live run does the same move in about thirty seconds with real UDP, real TCP health checks, honest clients, a pinning client and the JVM's own cache.

Every program reads its settings from a file in `config/` with **no defaults in code**: a missing key is an error that names the key. That is the job of `SettingsReader`.

## Where it appears

| File | What it does |
|---|---|
| `config/SettingsReader.java` | Reads whole numbers, durations, words and enum values from a properties file; collects every problem. |
| `sampling/Sampler.java` | Sends the same question many times through an `Upstream` and counts which address is first. |
| `sampling/SampleResult.java` | The counts and the latency of one sample. |
| `sampling/SampleReport.java` | Prints the table and the verdict "within" or "OUTSIDE". |
| `sampling/SampleSettings.java` | Reads `config/sample*.properties`. |
| `sampling/SampleCli.java` | The `sample` command. |
| `experiment/SimulatedClock.java` | A clock that moves only when told to. |
| `experiment/Arm.java`, `ArmResult.java` | One arm of the experiment and what it measured. |
| `experiment/ExperimentSettings.java` | Reads `config/ttl-experiment.properties`. |
| `experiment/TtlSimulation.java` | The simulation of one arm. |
| `experiment/TtlExperimentCli.java` | The `ttlExperiment` command. |
| `experiment/LiveSettings.java` | Reads `config/ttl-live.properties`. |
| `experiment/LiveMigration.java` | The loop of the live run, with an injected clock and pause. |
| `experiment/SiteEndpoints.java` | Two TCP listeners that stand for the two SeatWave sites. |
| `experiment/TtlLiveCli.java` | The `ttlLive` command. |

The settings files are explained in `java-properties.md`; the zones in `dns-zone-files.md`.

## How it works underneath

**Weighted routing, seen from the client.** The zone gives `book.seatwave.example.` two A records. The server moves one of them to the top of the answer at random, with probabilities in proportion to the weights. A client that takes the first address then spreads over the sites in those proportions. The sampling test asks 10,000 times and counts.

**Is a count "close enough"?** A fair coin flipped 10,000 times does not give exactly 5,000 heads. For a share `p` over `n` lookups the **standard error** is the square root of `p(1-p)/n`. For `p = 0.5` and `n = 10,000` it is 0.005, or 0.5 percentage points. The report allows three standard errors: 1.5 points. A correct server falls outside that about three times in a thousand runs. The seed is fixed (20261110), so the same run always gives the same numbers.

**The simulation, step by step** (for one arm, with an old TTL `T`):

1. The record is published with TTL `T` at time 0. 1,000 resolvers are made; each is a `CachingResolver` on the same simulated clock.
2. Each resolver is asked by its fans every 10 seconds, at its own random offset (its **phase**), and starts at a random moment of the first `T`, so by time `T` every cache is warm and the ages of the cached answers are spread evenly.
3. If the arm lowers the TTL, the guard approves lowering it at time `T`.
4. At time `2T` the guard approves switching the address to the new one.
5. One second later the guard is asked to approve retiring the old one; it refuses.
6. For each resolver, the program records the first moment it gave the new address.
7. The run continues until the last cache can have expired, and the report shows the time for 50, 95, 99 and 100 percent of the resolvers.

Every question goes through the real `Responder` and the real codec, but no socket is used: `InMemoryUpstream` calls them directly, so two days of simulated time take a second.

**The live run, step by step.** A real server listens on UDP port 5355. Two TCP listeners on ports 18081 and 18082 stand for the two sites (the sites' own addresses, 192.0.2.10 and 203.0.113.30, are documentation addresses that nothing answers). Every 500 ms the program opens a TCP connection to each listener with a 200 ms timeout; success is a healthy check. Every 250 ms five clients look the name up: three honest resolvers, one that keeps every answer at least 30 seconds (the pinning client) and `InetAddress.getByName`. At 10 seconds the TTL is lowered to 2; at 20 seconds the address is switched; the run prints each moment a client's answer changes.

**Time without sleeping.** Both `TtlSimulation` and `LiveMigration` take the clock as an argument. `LiveMigration` also takes a `Pause` (a function that waits). The command line passes `Clock.systemUTC()` and `Thread::sleep`; a test passes a `SimulatedClock` and a pause that advances the clock. So the same loop runs in milliseconds in a test.

## Walkthrough of this repository's use

### `config/SettingsReader.java`

```java
package io.plainround.seatwave.dns.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
```

- Imports: `Properties` (the JDK class for a `.properties` file), `Reader` and `Files` (reading a file), `StandardCharsets`, `Duration`, and the list classes.

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a learner sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();

  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

- The Javadoc is the contract: typed values from a settings file, **one problem line per missing or invalid key**, so a learner sees every mistake in one run. There are no defaults. When a key is invalid the method records the problem and returns a harmless stand-in value; the caller must check `problems()` before using any value.
- The two fields: the loaded `Properties` and a list of problem lines.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

- `load` reads a file as UTF-8. `Files.newBufferedReader(path, UTF_8)` opens it; the `try (Reader reader = ...)` block closes it at the end, even on an error. `properties.load(reader)` fills the `Properties` object from the lines `key=value`. Reading with a `Reader` and an explicit charset avoids the older byte-stream `load(InputStream)`, which reads the file as ISO-8859-1, an old single-byte encoding.

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

- `intBetween(key, minimum, maximum)` reads a whole number inside a range. A missing key was recorded by `text` already, so it returns the minimum. `Integer.parseInt` reads the number. Outside the range: a problem line such as `lookup.count must be from 1 to 1000000, but was 0`, and the minimum is returned. Not a number: `NumberFormatException` is caught and becomes `key must be a whole number, but was abc`.
- `String.format(Locale.ROOT, ...)` fills `%s` (text) and `%d` (whole number). `Locale.ROOT` keeps the digits the same on every computer.

```java
  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code .millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

- `millisBetween` is `intBetween` wrapped in a `Duration`. By convention the key ends in `.millis`.

```java
  /**
   * Reads a comma-separated list of enum constants, written in lower case.
   *
   * @param key the key
   * @param type the enum class
   * @param <E> the enum type
   * @return the constants in file order, or an empty list if the key is missing or invalid
   */
  public <E extends Enum<E>> List<E> enumList(String key, Class<E> type) {
    Objects.requireNonNull(type, "type");
    List<E> values = new ArrayList<>();
    for (String item : items(key)) {
      try {
        values.add(Enum.valueOf(type, item.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        problems.add(key + " has an unknown value: " + item);
        return List.of();
      }
    }
    return List.copyOf(values);
  }
```

- `enumList` reads a comma-separated list of enum constants written in lower case. `<E extends Enum<E>> List<E>` is a **generic method**: `E` stands for whatever enum the caller passes, and `Class<E> type` is the enum class itself. `Enum.valueOf(type, "A")` finds the constant by name. An unknown word is a problem and the list is empty.

```java
  /**
   * Reads a comma-separated list of words, each from an allow-list.
   *
   * @param key the key
   * @param allowed the only words accepted, compared exactly
   * @return the words in file order, or an empty list if the key is missing or invalid
   */
  public List<String> wordsIn(String key, List<String> allowed) {
    Objects.requireNonNull(allowed, "allowed");
    List<String> words = items(key);
    for (String word : words) {
      if (!allowed.contains(word)) {
        problems.add(key + " has " + word + ", but allows only " + String.join(", ", allowed));
        return List.of();
      }
    }
    return List.copyOf(words);
  }
```

- `wordsIn` reads a comma-separated list of words, each of which must be in an allow-list given by the caller. A word outside it is a problem naming the allowed words.

```java
  /**
   * Reads one enum constant, written in lower case.
   *
   * @param key the key
   * @param type the enum class
   * @param fallback the harmless stand-in returned when the key is missing or invalid
   * @param <E> the enum type
   * @return the constant, or the fallback if the key is missing or invalid
   */
  public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
    Objects.requireNonNull(fallback, "fallback");
    List<E> values = enumList(key, type);
    if (values.size() > 1) {
      problems.add(key + " takes one value, but has " + values.size());
      return fallback;
    }
    return values.isEmpty() ? fallback : values.getFirst();
  }
```

- `enumValue` reads one constant. More than one value is a problem. If nothing valid was read, it returns the `fallback`, the harmless stand-in.

```java
  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

- `text` is the base of all the other methods. `properties.getProperty(key)` returns `null` when the key is absent. A missing or blank value adds `key is missing` to the problems and returns an empty text. Otherwise it returns the value with the spaces at both ends removed (`strip`).

```java
  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
```

- `problems()` returns a copy of the list, so callers cannot change it.

```java
  private List<String> items(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",", -1)) {
      if (item.isBlank()) {
        problems.add(key + " has an empty item");
        return List.of();
      }
      items.add(item.strip());
    }
    return items;
  }
}
```

- `items` splits a text at commas. `split(",", -1)` keeps empty pieces at the end, so `a,b,` is seen as having an empty item and reported (`isBlank()` is true for an empty text or only spaces). Each item has its spaces removed.

### `sampling/Sampler.java`

```java
package io.plainround.seatwave.dns.sampling;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import io.plainround.seatwave.dns.resolver.Upstream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends the same question many times and counts which address each answer puts first, which is the
 * address a client that takes the first record would use. Stateless and thread-safe; the upstream
 * it is given is not, so give each thread its own.
 */
public final class Sampler {

  // Percentiles by nearest rank: the p50 and p99 of the lookup times.
  private static final double MEDIAN = 0.50;
  private static final double P99 = 0.99;

  private Sampler() {}
```

- `Sampler` sends the same question many times and counts which address each answer puts first: the address a client that takes the first record would use. It is stateless, so thread-safe; the `Upstream` it is given is not.
- The two constants are the percentiles to report: 50 percent (the **median**) and 99 percent. A **percentile** is the value below which that share of the measurements fall; the p99 is the time that 99 percent of lookups beat.

```java
  /**
   * Looks a name up many times.
   *
   * @param upstream the server to ask, for example a {@code DnsClient} on 127.0.0.1
   * @param name the name
   * @param type A or AAAA
   * @param lookups how many lookups, at least 1
   * @return the counts and the latency
   */
  public static SampleResult sample(
      Upstream upstream, DomainName name, RecordType type, int lookups) {
    Objects.requireNonNull(upstream, "upstream");
    if (lookups < 1) {
      throw new IllegalArgumentException("at least one lookup is needed");
    }
    Map<InetAddress, Integer> counts = new LinkedHashMap<>();
    long[] nanos = new long[lookups];
    int failures = 0;
    Question question = Question.of(name, type);
    for (int lookupIndex = 0; lookupIndex < lookups; lookupIndex++) {
      long started = System.nanoTime();
      Result<Message, LookupProblem> response = upstream.ask(question);
      nanos[lookupIndex] = System.nanoTime() - started;
      Optional<InetAddress> first = firstAddress(response, type);
      if (first.isPresent()) {
        counts.merge(first.get(), 1, Integer::sum);
      } else {
        failures++;
      }
    }
    Arrays.sort(nanos);
    return new SampleResult(
        name,
        lookups,
        counts,
        failures,
        Duration.ofNanos(percentile(nanos, MEDIAN)),
        Duration.ofNanos(percentile(nanos, P99)),
        Duration.ofNanos(nanos[lookups - 1]));
  }
```

- `sample` refuses less than one lookup. `counts` maps an address to how many times it was first; `nanos` stores the time of each lookup. `long[]` is an array of 64-bit numbers.
- The loop reads `System.nanoTime()` before and after `upstream.ask(question)`, and stores the difference. `firstAddress` takes the first address from the answer; if there is none (an error, or no record), the lookup counts as a failure. `counts.merge(address, 1, Integer::sum)` adds 1 to the address's count, or sets it to 1 if absent.
- `Arrays.sort(nanos)` sorts the times so the percentiles can be read by position. The result holds the name, the number of lookups, the counts, the failures, the p50, the p99 and the maximum (the last element).

```java
  private static Optional<InetAddress> firstAddress(
      Result<Message, LookupProblem> response, RecordType type) {
    if (!(response instanceof Result.Success<Message, LookupProblem>(Message message))) {
      return Optional.empty();
    }
    return message.answers().stream()
        .filter(record -> record.type() == type)
        .map(ResourceRecord::data)
        .map(data -> ((RecordData.Address) data).address())
        .findFirst();
  }

  private static long percentile(long[] sorted, double share) {
    int rank = (int) Math.ceil(share * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
```

- `firstAddress` uses a pattern on the `Result`: `response instanceof Result.Success<...>(Message message)`; the `!` in front means "if it is not a success, return empty". Then it keeps the records of the asked type, takes the address of each and takes the first.
- `percentile` uses the nearest-rank method: the rank is `ceil(share * n)`; the value is the element at position `rank - 1` of the sorted array. `Math.max(rank, 1)` guards the first element.

### `sampling/SampleResult.java`

```java
package io.plainround.seatwave.dns.sampling;

import io.plainround.seatwave.dns.message.DomainName;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What many lookups of one name returned: how often each address came first, how many lookups
 * failed, and the lookup latency.
 *
 * @param name the name looked up
 * @param lookups how many lookups were sent
 * @param firstCounts how many answers had each address first, in the order first seen
 * @param failures lookups with no usable answer
 * @param latencyP50 the median lookup time
 * @param latencyP99 the lookup time 99 percent of lookups beat
 * @param latencyMax the slowest lookup
 */
public record SampleResult(
    DomainName name,
    int lookups,
    Map<InetAddress, Integer> firstCounts,
    int failures,
    Duration latencyP50,
    Duration latencyP99,
    Duration latencyMax) {
```

- A record of what many lookups returned. `firstCounts` is a map from address to the number of times it was first.

```java
  /**
   * Checks and copies the fields.
   *
   * @throws IllegalArgumentException if the counts do not add up to the lookups
   */
  public SampleResult {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(latencyP50, "latencyP50");
    Objects.requireNonNull(latencyP99, "latencyP99");
    Objects.requireNonNull(latencyMax, "latencyMax");
    firstCounts = Collections.unmodifiableMap(new LinkedHashMap<>(firstCounts));
    int counted = firstCounts.values().stream().mapToInt(Integer::intValue).sum();
    if (lookups < 1 || counted + failures != lookups) {
      throw new IllegalArgumentException("every lookup must be counted once");
    }
  }

  /**
   * Returns the share of answers that had an address first.
   *
   * @param address the address
   * @return the share from 0 to 1, of all lookups
   */
  public double shareOf(InetAddress address) {
    return (double) firstCounts.getOrDefault(address, 0) / lookups;
  }
}
```

- The compact constructor refuses nulls, copies the map into an unmodifiable one (`Collections.unmodifiableMap(new LinkedHashMap<>(...))` keeps the order the addresses were first seen), and checks that every lookup was counted exactly once: the counts plus the failures must equal the number of lookups.
- `shareOf` is the count divided by the lookups, from 0 to 1. `(double) count / lookups` converts to a decimal before dividing.

### `sampling/SampleReport.java`

```java
package io.plainround.seatwave.dns.sampling;

import io.plainround.seatwave.dns.zone.Zone;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Prints a sample against the weights the zone configures. A measured share counts as "within" when
 * it is at most three standard errors from the configured share; for a share p over n lookups, one
 * standard error is the square root of p(1 - p) / n. Stateless and thread-safe.
 */
public final class SampleReport {

  // Three standard errors: a correct server lands outside about 3 times in 1,000 samples.
  private static final double STANDARD_ERRORS_ALLOWED = 3;
  private static final double PERCENT = 100;
  private static final double NANOS_PER_MILLI = 1_000_000;

  private SampleReport() {}
```

- The Javadoc defines the verdict: a share counts as **within** when it is at most three standard errors from the configured share, where one standard error is the square root of `p(1-p)/n`. The comment gives the meaning of three: a correct server lands outside about 3 times in 1,000 samples.

```java
  /**
   * Renders the table.
   *
   * @param result the sample
   * @param zone the zone, for the configured weights and the addresses of the name
   * @return the table and the latency line
   */
  public static String render(SampleResult result, Zone zone) {
    List<InetAddress> addresses = zone.addressesOf(result.name());
    int totalWeight =
        addresses.stream()
            .mapToInt(address -> zone.weights().weightOf(result.name(), address))
            .sum();
    StringBuilder table = new StringBuilder();
    table.append(
        String.format(
            Locale.ROOT,
            "%s %d lookups, %d failed%n%-16s %6s %12s %10s %10s %8s  %s%n",
            result.name(),
            result.lookups(),
            result.failures(),
            "address",
            "weight",
            "configured %",
            "measured %",
            "difference",
            "allowed",
            "verdict"));
```

- `render` builds the table. `totalWeight` is the sum of the weights of the name's addresses (`mapToInt(...).sum()`). The first `append` writes a line with the name, the number of lookups and failures, and then the column titles. `%n` is a line break. `%-16s` aligns a text to the left in 16 characters; `%6s`, `%12s`, `%10s` and `%8s` align to the right in 6, 12, 10 and 8 characters.

```java
    for (InetAddress address : addresses) {
      int weight = zone.weights().weightOf(result.name(), address);
      double configured = (double) weight / totalWeight;
      double measured = result.shareOf(address);
      double allowed =
          STANDARD_ERRORS_ALLOWED * Math.sqrt(configured * (1 - configured) / result.lookups());
      double difference = measured - configured;
      table.append(
          String.format(
              Locale.ROOT,
              "%-16s %6d %12.2f %10.2f %+10.2f %8.2f  %s%n",
              address.getHostAddress(),
              weight,
              PERCENT * configured,
              PERCENT * measured,
              PERCENT * difference,
              PERCENT * allowed,
              Math.abs(difference) <= allowed ? "within" : "OUTSIDE"));
    }
```

- One row per address. `configured` is the weight divided by the total. `measured` comes from the result. `allowed` is `3 * sqrt(configured * (1 - configured) / lookups)`. `difference` is measured minus configured.
- The row prints the numbers as percentages (times 100). `%+10.2f` prints a decimal number with two digits and always a sign. The verdict is `within` if the absolute difference is at most `allowed`, else `OUTSIDE`.

```java
    table.append(
        String.format(
            Locale.ROOT,
            "lookup latency over UDP on 127.0.0.1: p50 %.3f ms, p99 %.3f ms, max %.3f ms%n",
            millis(result.latencyP50()),
            millis(result.latencyP99()),
            millis(result.latencyMax())));
    return table.toString();
  }

  private static double millis(Duration duration) {
    return duration.toNanos() / NANOS_PER_MILLI;
  }
}
```

- The last line shows the latency: p50, p99 and max in milliseconds. `millis` divides the nanoseconds of a `Duration` by a million.

### `sampling/SampleSettings.java`

```java
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
```

- The nine components of the `sample` command: the name, the type (A or AAAA), the number of measured lookups, the number of warm-up lookups (sent first and not counted, so class loading and the JIT compiler are not in the latency), the timeout, the target, the external port, the zone and the seed.
- `Target` is an enum defined at the end: `LOCAL` or `EXTERNAL`.

```java
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
```

- The limits, and a compact constructor that refuses nulls and a type other than A or AAAA (only those carry addresses to count).

```java
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
```

- `from` reads each key. `reader.enumValue("lookup.type", RecordType.class, RecordType.A)` turns the word `a` into `RecordType.A`. The port of an external server must be at least 1024. The zone comes from `ZoneLoader`: even for an external server the zone is read, because the configured weights are compared with the measured shares.
- If the name text is empty (missing key), `"."` is parsed instead, a stand-in. The reader's problems go first in the list: `problems.addAll(0, reader.problems())` inserts at index 0.
- If anything went wrong, or the zone did not load, the result is an error; otherwise the settings record.

```java
  /** Where the sampled server runs. */
  public enum Target {
    /** Start a lab server in this process, on any free port. */
    LOCAL,
    /** Ask a server already running on 127.0.0.1, for example CoreDNS from the Compose file. */
    EXTERNAL
  }
}
```

- The enum `Target`: start a lab server in this process on any free port, or ask a server already running on 127.0.0.1, for example CoreDNS from the Compose file.

### `sampling/SampleCli.java`

```java
package io.plainround.seatwave.dns.sampling;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.server.DnsServer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point of the weighted-routing test: it looks one name up many times through a
 * chosen server and prints the share of answers per address against the configured weights.
 * Everything stays on 127.0.0.1. Exit status 2 means bad usage or settings; 1 means a share fell
 * outside its tolerance or the server could not be started.
 */
public final class SampleCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;

  private SampleCli() {}
```

- Imports. The Javadoc defines the exit status: 2 for bad usage or settings; 1 when a share fell outside its tolerance or the server could not be started.

```java
  /**
   * Runs the sample.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: sample <sample.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (SampleSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<SampleSettings, List<String>>(SampleSettings settings) -> {
          String report = run(settings);
          System.out.print(report);
          if (report.contains("OUTSIDE")) {
            System.exit(EXIT_FAILED);
          }
        }
        case Result.Error<SampleSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("the sample failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

- `main` reads the settings; on success, it runs the sample, prints the report and exits with status 1 if the report contains the word `OUTSIDE`. This makes the command usable in a script: status 0 means every share was within tolerance.
- On error, each problem is printed after `invalid:` and the program exits with 2.

```java
  static String run(SampleSettings settings) throws IOException {
    return switch (settings.target()) {
      case LOCAL -> {
        try (DnsServer server =
            DnsServer.start(
                DnsServer.ANY_FREE_PORT,
                new ZoneStore(settings.zone(), Clock.systemUTC()),
                new SplittableRandom(settings.randomSeed()))) {
          yield header("the lab server", server.port(), settings) + sample(server.port(), settings);
        }
      }
      case EXTERNAL ->
          header("an external server", settings.externalPort(), settings)
              + sample(settings.externalPort(), settings);
    };
  }
```

- `run` is a `switch` expression over the target. For `LOCAL`: a `DnsServer` on `ANY_FREE_PORT` (the system picks one), with a `ZoneStore` and a `SplittableRandom` seeded from the settings. `yield` inside the `try` block gives the value: the header plus the sample. The server closes at the end of the `try`. For `EXTERNAL`: the same sample against the port from the settings.

```java
  private static String sample(int port, SampleSettings settings) {
    try (DnsClient client = new DnsClient(port, settings.timeout())) {
      if (settings.warmupLookups() > 0) {
        Sampler.sample(client, settings.name(), settings.type(), settings.warmupLookups());
      }
      SampleResult result =
          Sampler.sample(client, settings.name(), settings.type(), settings.lookups());
      return SampleReport.render(result, settings.zone());
    }
  }
```

- `sample` opens a `DnsClient` on the port. If warm-up is set, it runs and throws away that many lookups; then it runs the measured ones and renders the report with the zone's weights.

```java
  private static String header(String what, int port, SampleSettings settings) {
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s; %s on 127.0.0.1:%d (UDP); %s %s; %d warm-up lookups first%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        what,
        port,
        settings.type(),
        settings.name(),
        settings.warmupLookups());
  }
```

- `header` prints the line with the Java version, the operating system, the architecture, which server, its port, the type and the name, and the warm-up count.

### `experiment/SimulatedClock.java`

```java
package io.plainround.seatwave.dns.experiment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A clock that moves only when told to, so a simulation of hours runs in a second and every run
 * gives the same numbers. Not thread-safe: one simulation owns it.
 */
public final class SimulatedClock extends Clock {

  private Instant now;

  /**
   * Starts the clock.
   *
   * @param start the first instant
   */
  public SimulatedClock(Instant start) {
    this.now = Objects.requireNonNull(start, "start");
  }
```

- `Clock` is an abstract class of the JDK. A subclass says what "now" is. `SimulatedClock` holds an `Instant` that only changes when `advanceTo` is called, so a simulation of hours runs in a second and every run gives the same numbers. It is not thread-safe: one simulation owns it.

```java
  /**
   * Moves the clock forward.
   *
   * @param later the new instant, not before the current one
   */
  public void advanceTo(Instant later) {
    Objects.requireNonNull(later, "later");
    if (later.isBefore(now)) {
      throw new IllegalArgumentException("a simulated clock never goes back");
    }
    now = later;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("the simulation works in UTC only");
  }
}
```

- `advanceTo` refuses to go back (`isBefore`). `instant()` returns the stored time. `getZone()` says the time zone is UTC. `withZone` refuses: the simulation works in UTC only. These three methods are the abstract methods of `Clock`.

### `experiment/Arm.java`

```java
package io.plainround.seatwave.dns.experiment;

import java.util.Objects;

/**
 * One arm of the TTL-change experiment: the TTL the record starts with, the TTL it is lowered to
 * one old TTL before the switch (the same value means "not lowered"), and the share of resolvers
 * that pin answers for longer than the TTL.
 *
 * @param name the arm's name in the settings and the table
 * @param initialTtlSeconds the TTL the record carries at the start
 * @param loweredTtlSeconds the TTL after lowering; equal to the initial TTL for no lowering
 * @param pinningPercent the percent of resolvers that keep every answer at least the pinning hold
 * @param pinningHoldSeconds how long a pinning resolver keeps an answer at least
 */
public record Arm(
    String name,
    long initialTtlSeconds,
    long loweredTtlSeconds,
    int pinningPercent,
    long pinningHoldSeconds) {
```

- One **arm** of the experiment is one scenario: the TTL the record starts with, the TTL it is lowered to one old TTL before the switch (the same value means "not lowered"), the percent of resolvers that pin, and how long a pinning resolver keeps an answer at least.

```java
  private static final int ALL_PERCENT = 100;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the lowered TTL is above the initial one, or a value is out
   *     of range
   */
  public Arm {
    Objects.requireNonNull(name, "name");
    if (initialTtlSeconds < 1 || loweredTtlSeconds < 1 || loweredTtlSeconds > initialTtlSeconds) {
      throw new IllegalArgumentException("the lowered TTL must be from 1 to the initial TTL");
    }
    if (pinningPercent < 0 || pinningPercent > ALL_PERCENT || pinningHoldSeconds < 0) {
      throw new IllegalArgumentException(
          "the pinning share is a percent; the hold is not negative");
    }
  }

  /**
   * Says whether this arm lowers the TTL before the switch.
   *
   * @return true if the lowered TTL is below the initial one
   */
  public boolean isLowering() {
    return loweredTtlSeconds < initialTtlSeconds;
  }
}
```

- The constructor refuses a lowered TTL below 1 or above the initial one, and a pinning percent outside 0 to 100 or a negative hold. `isLowering()` says whether the lowered TTL is below the initial one.

### `experiment/ArmResult.java`

```java
package io.plainround.seatwave.dns.experiment;

import java.util.List;
import java.util.Objects;

/**
 * What one arm of the TTL-change experiment measured. A switch time of -1 means that share of
 * resolvers never saw the new address before the run ended.
 *
 * @param arm the arm
 * @param cacheHitRatio the share of resolver lookups answered from cache in the rate window
 * @param serverQueriesPerSecond the authoritative server's query rate in the rate window
 * @param switchSeconds the seconds after the switch at which 50, 95, 99 and 100 percent of
 *     resolvers first gave the new address
 * @param retireAfterSeconds the seconds after the switch from which the guard approves retiring the
 *     old address
 * @param onOldAtRetirePercent the percent of resolvers still giving the old address at that moment
 * @param guardLog every guard decision, in order
 */
public record ArmResult(
    Arm arm,
    double cacheHitRatio,
    double serverQueriesPerSecond,
    List<Long> switchSeconds,
    long retireAfterSeconds,
    double onOldAtRetirePercent,
    List<String> guardLog) {

  /** Checks and copies the fields. */
  public ArmResult {
    Objects.requireNonNull(arm, "arm");
    switchSeconds = List.copyOf(switchSeconds);
    guardLog = List.copyOf(guardLog);
  }
}
```

- What one arm measured: the cache hit ratio and the server's query rate in the window before the switch, the seconds after the switch at which 50, 95, 99 and 100 percent of resolvers first gave the new address (`-1` means never within the run), the seconds after the switch from which the guard approves retiring the old address, the percent of resolvers still giving the old address at that moment, and the log of every guard decision.

### `experiment/ExperimentSettings.java`

```java
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
```

- The settings of the simulation. `queryInterval` is how often each resolver is asked by its fans; `healthInterval` and `maxCheckGap` are for the health checks and the guard; `maximumHold` and `maximumNegativeHold` are the honest resolvers' policy; `rateWindow` is the window before the switch in which the server's query rate is measured.

```java
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
```

- `ARM_NAME` is a pattern for arm names (a lower-case letter, then up to 29 letters, digits or hyphens). `MAX_RESOLVERS` is 100,000; `MAX_SECONDS` is 604,800 (a week).
- The constructor refuses no arm and fewer than one resolver.

```java
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
```

- `from` reads the name and the two addresses as text, then each number with `intBetween`, and the durations with the helper `seconds`.

```java
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
```

- The arm names are read from `arm.names` and checked against the same pattern (the allow-list trick used in `ServeSettings`). For each arm four keys are read: `arm.<name>.ttl.seconds`, `.lowered.ttl.seconds`, `.pinning.percent` and `.pinning.hold.seconds`. A lowered TTL above the initial one is a problem; otherwise an `Arm` is added.

```java
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
```

- The zone is loaded. All problems are gathered. `parse` turns the three migration texts into a name and two addresses and may add problems. If anything is wrong, the result is an error.
- The check that the zone gives the name the old address stops a run that could never start. Then the settings record is made.

```java
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
```

- `parse` is package-private, so `LiveSettings` can use it as well. The name is parsed with `DomainName.parse` and a problem is recorded under `migration.name:`; the two addresses are parsed by `address`. The result is a `Parsed` record.

```java
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
```

- `address` returns the loopback address as a stand-in when the key was missing (the reader recorded that already). `InetAddress.ofLiteral` reads digits only and never looks a name up. A bad literal is a problem `not an address: ...`.

```java
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
```

- `seconds` reads a duration in seconds from 1 to a week. `Parsed` is a record of the three values.

### `experiment/TtlSimulation.java`

```java
package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.migration.ApprovedChange;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.MigrationRefusal;
import io.plainround.seatwave.dns.migration.Publication;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.InMemoryUpstream;
import io.plainround.seatwave.dns.resolver.Resolution;
import io.plainround.seatwave.dns.zone.Zone;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
```

- Imports: the guard, the store, the health history, the resolver and the clock.

```java
/**
 * The TTL-change experiment as a simulation on a {@link SimulatedClock}: many caching resolvers ask
 * for a name at a steady pace while a migration moves it to a new address through the {@link
 * MigrationGuard}, and the run records when each resolver first gives the new address.
 *
 * <p>The timeline of every arm, in seconds from the start: at 0 the record is published with the
 * arm's starting TTL; each resolver starts asking at a random moment of the first old TTL, so by
 * then every cache is warm and the cache ages are spread evenly; if the arm lowers the TTL, it does
 * so at one old TTL; the switch is at two old TTLs; one second after the switch the guard is asked,
 * too early, to approve retiring the old address. Both sites pass every health check. The
 * resolvers' questions go through the real {@code Responder} and message codec, without a socket.
 * Stateless and thread-safe; each run builds its own objects.
 */
public final class TtlSimulation {

  // Times are reported relative to the start; the absolute date does not matter.
  private static final Instant START = Instant.EPOCH;
  private static final double[] SWITCH_SHARES = {0.50, 0.95, 0.99, 1.00};
  private static final double PERCENT = 100;
  // The run continues this many query intervals past the last moment any cache can expire.
  private static final int TAIL_INTERVALS = 2;

  private TtlSimulation() {}
```

- The Javadoc is the timeline of every arm, in seconds from the start (see "How it works underneath"). Both sites pass every health check. The resolvers' questions go through the real `Responder` and the codec, without a socket.
- `START` is `Instant.EPOCH`; times are reported relative to the start. `SWITCH_SHARES` are the four shares reported: 50, 95, 99 and 100 percent. `TAIL_INTERVALS` is 2: the run goes two query intervals past the last moment any cache can expire.

```java
  /**
   * Runs one arm.
   *
   * @param settings the experiment's settings
   * @param arm the arm
   * @return what the arm measured
   */
  public static ArmResult run(ExperimentSettings settings, Arm arm) {
    long oldTtl = arm.initialTtlSeconds();
    long switchAt = 2 * oldTtl;
    long end =
        switchAt
            + Math.max(oldTtl, arm.pinningHoldSeconds())
            + TAIL_INTERVALS * settings.queryInterval().toSeconds();
    SimulatedClock clock = new SimulatedClock(START);
    ZoneStore store = new ZoneStore(startingZone(settings, oldTtl), clock);
    HealthHistory health = new HealthHistory();
    List<InetAddress> sites = new ArrayList<>(settings.zone().addressesOf(settings.name()));
    sites.add(settings.newAddress());
    for (long second = 0; second <= end; second += settings.healthInterval().toSeconds()) {
      for (InetAddress site : sites) {
        health.record(START.plusSeconds(second), site, true);
      }
    }
    Run run =
        new Run(
            settings,
            arm,
            clock,
            store,
            new MigrationGuard(store, health, clock, settings.maxCheckGap()),
            switchAt);
    run.ask(end);
    return run.result();
  }
```

- `run` for one arm: `oldTtl` is the arm's initial TTL; the switch is at twice that; the end is the switch plus the longer of the old TTL and the pinning hold, plus two query intervals.
- It makes the clock, the store (with the zone's TTLs set to the arm's initial TTL by `startingZone`), the health history, and the list of sites: the name's current addresses plus the new address.
- The loop records a healthy check for every site every `healthInterval` seconds, from 0 to the end: both sites pass every check.
- It builds a `Run`, with a `MigrationGuard` over the store, the history and the clock, then `ask(end)` and `result()`.

```java
  private static Zone startingZone(ExperimentSettings settings, long ttlSeconds) {
    List<ResourceRecord> records =
        settings.zone().records().stream()
            .map(record ->
                record.owner().equals(settings.name())
                        && (record.type() == RecordType.A || record.type() == RecordType.AAAA)
                    ? record.withTtl(ttlSeconds)
                    : record)
            .toList();
    return switch (settings.zone().with(records, settings.zone().weights())) {
      case Result.Success<Zone, List<String>>(Zone zone) -> zone;
      case Result.Error<Zone, List<String>>(List<String> problems) ->
          throw new IllegalStateException("a TTL change broke a valid zone: " + problems);
    };
  }
```

- `startingZone` sets the TTL of the name's A and AAAA records to the arm's initial TTL and builds the changed zone with `zone.with(...)`, which validates it. A valid zone cannot become invalid by a TTL change, so an error here is a bug and throws `IllegalStateException`.

```java
  /** One arm's moving parts. Confined to one call of {@link #run}. */
  private static final class Run {
    private final ExperimentSettings settings;
    private final Arm arm;
    private final SimulatedClock clock;
    private final ZoneStore store;
    private final MigrationGuard guard;
    private final long switchAt;
    private final InMemoryUpstream upstream;
    private final List<CachingResolver> resolvers = new ArrayList<>();
    private final long[] phases;
    private final long[] firstRounds;
    private final long[] firstNew;
    private final List<String> log = new ArrayList<>();
    private boolean hasLowered;
    private boolean hasSwitched;
    private boolean hasTriedRetire;
    private long windowLookups;
    private long windowQueries;

```

- `Run` is a private nested class that holds one arm's moving parts. The arrays `phases`, `firstRounds` and `firstNew` have one entry per resolver. `hasLowered`, `hasSwitched` and `hasTriedRetire` record which steps of the timeline have happened. `windowLookups` and `windowQueries` count lookups and server queries in the rate window.

```java
    Run(
        ExperimentSettings settings,
        Arm arm,
        SimulatedClock clock,
        ZoneStore store,
        MigrationGuard guard,
        long switchAt) {
      this.settings = settings;
      this.arm = arm;
      this.clock = clock;
      this.store = store;
      this.guard = guard;
      this.switchAt = switchAt;
      SplittableRandom random = new SplittableRandom(settings.seed());
      this.upstream = new InMemoryUpstream(store, random.split());
      int pinning = (int) Math.round(settings.resolvers() * arm.pinningPercent() / PERCENT);
      phases = new long[settings.resolvers()];
      firstRounds = new long[settings.resolvers()];
      firstNew = new long[settings.resolvers()];
      Arrays.fill(firstNew, -1);
      for (int index = 0; index < settings.resolvers(); index++) {
        Duration minimumHold =
            index < pinning ? Duration.ofSeconds(arm.pinningHoldSeconds()) : Duration.ZERO;
        CachePolicy policy =
            new CachePolicy(
                minimumHold,
                settings.maximumHold().compareTo(minimumHold) < 0
                    ? minimumHold
                    : settings.maximumHold(),
                settings.maximumNegativeHold(),
                Duration.ZERO);
        resolvers.add(new CachingResolver(upstream, clock, policy));
        phases[index] = random.nextLong(settings.queryInterval().toSeconds());
        // Each resolver first asks at a random moment of the first old TTL, so cache ages are
        // spread as they are on the internet, not all filled at the same second.
        firstRounds[index] =
            random.nextLong(
                Math.max(1, arm.initialTtlSeconds() / settings.queryInterval().toSeconds()));
      }
    }
```

- The constructor builds the in-memory upstream (the weighted choice is seeded from the settings), and the resolvers. `pinning` is the number of pinning resolvers: the percentage of the total, rounded.
- The first `pinning` resolvers get a minimum hold equal to the pinning hold; the others have zero. Each gets a `CachePolicy` (the maximum hold is raised to the minimum if the minimum is larger, because the policy requires minimum ≤ maximum), and a `CachingResolver` on the shared clock.
- `phases[index]` is a random offset within the query interval. `firstRounds[index]` is the round (a count of query intervals) in which the resolver first asks: a random number from 0 up to (not including) the number of query intervals in the initial TTL, with 1 as the smallest bound. The comment gives the reason: cache ages are spread as they are on the internet, and not all filled in the same second. `Arrays.fill(firstNew, -1)` marks every resolver as "has not seen the new address".

```java
    void ask(long end) {
      long interval = settings.queryInterval().toSeconds();
      Integer[] order = new Integer[phases.length];
      Arrays.setAll(order, index -> index);
      // Resolvers are visited in the order of their phase, so time only moves forward.
      Arrays.sort(order, (left, right) -> Long.compare(phases[left], phases[right]));
      for (long round = 0; round * interval <= end; round++) {
        for (int index : order) {
          long second = phases[index] + round * interval;
          if (second > end) {
            break;
          }
          if (round < firstRounds[index]) {
            continue;
          }
          act(second);
          clock.advanceTo(START.plusSeconds(second));
          lookUp(index, second);
        }
      }
    }
```

- `ask(end)` runs the simulation. The resolvers are visited in the order of their phase, so time only moves forward (the comment). `Integer[] order` holds the indexes; `Arrays.sort` with a comparator sorts them by phase (`Arrays.setAll(order, index -> index)` fills the array with 0, 1, 2 ...).
- For each round, for each resolver in phase order, the second is `phase + round * interval`. A second past the end stops the inner loop. A resolver that has not yet started (`round < firstRounds[index]`) is skipped.
- Then, in this order: `act(second)` lets the migration steps that are due happen; the clock advances to this second; the resolver looks the name up.

```java
    private void lookUp(int index, long second) {
      long queriesBefore = upstream.queries();
      Resolution resolution = resolvers.get(index).resolve(settings.name(), RecordType.A);
      if (second >= switchAt - settings.rateWindow().toSeconds() && second < switchAt) {
        windowLookups++;
        windowQueries += upstream.queries() - queriesBefore;
      }
      List<InetAddress> addresses = resolution.addresses();
      boolean isNew = !addresses.isEmpty() && addresses.getFirst().equals(settings.newAddress());
      if (hasSwitched && isNew && firstNew[index] < 0) {
        firstNew[index] = second - switchAt;
      }
    }
```

- `lookUp` makes one resolver resolve the name for type A and counts the upstream queries it caused. If the second lies in the rate window (the `rateWindow` seconds before the switch), the lookups and queries are added to the window counters. If the resolution gives the new address first, and the switch has happened, and this resolver had not seen it yet, `firstNew[index]` is set to the seconds after the switch.

```java
    private void act(long second) {
      if (arm.isLowering() && !hasLowered && second >= arm.initialTtlSeconds()) {
        hasLowered = true;
        clock.advanceTo(START.plusSeconds(arm.initialTtlSeconds()));
        record(guard.changeTtl(settings.name(), arm.loweredTtlSeconds()));
      }
      if (!hasSwitched && second >= switchAt) {
        hasSwitched = true;
        clock.advanceTo(START.plusSeconds(switchAt));
        record(guard.switchAddress(settings.name(), settings.oldAddress(), settings.newAddress()));
      }
      if (hasSwitched && !hasTriedRetire && second > switchAt) {
        hasTriedRetire = true;
        clock.advanceTo(START.plusSeconds(switchAt + 1));
        switch (guard.approveRetirement(settings.name(), settings.oldAddress())) {
          case Result.Success<Instant, MigrationRefusal>(Instant approved) ->
              log.add(
                  at(approved)
                      + " retire "
                      + settings.oldAddress().getHostAddress()
                      + ": approved");
          case Result.Error<Instant, MigrationRefusal>(MigrationRefusal refusal) ->
              log.add(
                  at(clock.instant())
                      + " retire "
                      + settings.oldAddress().getHostAddress()
                      + ": "
                      + brief(refusal));
        }
      }
    }
```

- `act` is the timeline. If the arm lowers the TTL and it has not yet, and the time has reached the old TTL: the clock moves to exactly that second, and the guard is asked to change the TTL. If the time has reached the switch: the clock moves to the switch second and the guard is asked to switch the address. If the switch has happened and retirement was not yet tried and the time is past the switch: the clock moves one second past the switch, and the guard is asked to approve retirement; the answer is logged either way.
- The `switch` over the `Result` has a success arm and an error arm; the error arm logs `refused, too early; allowed from t=...` through `brief`.

```java
    private void record(Result<ApprovedChange, MigrationRefusal> decision) {
      switch (decision) {
        case Result.Success<ApprovedChange, MigrationRefusal>(ApprovedChange change) -> {
          if (store.apply(change) instanceof Result.Success<Publication, MigrationRefusal>) {
            log.add(at(clock.instant()) + " applied: " + change.description());
          }
        }
        case Result.Error<ApprovedChange, MigrationRefusal>(MigrationRefusal refusal) ->
            log.add(at(clock.instant()) + " " + refusal.describe());
      }
    }
```

- `record` takes the guard's decision. An approved change is applied through the store (`store.apply(change)`) and logged; `instanceof Result.Success<...>` tests the type without unpacking. A refusal is logged with its `describe()`.

```java
    private String brief(MigrationRefusal refusal) {
      return switch (refusal) {
        case MigrationRefusal.TooEarly(Instant earliest, String reason) ->
            "refused, too early; allowed from " + at(earliest);
        case MigrationRefusal.UnhealthySite unhealthy -> unhealthy.describe();
        case MigrationRefusal.BadRequest bad -> bad.describe();
      };
    }

    private static String at(Instant instant) {
      return String.format(Locale.ROOT, "t=%ds", Duration.between(START, instant).toSeconds());
    }
```

- `brief` turns a refusal into a short text (a `switch` over the sealed `MigrationRefusal`). `at` formats a time as `t=7200s`.

```java
    ArmResult result() {
      long[] sorted = firstNew.clone();
      // A resolver that never switched sorts last.
      for (int index = 0; index < sorted.length; index++) {
        sorted[index] = sorted[index] < 0 ? Long.MAX_VALUE : sorted[index];
      }
      Arrays.sort(sorted);
      List<Long> shares = new ArrayList<>();
      for (double share : SWITCH_SHARES) {
        long value = sorted[(int) Math.ceil(share * sorted.length) - 1];
        shares.add(value == Long.MAX_VALUE ? -1 : value);
      }
      long retireAfter =
          guard
              .earliestRetirement(settings.name(), settings.oldAddress())
              .map(earliest -> Duration.between(START.plusSeconds(switchAt), earliest).toSeconds())
              .orElse(-1L);
      long stillOld =
          Arrays.stream(firstNew).filter(seen -> seen < 0 || seen > retireAfter).count();
      return new ArmResult(
          arm,
          windowLookups == 0 ? 0 : 1 - (double) windowQueries / windowLookups,
          (double) windowQueries / settings.rateWindow().toSeconds(),
          shares,
          retireAfter,
          PERCENT * stillOld / firstNew.length,
          log);
    }
  }
}
```

- `result` computes the numbers. It copies `firstNew`, replaces `-1` (never) with the largest `long` so that it sorts last, and sorts. For each share (0.50, 0.95, 0.99, 1.00) it picks the element at position `ceil(share * n) - 1`; a value that is the largest `long` is reported as `-1`.
- `retireAfter` is the guard's earliest retirement time, minus the switch time, in seconds. `stillOld` counts the resolvers that had not seen the new address by then (`seen < 0 || seen > retireAfter`).
- The record is made from: the hit ratio (`1 - queries / lookups`), the server query rate (queries divided by the window length in seconds), the shares, the retirement time, the percent still on the old address, and the log.

### `experiment/TtlExperimentCli.java`

```java
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
```

- The command. Its Javadoc says it is a model and says so in its first line.

```java
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
```

- `main` reads the settings and prints the result of `render`. Bad settings or an unreadable file exit with status 2.

```java
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
```

- `render` builds the output in a `StringBuilder`. The first block is the text that starts with `MODEL, not a measurement.` and states the setup: the number of resolvers, the name, the interval, the seed, the two addresses, the health interval, and the rate window. In a Java string, `"..."` followed by `+ "..."` on the next line is one long text cut into parts only to fit the line.
- The second block is the column titles. Then each arm is run (`TtlSimulation.run`) and a row is added; the guard log lines are collected with the arm's name in front. At the end the guard decisions are printed, two spaces in, one per line.

```java
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
```

- `row` formats one arm: its name, the TTLs, the lowered TTL (or `no`), the pinning percent, the server rate, the hit ratio, the four times, the retirement time and the percent still on the old address. `%7d%%` prints a whole number followed by a percent sign (`%%` is a literal `%`).

### `experiment/LiveSettings.java`

```java
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
```

- The live run's settings. The Javadoc explains the difference to the simulation: it uses seconds where the simulation uses hours.

```java
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
```

- The components, in the order of the file: the server port, the zone, the name and the two addresses, the lowered TTL, the TCP ports of the two sites, the health interval, the largest gap, the health timeout, the poll interval, the number of honest clients, the pinning hold, the JVM cache time, the lookup timeout, how long to watch after the switch and the seed.
- The limits: 60 seconds for the millisecond settings, 600 seconds for the second settings, and 20 clients.

```java
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
```

- `from` reads each key with its range, loads the zone, gathers the problems and parses the three migration values with `ExperimentSettings.parse` (shared code). If any problem exists, the result is an error; otherwise the record.

### `experiment/SiteEndpoints.java`

```java
package io.plainround.seatwave.dns.experiment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stand-ins for the two SeatWave sites: one TCP listener on the loopback address per site address,
 * so a health check is a real TCP connection with a real timeout. The site addresses themselves are
 * documentation addresses that nothing answers, which is why each is mapped to a local port.
 * Thread-safe.
 */
public final class SiteEndpoints implements AutoCloseable {
```

- The two sites of the module are documentation addresses that nothing answers. The stand-ins are two TCP listeners on the loopback address, one per site. A **health check** is then a real TCP connection with a real timeout.
- A **TCP connection** is a two-way channel between two programs, set up by a handshake. A `ServerSocket` waits for connections; a `Socket` is one end of a connection.

```java
  private final Map<InetAddress, ServerSocket> listeners;

  private SiteEndpoints(Map<InetAddress, ServerSocket> listeners) {
    this.listeners = Map.copyOf(listeners);
  }

  /**
   * Opens one listener per site and accepts connections on a virtual thread each.
   *
   * @param portBySite the loopback port that stands for each site address
   * @return the running stand-ins
   * @throws IOException if a port is taken
   */
  public static SiteEndpoints start(Map<InetAddress, Integer> portBySite) throws IOException {
    Map<InetAddress, ServerSocket> listeners = new LinkedHashMap<>();
    for (Map.Entry<InetAddress, Integer> site : portBySite.entrySet()) {
      ServerSocket listener = new ServerSocket();
      listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), site.getValue()));
      listeners.put(site.getKey(), listener);
      Thread.ofVirtual().name("site-" + site.getValue()).start(() -> accept(listener));
    }
    return new SiteEndpoints(listeners);
  }
```

- `start` takes the loopback port that stands for each site address. For each: a `ServerSocket` is created and bound to 127.0.0.1 and that port; it is stored; and a **virtual thread** is started that accepts connections (`Thread.ofVirtual().name(...).start(() -> accept(listener))`, Java 21). A virtual thread is a very light thread managed by the JDK; one per listener costs almost nothing.

```java
  /**
   * Checks one site: a TCP connection to its stand-in, within a timeout.
   *
   * @param site the site address
   * @param timeout the longest wait for the connection
   * @return true if the connection opened in time
   */
  public boolean isHealthy(InetAddress site, Duration timeout) {
    ServerSocket listener = Objects.requireNonNull(listeners.get(site), "unknown site");
    try (Socket probe = new Socket()) {
      probe.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
          Math.toIntExact(timeout.toMillis()));
      return true;
    } catch (IOException refusedOrTimedOut) {
      // A refused or slow connection is exactly what a failed health check is.
      return false;
    }
  }
```

- `isHealthy` opens a `Socket` to the site's stand-in with a connect timeout: `probe.connect(address, millis)`. If the connection opens in time, the check is true. A refused or slow connection throws `IOException`, which is exactly what a failed health check is, so it returns false. The `Socket` closes at the end of the `try`.
- `Objects.requireNonNull(listeners.get(site), "unknown site")` stops a check of an address the object does not know.

```java
  /** Closes every listener. */
  @Override
  public void close() {
    listeners.values().forEach(SiteEndpoints::closeQuietly);
  }
```

- `close` closes every listener through `closeQuietly`.

```java
  private static void accept(ServerSocket listener) {
    while (!listener.isClosed()) {
      try (Socket connection = listener.accept()) {
        // The check needs only the connection; closing it at once is the whole answer.
        connection.shutdownOutput();
      } catch (SocketException closed) {
        return;
      } catch (IOException failed) {
        throw new UncheckedIOException("a site stand-in failed", failed);
      }
    }
  }
```

- `accept` loops while the listener is open. `listener.accept()` blocks until a connection arrives. The connection is closed at once (`shutdownOutput()` and the end of the `try`): the check needs only the connection. When the listener is closed, `accept` throws `SocketException`, which ends the loop; any other `IOException` is unexpected and becomes an `UncheckedIOException`.

```java
  private static void closeQuietly(ServerSocket listener) {
    try {
      listener.close();
    } catch (IOException failed) {
      throw new UncheckedIOException("cannot close a site stand-in", failed);
    }
  }
}
```

- `closeQuietly` closes one listener, wrapping an `IOException`.

### `experiment/LiveMigration.java`

```java
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

```

- Imports, including three functional interfaces: `Consumer<T>` (a function that takes a value and returns nothing), `Predicate<T>` (a function that returns true or false) and `Supplier<T>` (a function that takes nothing and returns a value).

```java
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

```

- The Javadoc: the loop of the live run, on an injected clock and an injected pause. Every tick it checks both sites when a health interval has passed, takes the next migration step the timeline allows, and lets every client look the name up. The loop ends when retirement is approved and every client gives the new address, or when the watch time after the switch runs out.

```java
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
```

- The constructor keeps the settings, the parts, the clock and the pause.

```java
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
```

- `run` returns, for each client by name, the seconds after the switch at which it first gave the new address.
- `oldTtl` is the guard's old TTL window for the name (10 seconds for the live zone). The TTL is lowered at one old TTL after the start, the address is switched at two, and the run ends `observeAfterSwitch` after the switch.
- The loop runs while the clock is before the end: check the sites, take the next step, let every client look up the name (`watch`). When retirement is done and every client has seen the new address, `break`. Otherwise `pause.pause(settings.pollInterval())` waits.
- At the end, the map from the client index to the first-seen moment is turned into seconds after the switch, in the order of the clients (`LinkedHashMap`).

```java
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
```

- `checkSites`: if less than one health interval has passed since the last check, return. Otherwise record, for each of the two sites, the result of `siteCheck.test(site)` in the health history, with the time. `Predicate.test` calls the predicate.

```java
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
```

- `step` takes the next step of the timeline that is due:
  1. At the lowering time, once: the guard is asked to change the TTL; the line logged is the outcome.
  2. At the switch time, once: the switch is asked; then immediately retirement, which the guard refuses; the earliest retirement time is stored for later.
  3. When that time has come, once: retirement is asked again, and now approved.
- `line(...)` prefixes each message with the time since the start.

```java
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
```

- `watch` makes one client look the name up (`client.lookup().get()`). If its answer changed since the last tick, the new answer is logged: `honest-1   now gets 203.0.113.30`. If the switch has happened and the answer is the new address, the first such moment is stored (`putIfAbsent` stores only if no value is there yet).

```java
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
```

- `apply` turns the guard's decision into text. A nested `switch` expression: an approved change is applied to the store; if the store accepts it, the text is `guard approved, applied: ...`; if the store refuses, its reason. A refusal from the guard gives its `describe()`.

```java
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
```

- `retire` asks the guard to approve retirement of the old address and describes the answer. The second arm uses a **nested record pattern**: `MigrationRefusal.TooEarly(var earliest, var why)` inside `Result.Error<...>(...)`. The text: `retire 192.0.2.10: refused: ...; allowed from t=24.094 s`.

```java
  private String line(State state, String text) {
    return String.format(Locale.ROOT, "t=%7.3f s  %s", seconds(state.start, clock.instant()), text);
  }

  private static double seconds(Instant from, Instant to) {
    return Duration.between(from, to).toMillis() / MILLIS_PER_SECOND;
  }

```

- `line` writes the elapsed time in the form `t=  20.094 s`: `%7.3f` is a decimal number with three digits after the point in 7 characters. `seconds` converts the time between two instants to seconds with a fraction (`toMillis() / 1000`).

```java
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

```

- `Pause` is a functional interface for "wait this long"; it may throw `InterruptedException`. A test passes a pause that advances the simulated clock.

```java
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

```

- `Client` is a record of a name and a `Supplier<String>` that looks the name up once and returns the address it got, or why none.

```java
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

```

- `Parts` bundles the store, the health history, the guard and a `Predicate<InetAddress>` that checks one site.

```java
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
```

- `State` holds the progress of one run: the start, the last answers, the map from client index to first-seen moment, the switch time, the last check, the retirement time and four booleans.
- The constructor puts `lastCheck` one interval in the past, so the first tick checks both sites at once (the comment). The list of last answers starts with empty texts.

### `experiment/TtlLiveCli.java`

```java
package io.plainround.seatwave.dns.experiment;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.jdk.LabResolverProvider;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.migration.HealthHistory;
import io.plainround.seatwave.dns.migration.MigrationGuard;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.CachePolicy;
import io.plainround.seatwave.dns.resolver.CachingResolver;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.server.DnsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

```

- Imports, including `LabResolverProvider` (for the two property names) and `Security`.

```java
/**
 * Command-line entry point of the live TTL-change run: a real UDP server on 127.0.0.1, two site
 * stand-ins with real TCP health checks, and clients that look the name up on the wall clock:
 * honest caching resolvers, one that pins answers longer than the TTL, and the JVM's own {@code
 * InetAddress} with its own cache. {@link LiveMigration} runs the timeline. Exit status 2 means bad
 * usage or settings; 1 means the run failed.
 */
public final class TtlLiveCli {

  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  // An honest client keeps an answer for its TTL, up to a day.
  private static final Duration ONE_DAY = Duration.ofDays(1);

  private TtlLiveCli() {}

```

- The Javadoc: a real UDP server on 127.0.0.1, two site stand-ins with real TCP health checks, and clients that look the name up on the wall clock. `ONE_DAY` is the cap on how long an honest client keeps an answer.

```java
  /**
   * Runs the live experiment.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while waiting
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: ttlLive <ttl-live.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (LiveSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<LiveSettings, List<String>>(LiveSettings settings) -> run(settings);
        case Result.Error<LiveSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("the live run failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

- `main` follows the same pattern as the other commands: one argument, read the settings, run or print the problems and exit with 2; an `IOException` exits with 1.

```java
  private static void run(LiveSettings settings) throws IOException, InterruptedException {
    // Both must be set before the first lookup: the JVM reads its cache policy and loads the
    // resolver provider once, at the first lookup.
    Security.setProperty(
        "networkaddress.cache.ttl", Long.toString(settings.jvmCacheTtl().toSeconds()));
    System.setProperty(LabResolverProvider.PORT_PROPERTY, Integer.toString(settings.serverPort()));
    System.setProperty(
        LabResolverProvider.TIMEOUT_PROPERTY, Long.toString(settings.lookupTimeout().toMillis()));
    Clock clock = Clock.systemUTC();
    ZoneStore store = new ZoneStore(settings.zone(), clock);
    HealthHistory health = new HealthHistory();
    Map<InetAddress, Integer> sitePorts = new LinkedHashMap<>();
    sitePorts.put(settings.oldAddress(), settings.oldSitePort());
    sitePorts.put(settings.newAddress(), settings.newSitePort());
    List<DnsClient> sockets = new ArrayList<>();
    try (SiteEndpoints sites = SiteEndpoints.start(sitePorts);
        DnsServer server =
            DnsServer.start(
                settings.serverPort(), store, new SplittableRandom(settings.randomSeed()))) {
      MigrationGuard guard = new MigrationGuard(store, health, clock, settings.maxCheckGap());
      LiveMigration.Parts parts =
          new LiveMigration.Parts(
              store, health, guard, site -> sites.isHealthy(site, settings.healthTimeout()));
      List<LiveMigration.Client> clients = clients(settings, server.port(), clock, sockets);
      System.out.print(header(settings, guard.oldTtlWindowSeconds(settings.name())));
      Map<String, Double> sawNew =
          new LiveMigration(settings, parts, clock, Thread::sleep)
              .run(clients, System.out::println);
      System.out.printf(
          Locale.ROOT,
          "%nfirst answer with %s, seconds after the switch:%n",
          settings.newAddress().getHostAddress());
      for (LiveMigration.Client client : clients) {
        System.out.printf(
            Locale.ROOT,
            "  %-10s %s%n",
            client.name(),
            sawNew.containsKey(client.name())
                ? String.format(Locale.ROOT, "%.3f", sawNew.get(client.name()))
                : "never");
      }
    } finally {
      sockets.forEach(DnsClient::close);
    }
  }
```

- `run` first sets the JVM's cache time and the provider's two system properties. The comment says why: they must be set before the first lookup.
- It makes the clock, the store and the health history, and a map from each site address to its stand-in port.
- `try (SiteEndpoints sites = ...; DnsServer server = ...)` opens two resources; they are closed in reverse order at the end. The server listens on the port from the settings.
- The guard is built over the store, the history and the clock. `Parts` gets a predicate `site -> sites.isHealthy(site, healthTimeout)`, a lambda that does a real TCP check.
- The clients are made, the header is printed, and `new LiveMigration(settings, parts, clock, Thread::sleep).run(clients, System.out::println)` runs the timeline. `Thread::sleep` is a method reference that matches `Pause`; `System.out::println` matches `Consumer<String>`.
- Then the table of first answers is printed: a client that never saw the new address prints `never`.
- The `finally` block closes the `DnsClient` sockets.

```java
  private static List<LiveMigration.Client> clients(
      LiveSettings settings, int port, Clock clock, List<DnsClient> sockets) {
    List<LiveMigration.Client> clients = new ArrayList<>();
    for (int index = 0; index <= settings.honestClients(); index++) {
      boolean isPinning = index == settings.honestClients();
      DnsClient socket = new DnsClient(port, settings.lookupTimeout());
      sockets.add(socket);
      Duration minimumHold = isPinning ? settings.pinningHold() : Duration.ZERO;
      CachingResolver resolver =
          new CachingResolver(
              socket, clock, new CachePolicy(minimumHold, ONE_DAY, ONE_DAY, Duration.ZERO));
      String name = isPinning ? "pinning" : "honest-" + (index + 1);
      clients.add(new LiveMigration.Client(name, () -> resolverAnswer(resolver, settings.name())));
    }
    clients.add(new LiveMigration.Client("jvm", () -> jvmAnswer(settings.name())));
    return clients;
  }
```

- `clients` makes the honest clients (`index` from 0 to `honestClients - 1`), then the pinning client (the last index, `isPinning`), each with its own `DnsClient` socket and a `CachingResolver` with a `CachePolicy` of (minimum hold, one day, one day, no stale). The pinning client's minimum hold is the pinning hold; the others have zero.
- The name is `pinning` or `honest-N`. The last client is `jvm`: it calls `InetAddress.getByName`.

```java
  private static String resolverAnswer(CachingResolver resolver, DomainName name) {
    List<InetAddress> addresses = resolver.resolve(name, RecordType.A).addresses();
    return addresses.isEmpty() ? "no address" : addresses.getFirst().getHostAddress();
  }

  private static String jvmAnswer(DomainName name) {
    try {
      return InetAddress.getByName(name.toString()).getHostAddress();
    } catch (UnknownHostException failed) {
      return "UnknownHostException " + failed.getMessage();
    }
  }

```

- `resolverAnswer` resolves the name for type A and returns the first address as text, or `no address`. `jvmAnswer` calls `InetAddress.getByName(name.toString())`, which goes through the provider and the JVM's cache, and returns the address, or the text of the exception.

```java
  private static String header(LiveSettings settings, long oldTtl) {
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s; lab server on 127.0.0.1:%d (UDP); %s A %s, TTL %d s;"
            + " site stand-ins on 127.0.0.1:%d and :%d (TCP)%n"
            + "clients: %d honest, 1 pinning (keeps answers %d s at least), 1 JVM InetAddress"
            + " (networkaddress.cache.ttl=%d); each looks up every %d ms%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        settings.serverPort(),
        settings.name(),
        settings.oldAddress().getHostAddress(),
        oldTtl,
        settings.oldSitePort(),
        settings.newSitePort(),
        settings.honestClients(),
        settings.pinningHold().toSeconds(),
        settings.jvmCacheTtl().toSeconds(),
        settings.pollInterval().toMillis());
  }
}
```

- `header` prints the line with the Java version, the system, the server port, the name, the old address and TTL, the stand-in ports, the clients and the poll interval.

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only.

**1. Run the weighted-routing test.**

```text
./gradlew -q sample --args='config/sample.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; the lab server on 127.0.0.1:63936 (UDP); A book.seatwave.example.; 1000 warm-up lookups first
book.seatwave.example. 10000 lookups, 0 failed
address          weight configured % measured % difference  allowed  verdict
192.0.2.10           50        50.00      50.48      +0.48     1.50  within
198.51.100.20        50        50.00      49.52      -0.48     1.50  within
lookup latency over UDP on 127.0.0.1: p50 0.040 ms, p99 0.059 ms, max 1.223 ms
```

The port and the latency change on every run. The shares do not: the seed is fixed. `1.50` is three standard errors for a 50 percent share over 10,000 lookups.

**2. Change the weights to 90 and 10.**

```text
./gradlew -q sample --args='config/sample-90-10.properties'
```

```text
book.seatwave.example. 10000 lookups, 0 failed
address          weight configured % measured % difference  allowed  verdict
192.0.2.10           90        90.00      90.29      +0.29     0.90  within
198.51.100.20        10        10.00       9.71      -0.29     0.90  within
```

The allowed difference is smaller (0.90) because the standard error is smaller for a share near 100 percent.

**3. Run the simulation.** It runs five arms in about ten seconds.

```text
./gradlew -q ttlExperiment --args='config/ttl-experiment.properties'
```

```text
arm                   TTL s  lowered  pinning  server/s hit ratio   50% s   95% s   99% s   100% s  retire s old at ret
one-hour               3600       no       0%      0.28    0.9972    1853    3417    3571     3598      7200       0.0%
one-hour-lowered       3600       60       0%      8.24    0.9176      30      57      59       59       120       0.0%
one-day               86400       no       0%      0.01    0.9999   42090   82083   85966    86377    172800       0.0%
one-day-lowered       86400       60       0%     16.32    0.8368      30      57      59       59       120       0.0%
lowered-pinning        3600       60       5%      7.89    0.9211      32      59    1550     1736       120       4.8%
```

(This is the table part of the output; the lines above and below it are in the README.) Read it row by row. Lowering the TTL to 60 seconds makes half of the resolvers switch after 30 seconds instead of 1,853 (one hour) or 42,090 seconds (one day), at the price of 8.24 or 16.32 queries per second at the server instead of 0.28 or 0.01. In the last row, 5 percent of resolvers pin answers for 30 minutes: the 99th percentile jumps from 59 to 1,550 seconds, and 4.8 percent are still on the old address when retirement would be allowed.

**4. Run the live experiment.** About half a minute:

```text
./gradlew -q ttlLive --args='config/ttl-live.properties'
```

```text
t= 10.031 s  guard approved, applied: seatwave.example. TTL 10 -> 2 seconds
t= 20.094 s  guard approved, applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
t= 20.104 s  retire 192.0.2.10: refused: caches may still send fans to 192.0.2.10; keep it serving; allowed from t=24.094 s
t= 20.366 s  honest-1   now gets 203.0.113.30
t= 24.216 s  guard approved: retire 192.0.2.10
t= 30.098 s  pinning    now gets 203.0.113.30
t= 30.103 s  jvm        now gets 203.0.113.30
```

The honest clients switch 0.3 seconds after the switch; the pinning client and the JVM's own cache switch 10 seconds after it, because both keep the old answer for 30 seconds from the first lookup.

**5. Give a command a bad settings file.** In a scratch folder, make a copy of `config/sample.properties` with `lookup.count=0` and without the `random.seed` line, and run `./gradlew -q sample --args='<path of your copy>'`:

```text
invalid: lookup.count must be from 1 to 1000000, but was 0
invalid: random.seed is missing
```

One `invalid:` line for each mistake, in one run. Gradle then reports that the process finished with exit value 2, the program's "used incorrectly" status.

## Mistakes people make

- **Letting a default hide a missing key.** There are no defaults. A missing key is an error that names the key.
- **Stopping at the first mistake in a settings file.** The reader collects every problem so one run shows all of them.
- **Comparing a measured share with the weight exactly.** Random samples vary; the report allows three standard errors.
- **Measuring cold code.** The first lookups include class loading and the JIT compiler (just-in-time compiler), so the sampler throws away a warm-up first.
- **Using the wall clock in a model.** A simulation that reads `Instant.now()` could not repeat. Time is injected.
- **Sharing a random generator between threads.** Each simulation and each server has its own, split from a seed.
- **Assuming the JVM follows a record's TTL.** It keeps an answer for `networkaddress.cache.ttl`; the live run's `jvm` client shows that.

## Check yourself

1. Why does `SettingsReader` return a stand-in value instead of throwing for a bad key? (So that all the keys are read and every problem is reported in one run; the caller checks `problems()` before using a value.)
2. What is the standard error of a 50 percent share over 10,000 lookups, and how many are allowed? (0.5 percentage points; three of them, 1.5.)
3. Why does the simulation start each resolver at a random moment of the first old TTL? (So that the ages of the cached answers are spread evenly, as on the internet, and not all equal.)
4. What does `Thread::sleep` do in `TtlLiveCli`, and what does a test pass instead? (It is the `Pause` that waits for real; a test passes a pause that advances a `SimulatedClock`.)
5. Why does the pinning client switch about 10 seconds after the others in the live run? (It keeps every answer at least 30 seconds from the first lookup, longer than the 2-second TTL.)

## Further reading

- The Java SE 25 API, `java.util.Properties`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- The Java SE 25 API, `java.time.Clock`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/Clock.html
- The Java SE 25 API, `java.util.SplittableRandom`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/SplittableRandom.html
- The Java SE 25 API, `java.net.ServerSocket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ServerSocket.html
- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- RFC 2181, Clarifications to the DNS Specification: https://www.rfc-editor.org/rfc/rfc2181
- RFC 5737, IPv4 Address Blocks Reserved for Documentation: https://www.rfc-editor.org/rfc/rfc5737
- CoreDNS `loadbalance` plugin: https://coredns.io/plugins/loadbalance/
