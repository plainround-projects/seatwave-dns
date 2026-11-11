# The `zone` and `server` packages, line by line

## What it is

This file reads the Java code of two packages, one line at a time.

- The `zone` package has five files: `ZoneFile.java`, `Weights.java`, `Zone.java`, `Lookup.java` and `ZoneLoader.java`. Together they read the files in `zones/`, check them, and answer the question "what does this zone say about this name?".
- The `server` package has four files: `Responder.java`, `DnsServer.java`, `ServeSettings.java` and `ServeCli.java`. Together they turn that answer into a DNS server that listens on a UDP port of this machine.

A **zone** is the part of the DNS name space that one organization manages, kept as a list of records. `seatwave.example.` is SeatWave's zone. A **server** is a program that waits for requests and answers them.

## Why this repository uses it

Module 6 is about naming. SeatWave publishes its addresses in a zone, and a server answers questions about them. The server here is **authoritative**: it holds the zone itself and gives the final answer for names in it. It never asks another server for help. That is the opposite of a **resolver** (see `java-resolver-and-jdk-walkthrough.md`), which asks other servers on behalf of a client.

Three ideas run through this code:

1. **An invalid zone cannot exist.** `Zone.of` is the only way to make a zone, and it checks everything. A mistake in a zone file is found when the file is loaded, not when a customer asks.
2. **The rules of the server live in a function with no socket.** `Responder.respond` takes bytes and a zone and returns bytes. Every rule can be tested without a network.
3. **Everything listens on 127.0.0.1 only.** No query ever leaves this machine.

## Where it appears

| File | What it does |
|---|---|
| `zone/ZoneFile.java` | Reads a zone file (the master-file format, RFC 1035 section 5) into records. |
| `zone/Weights.java` | Reads a weights file and picks among addresses by weight. |
| `zone/Zone.java` | Holds a zone, checks it, and answers a question from it. |
| `zone/Lookup.java` | The five kinds of answer a zone can give. |
| `zone/ZoneLoader.java` | Reads the three settings keys of a zone, loads the files and builds the zone. |
| `server/Responder.java` | One query packet in, one response packet out. |
| `server/DnsServer.java` | The UDP socket and the receiving thread. |
| `server/ServeSettings.java` | Reads `config/serve.properties` into one listener per zone. |
| `server/ServeCli.java` | The `serve` command. |

The formats of the files in `zones/` are in `dns-zone-files.md`; the settings in `java-properties.md`.

## How it works underneath

**One query, step by step.** A client sends one UDP packet to port 5303. The server's one thread, blocked in `receive`, wakes up with the bytes. It hands them to `Responder.respond` together with the current zone. The `Responder` checks the packet is a query, has the codec decode it (`message` package), asks the zone `lookup`, and encodes the answer. The thread sends the bytes back to the address the packet came from.

**The five answers of a zone** (`Lookup`):

| Answer | Meaning | What `dig` shows |
|---|---|---|
| `Answer` | The zone holds records of that name and type. | NOERROR, an ANSWER section |
| `Referral` | The name is inside a child zone this zone hands out (a delegation). | NOERROR, an AUTHORITY section of NS records, glue in ADDITIONAL |
| `NoData` | The name exists, but not with that type. | NOERROR, no answer, the SOA in AUTHORITY |
| `NameError` | The name does not exist. | NXDOMAIN, the SOA in AUTHORITY |
| `OutOfZone` | The name is not in this zone at all. | REFUSED |

**Order of the checks in `Zone.lookup`:** outside the zone? Then a delegation above the name? Then records at the name? Then the answer itself, following a CNAME if there is one, and picking a weighted address.

**A delegation and glue.** The root zone (`zones/root.zone`) does not hold the records of `seatwave.example.`. It holds an NS record that says "ask `a.tld-lab.example.`". That name server's own address, which the client needs in order to ask it, comes in the same response as an extra record. The extra record is called **glue**. A zone must hold the glue for a name server that lives inside the zone it serves, or nobody could find that server: `Zone` refuses a zone that lacks it (a "broken delegation").

**Weighted answers.** The name `book.seatwave.example.` has two A records. A client that takes the first address always picks the same site if the order never changes. The server rotates which address comes first, at random, with probabilities given by the weights file. This follows the policy of CoreDNS's `loadbalance weighted` plugin.

**One thread, one zone object that never changes.** A `Zone` is immutable (nothing in it can be modified after creation). A migration builds a new `Zone` and swaps it in through `ZoneStore` (see `java-migration-walkthrough.md`). The server reads the current zone for every packet, so it never sees a half-changed zone.

## Walkthrough of this repository's use

### `zone/Lookup.java`

```java
package io.plainround.seatwave.dns.zone;

import io.plainround.seatwave.dns.message.ResourceRecord;
import java.util.List;
import java.util.Objects;

/** What a zone knows about one question. */
public sealed interface Lookup {
```

- A sealed interface again: a `Lookup` is exactly one of five records.

```java
  /**
   * The zone holds the answer: the records, with any CNAME first.
   *
   * @param records the answer records
   */
  record Answer(List<ResourceRecord> records) implements Lookup {

    /** Copies the records. */
    public Answer {
      records = List.copyOf(records);
    }
  }
```

- `Answer` holds the records of the answer, with any CNAME first. The compact constructor copies the list to an unmodifiable list.

```java
  /**
   * The name lies in a child zone that this zone delegates: here are the child's name servers and,
   * as glue, their addresses.
   *
   * @param nameServers the NS records of the child zone
   * @param glue the A and AAAA records of those name servers, when this zone has them
   */
  record Referral(List<ResourceRecord> nameServers, List<ResourceRecord> glue) implements Lookup {

    /** Copies the records. */
    public Referral {
      nameServers = List.copyOf(nameServers);
      glue = List.copyOf(glue);
    }
  }
```

- `Referral` holds the NS records of the child zone and, as glue, the A and AAAA records of those name servers, when this zone has them.

```java
  /**
   * The name exists, but has no record of the type asked for (dig shows NOERROR and no answer).
   *
   * @param soa the zone's SOA, whose minimum field sets how long the "no" may be cached
   */
  record NoData(ResourceRecord soa) implements Lookup {

    /** Checks the field. */
    public NoData {
      Objects.requireNonNull(soa, "soa");
    }
  }
```

- `NoData` holds the zone's SOA. The SOA's last field says how long a "no" may be cached. `dig` shows this case as NOERROR with an empty answer.

```java
  /**
   * The name does not exist in the zone (dig shows NXDOMAIN).
   *
   * @param soa the zone's SOA, for negative caching (RFC 2308)
   */
  record NameError(ResourceRecord soa) implements Lookup {

    /** Checks the field. */
    public NameError {
      Objects.requireNonNull(soa, "soa");
    }
  }
```

- `NameError` is the same shape for a name that does not exist (NXDOMAIN). RFC 2308 describes negative caching.

```java
  /** The name is not in this zone at all, so the server refuses to answer. */
  record OutOfZone() implements Lookup {}
}
```

- `OutOfZone` has no data: `record OutOfZone() implements Lookup {}` is a record with no components and an empty body.

### `zone/Weights.java`

```java
package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Weights for weighted answers, read from a file in the format of CoreDNS's {@code loadbalance
 * weighted} policy: a line with a name, then one line per address with its weight from 1 to 255. An
 * address with no weight counts as weight 1, as in CoreDNS.
 *
 * @param byName the weight of each address, per name, in file order
 */
public record Weights(Map<DomainName, Map<InetAddress, Integer>> byName) {
```

- A weights file holds, for each weighted name, the weight of each address. The file uses the format of CoreDNS's `loadbalance weighted` policy: a line with a name, then one line per address with a weight from 1 to 255. An address that has no weight counts as 1, as in CoreDNS.
- The record has one component: a map from name to (a map from address to weight). `Map<DomainName, Map<InetAddress, Integer>>` is a **generic** type: a map whose keys are names and whose values are maps. `LinkedHashMap` keeps the order in which entries were added.

```java
  /** No weights at all: every address of a name is equally likely to be first. */
  public static final Weights NONE = new Weights(Map.of());

  /** The smallest and largest weights CoreDNS accepts (its loadbalance documentation). */
  public static final int MIN_WEIGHT = 1;

  /** The largest weight. */
  public static final int MAX_WEIGHT = 255;
```

- `NONE` is "no weights at all": every address of a name is equally likely to be first. `MIN_WEIGHT` and `MAX_WEIGHT` are the limits CoreDNS accepts.

```java
  /**
   * Copies and checks the weights.
   *
   * @throws IllegalArgumentException if a weight is outside 1 to 255
   */
  public Weights {
    Map<DomainName, Map<InetAddress, Integer>> copy = new LinkedHashMap<>();
    byName.forEach((name, weights) -> {
      weights
          .values()
          .forEach(weight -> {
            if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
              throw new IllegalArgumentException("a weight must be from 1 to 255");
            }
          });
      copy.put(name, Map.copyOf(new LinkedHashMap<>(weights)));
    });
    byName = Map.copyOf(copy);
  }
```

- The compact constructor copies everything into new maps, so nobody can change the weights later, and checks each weight. `forEach((name, weights) -> { ... })` runs a lambda for every entry. `Map.copyOf` makes an unmodifiable map. An unfair weight (0 or 256) stops with `IllegalArgumentException`.

```java
  /**
   * Reads a weights file.
   *
   * @param text the file's content
   * @return the weights, or one line per problem with its line number
   */
  public static Result<Weights, List<String>> parse(String text) {
    Objects.requireNonNull(text, "text");
    Map<DomainName, Map<InetAddress, Integer>> byName = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    Optional<DomainName> current = Optional.empty();
    List<String> lines = text.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index).strip();
      String where = "weights line " + (index + 1) + ": ";
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] fields = line.split("\\s+");
      if (fields.length == 1) {
        switch (DomainName.parse(fields[0])) {
          case Result.Success<DomainName, String>(DomainName name) -> {
            current = Optional.of(name);
            byName.putIfAbsent(name, new LinkedHashMap<>());
          }
          case Result.Error<DomainName, String>(String problem) -> problems.add(where + problem);
        }
      } else if (fields.length == 2 && current.isPresent()) {
        addWeight(fields, where, byName.get(current.get()), problems);
      } else {
        problems.add(where + "expected a name, or an address and a weight after a name");
      }
    }
    return problems.isEmpty() ? Result.success(new Weights(byName)) : Result.error(problems);
  }
```

- `parse` reads the text of a weights file. It returns `Result<Weights, List<String>>`: the weights, or one problem line per mistake. A typo in a file is an expected failure.
- `text.lines().toList()` splits the text into lines (`String.lines()` is Java 11). The loop goes through them with an index so each problem can name its line number: `"weights line " + (index + 1)`.
- A blank line or a line that starts with `#` is skipped (`continue`). The `#` comment style is the same as in the real CoreDNS file.
- `line.split("\\s+")` cuts the line at runs of white space. One field means a name: `DomainName.parse` is tried; on success the name becomes the `current` name and an empty map is added for it (`putIfAbsent`); on error, the problem is recorded.
- The `switch` with `case Result.Success<DomainName, String>(DomainName name) ->` is a pattern-matching `switch` over the `Result` (see `result4j.md`). Both cases are given, so there is no `default`.
- Two fields after a name mean an address and a weight; `addWeight` handles them. Anything else gets one problem line.
- If there were no problems, the result is a `Weights`; otherwise the list of problems.

```java
  /**
   * Returns the weight of one address of a name.
   *
   * @param name the name
   * @param address the address
   * @return its weight, or 1 if the file gives none
   */
  public int weightOf(DomainName name, InetAddress address) {
    return byName.getOrDefault(name, Map.of()).getOrDefault(address, MIN_WEIGHT);
  }
```

- `weightOf` looks up the weight of one address of one name. `getOrDefault(key, fallback)` returns the fallback when the key is missing: first an empty map for an unknown name, then weight 1 for an unknown address.

```java
  /**
   * Returns these weights with one address of one name replaced by another, so a migration keeps
   * the weight a site had. Other names keep their weights unchanged.
   *
   * @param name the name that moves
   * @param oldAddress the address leaving
   * @param newAddress the address taking its place
   * @return the changed copy
   */
  public Weights withAddressReplaced(
      DomainName name, InetAddress oldAddress, InetAddress newAddress) {
    Map<DomainName, Map<InetAddress, Integer>> changed = new LinkedHashMap<>(byName);
    if (byName.getOrDefault(name, Map.of()).containsKey(oldAddress)) {
      Map<InetAddress, Integer> copy = new LinkedHashMap<>(byName.get(name));
      copy.put(newAddress, copy.remove(oldAddress));
      changed.put(name, copy);
    }
    return new Weights(changed);
  }
```

- `withAddressReplaced` is used by a migration: when a site moves from the old address to the new one, the new address keeps the weight of the old one. It copies the map, and only if the name has the old address, makes a copy of the inner map, puts the new address with the weight that `remove(oldAddress)` returns (`remove` returns the removed value), and stores it. Other names keep their weights.

```java
  private static void addWeight(
      String[] fields, String where, Map<InetAddress, Integer> weights, List<String> problems) {
    try {
      // ofLiteral reads only an address written as digits; it never looks a name up.
      InetAddress address = InetAddress.ofLiteral(fields[0]);
      int weight = Integer.parseInt(fields[1]);
      if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
        problems.add(
            String.format(
                Locale.ROOT, "%sa weight must be from 1 to 255, but was %d", where, weight));
        return;
      }
      weights.put(address, weight);
    } catch (IllegalArgumentException notAnAddressOrNumber) {
      // NumberFormatException is an IllegalArgumentException, so one handler covers both fields.
      problems.add(where + "not an address and a whole-number weight: " + String.join(" ", fields));
    }
  }
```

- `addWeight` reads a line `192.0.2.10 50`. `InetAddress.ofLiteral(text)` (Java 22) makes an address from digits only; the comment says it never looks a name up, so a typo cannot send a query anywhere. `Integer.parseInt` reads the weight. A weight out of range adds a problem and returns.
- `String.format(Locale.ROOT, "%sa weight must be from 1 to 255, but was %d", where, weight)` fills the `%s` and `%d` markers. `Locale.ROOT` keeps number formatting the same on every computer.
- One `catch (IllegalArgumentException ...)` covers both a bad address and a bad number, because `NumberFormatException` is a kind of `IllegalArgumentException`, and `ofLiteral` throws `IllegalArgumentException` for a bad literal.

### `zone/ZoneFile.java`

```java
package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
```

- Imports: `Inet4Address` and `Inet6Address` to check the family of an address.

```java
/**
 * Reads a zone file in the master-file format of RFC 1035 section 5, the same files CoreDNS's
 * {@code file} plugin reads, limited to what this lab needs.
 *
 * <p>Supported: {@code $ORIGIN} and {@code $TTL}; one record per line; an owner of {@code @}, a
 * relative name, an absolute name ending in a dot, or a blank owner meaning "the previous one"; an
 * optional TTL and the optional class {@code IN}; the types A, AAAA, NS, CNAME, TXT and SOA (the
 * SOA on one line); comments after a semicolon. Not supported, and reported as a problem:
 * parentheses, other classes, other types and {@code $INCLUDE}. Stateless and thread-safe.
 */
public final class ZoneFile {

  // The seven fields after "SOA": two names and five numbers (RFC 1035 section 3.3.13).
  private static final int SOA_FIELDS = 7;

  private ZoneFile() {}
```

- The Javadoc lists exactly what is supported. A zone file has one record per line. `$ORIGIN` sets the name that is added to relative names, `$TTL` sets the default TTL, `@` stands for the origin, and a line that starts with a space or tab keeps the owner of the line above. A TTL and a class `IN` may come before the type. Parentheses (multi-line records), other classes, other types and `$INCLUDE` are not supported and are reported as problems.
- `SOA_FIELDS` is 7: two names and five numbers.

```java
  /**
   * Parses a zone file.
   *
   * @param text the file's content
   * @param origin the origin used until a {@code $ORIGIN} line changes it
   * @return the records in file order, or one line per problem with its line number
   */
  public static Result<List<ResourceRecord>, List<String>> parse(String text, DomainName origin) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(origin, "origin");
    State state = new State(origin);
    List<String> lines = text.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      state.where = "line " + (index + 1) + ": ";
      String raw = lines.get(index);
      Optional<List<String>> tokens = tokens(raw, state);
      if (tokens.isEmpty() || tokens.get().isEmpty()) {
        continue;
      }
      boolean hasBlankOwner = Character.isWhitespace(raw.charAt(0));
      readLine(tokens.get(), hasBlankOwner, state);
    }
    return state.problems.isEmpty()
        ? Result.success(List.copyOf(state.records))
        : Result.error(List.copyOf(state.problems));
  }
```

- `parse` returns the records in file order, or one problem line per mistake with its line number.
- `State` (defined at the end of the file) holds what changes while reading: the origin, the default TTL, the previous owner, the records and the problems found.
- For every line: `state.where` is set to `"line N: "` so any problem can add its position. `tokens` cuts the line into words. If the line has no words (blank or only a comment), it is skipped.
- A line that begins with white space has a **blank owner**: `Character.isWhitespace(raw.charAt(0))`. `readLine` then reads the line.
- At the end, a result with the list of records, or with the problems.

```java
  private static void readLine(List<String> tokens, boolean hasBlankOwner, State state) {
    String first = tokens.getFirst();
    if (first.equals("$ORIGIN") && tokens.size() == 2) {
      absoluteName(tokens.get(1), state).ifPresent(name -> state.origin = name);
      return;
    }
    if (first.equals("$TTL") && tokens.size() == 2) {
      ttl(tokens.get(1), state).ifPresent(state::setDefaultTtl);
      return;
    }
    if (first.startsWith("$")) {
      state.problems.add(state.where + "unsupported directive " + first);
      return;
    }
    int next = 0;
    Optional<DomainName> owner;
    if (hasBlankOwner) {
      owner = state.previousOwner();
      if (owner.isEmpty()) {
        state.problems.add(state.where + "a blank owner needs a record above it");
        return;
      }
    } else {
      owner = name(tokens.getFirst(), state);
      next = 1;
    }
    Optional<Long> recordTtl = Optional.empty();
    // The TTL and the class may come in either order (RFC 1035 section 5.1).
    for (int optional = 0; optional < 2 && next < tokens.size(); optional++) {
      String token = tokens.get(next);
      if (token.chars().allMatch(Character::isDigit)) {
        recordTtl = ttl(token, state);
        if (recordTtl.isEmpty()) {
          // The bad TTL is already reported; one problem per line is enough.
          return;
        }
        next++;
      } else if (token.equalsIgnoreCase("IN")) {
        next++;
      }
    }
    if (owner.isEmpty() || next >= tokens.size()) {
      if (owner.isPresent()) {
        state.problems.add(state.where + "a record needs a type");
      }
      return;
    }
    state.setPreviousOwner(owner.get());
    Optional<Long> effectiveTtl = recordTtl.or(state::defaultTtl);
    if (effectiveTtl.isEmpty()) {
      state.problems.add(state.where + "no TTL on the record and no $TTL line above it");
      return;
    }
    record(owner.get(), effectiveTtl.get(), tokens.subList(next, tokens.size()), state)
        .ifPresent(state.records::add);
  }
```

- `readLine` handles one line. If the first word is `$ORIGIN` with one argument, the origin is changed. `absoluteName(...)` returns an `Optional`; `ifPresent(name -> state.origin = name)` runs only if the name was valid. If it is `$TTL`, the default TTL is set (`state::setDefaultTtl` is a method reference). Any other word that starts with `$` is reported as an unsupported directive.
- The owner: with a blank owner, the previous line's owner is used (and it is an error if there is none); otherwise the first word is the owner name, and reading continues at word 1.
- The loop reads up to two optional words, a TTL and a class, in either order (RFC 1035 section 5.1). A word of only digits (`allMatch(Character::isDigit)`) is a TTL; the word `IN` (any case) is the class and is skipped. A bad TTL has been reported already, so the method returns.
- If the owner is invalid or no word is left, the line has no type: that is reported as "a record needs a type" (only when the owner was valid, so one mistake gives one problem).
- The owner is remembered as the previous owner. The TTL of the record is its own TTL, or else the default; `recordTtl.or(state::defaultTtl)` uses the second `Optional` if the first is empty. With neither, the line is reported.
- The last statement hands the type and data (the rest of the words, `subList(next, size)`) to `record`.

```java
  private static Optional<ResourceRecord> record(
      DomainName owner, long ttlSeconds, List<String> typeAndData, State state) {
    Optional<RecordType> type = RecordType.fromName(typeAndData.getFirst());
    if (type.isEmpty()) {
      state.problems.add(state.where + "unsupported type or class " + typeAndData.getFirst());
      return Optional.empty();
    }
    List<String> data = typeAndData.subList(1, typeAndData.size());
    Optional<RecordData> parsed =
        switch (type.get()) {
          case A, AAAA -> address(data, type.get(), state);
          case NS, CNAME ->
              one(data, state).flatMap(target -> name(target, state)).map(RecordData.Host::new);
          case TXT -> text(data, state);
          case SOA -> authority(data, state);
        };
    return parsed.map(recordData -> new ResourceRecord(owner, type.get(), ttlSeconds, recordData));
  }
```

- `record` finds the type by name. An unknown type (for example `MX`) is reported as "unsupported type or class".
- The `switch` expression over the enum parses the data for each type: A and AAAA read an address; NS and CNAME read exactly one name and wrap it in a `RecordData.Host` (`RecordData.Host::new` is a constructor reference); TXT reads strings; SOA reads seven fields. `Optional.flatMap` chains steps that may fail; `map` converts the result when it is there.
- The last line builds the `ResourceRecord` from the owner, the type, the TTL and the data, if the data parsed. The record's own constructor checks that data and type match.

```java
  private static Optional<RecordData> address(List<String> data, RecordType type, State state) {
    return one(data, state)
        .flatMap(text -> {
          try {
            // ofLiteral never looks a name up, so a typo cannot send a query anywhere.
            InetAddress address = InetAddress.ofLiteral(text);
            boolean isRightFamily =
                type == RecordType.A
                    ? address instanceof Inet4Address
                    : address instanceof Inet6Address;
            if (!isRightFamily) {
              state.problems.add(state.where + "not an " + type + " address: " + text);
              return Optional.empty();
            }
            return Optional.of(new RecordData.Address(address));
          } catch (IllegalArgumentException notAnAddress) {
            state.problems.add(state.where + "not an " + type + " address: " + text);
            return Optional.empty();
          }
        });
  }
```

- `address` reads the one word that must follow `A` or `AAAA`. `InetAddress.ofLiteral` never looks a name up (see the comment). The family is checked: an A record needs an IPv4 address and an AAAA record an IPv6 address; a mismatch is a problem. The lambda returns `Optional.empty()` after recording the problem.

```java
  private static Optional<RecordData> text(List<String> data, State state) {
    if (data.isEmpty()) {
      state.problems.add(state.where + "a TXT record needs a string");
      return Optional.empty();
    }
    try {
      return Optional.of(new RecordData.Text(data));
    } catch (IllegalArgumentException tooLong) {
      state.problems.add(state.where + tooLong.getMessage());
      return Optional.empty();
    }
  }
```

- `text` builds a TXT record from the quoted strings. A string over 255 bytes makes the `RecordData.Text` constructor throw `IllegalArgumentException`, which is caught and turned into a problem line with its message.

```java
  private static Optional<RecordData> authority(List<String> data, State state) {
    if (data.size() != SOA_FIELDS) {
      state.problems.add(
          state.where + "an SOA needs 7 fields on one line; parentheses are not supported");
      return Optional.empty();
    }
    Optional<DomainName> primary = name(data.get(0), state);
    Optional<DomainName> mailbox = name(data.get(1), state);
    List<Optional<Long>> numbers =
        data.subList(2, SOA_FIELDS).stream().map(token -> ttl(token, state)).toList();
    if (primary.isEmpty() || mailbox.isEmpty() || numbers.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }
    return Optional.of(
        new RecordData.Authority(
            primary.get(),
            mailbox.get(),
            numbers.get(0).get(),
            numbers.get(1).get(),
            numbers.get(2).get(),
            numbers.get(3).get(),
            numbers.get(4).get()));
  }
```

- `authority` reads an SOA. It needs exactly seven words on one line. The two names are parsed with `name`; the five numbers go through `ttl` (the same number check as for a TTL, 0 to 2,147,483,647). If anything failed, there is nothing to return (the problems are already recorded). `data.subList(2, SOA_FIELDS).stream().map(token -> ttl(token, state)).toList()` turns the five words into five `Optional<Long>`.
- `numbers.get(0).get()` takes the number out of each `Optional`; it is safe here because the line before checked `anyMatch(Optional::isEmpty)`.

```java
  private static Optional<String> one(List<String> data, State state) {
    if (data.size() != 1) {
      state.problems.add(state.where + "expected exactly one value, but found " + data.size());
      return Optional.empty();
    }
    return Optional.of(data.getFirst());
  }
```

- `one` checks that exactly one word is left, as an A, AAAA, NS or CNAME record needs.

```java
  private static Optional<DomainName> name(String token, State state) {
    if (token.equals("@")) {
      return Optional.of(state.origin);
    }
    // Under the root origin a relative name only gains its final dot.
    String suffix = state.origin.equals(DomainName.ROOT) ? "" : state.origin.toString();
    String absolute = token.endsWith(".") ? token : token + "." + suffix;
    return absoluteName(absolute, state);
  }
```

- `name` turns a word into an absolute name. `@` is the origin. A name that ends in a dot is already absolute. Otherwise the origin is added: `www` under `seatwave.example.` becomes `www.seatwave.example.`. Under the root origin, the suffix is empty, so a relative name only gains its final dot.

```java
  private static Optional<DomainName> absoluteName(String token, State state) {
    return switch (DomainName.parse(token)) {
      case Result.Success<DomainName, String>(DomainName name) -> Optional.of(name);
      case Result.Error<DomainName, String>(String problem) -> {
        state.problems.add(state.where + problem);
        yield Optional.empty();
      }
    };
  }
```

- `absoluteName` calls `DomainName.parse` and switches over the `Result`. In the error arm a block records the problem and ends with `yield Optional.empty();`. `yield` gives the value of a `switch` expression from inside a block (Java 14).

```java
  private static Optional<Long> ttl(String token, State state) {
    try {
      long seconds = Long.parseLong(token);
      if (seconds < 0 || seconds > ResourceRecord.MAX_TTL_SECONDS) {
        state.problems.add(state.where + "a number must be from 0 to 2147483647: " + token);
        return Optional.empty();
      }
      return Optional.of(seconds);
    } catch (NumberFormatException notANumber) {
      state.problems.add(state.where + "not a whole number: " + token);
      return Optional.empty();
    }
  }
```

- `ttl` reads a whole number with `Long.parseLong`. A number below 0 or above 2,147,483,647 is a problem; so is a word that is not a number (`NumberFormatException`).

```java
  private static Optional<List<String>> tokens(String line, State state) {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    boolean isQuoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        isQuoted = !isQuoted;
        if (!isQuoted) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else if (isQuoted) {
        token.append(character);
      } else if (character == ';') {
        break;
      } else if (character == '(' || character == ')') {
        state.problems.add(
            state.where + "parentheses are not supported; write the record on one line");
        return Optional.empty();
      } else if (Character.isWhitespace(character)) {
        if (!token.isEmpty()) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else {
        token.append(character);
      }
    }
    if (isQuoted) {
      state.problems.add(state.where + "a quote is not closed");
      return Optional.empty();
    }
    if (!token.isEmpty()) {
      tokens.add(token.toString());
    }
    return Optional.of(tokens);
  }
```

- `tokens` cuts one line into words, character by character. It is a small state machine: `isQuoted` is true between two quotation marks, `token` collects the characters of the current word in a `StringBuilder`.
- A quotation mark toggles `isQuoted`; when it closes, the quoted text becomes one word even if it holds spaces. Inside quotes every character is kept (including `;`). Outside quotes: a semicolon starts a comment, so the rest of the line is dropped (`break`); a parenthesis is an error; white space ends the current word; any other character is added to the word.
- After the loop: a quote that was never closed is a problem; a last word is added.

```java
  /** The parser's position in one file. Confined to one call of {@link #parse}. */
  private static final class State {
    private final List<ResourceRecord> records = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    // A one-element list stands for "set or not", because Optional is kept to return types.
    private final List<DomainName> previousOwner = new ArrayList<>();
    private final List<Long> defaultTtl = new ArrayList<>();
    private DomainName origin;
    private String where = "";

    State(DomainName origin) {
      this.origin = origin;
    }

    Optional<DomainName> previousOwner() {
      return previousOwner.stream().findFirst();
    }

    void setPreviousOwner(DomainName owner) {
      previousOwner.clear();
      previousOwner.add(owner);
    }

    Optional<Long> defaultTtl() {
      return defaultTtl.stream().findFirst();
    }

    void setDefaultTtl(long seconds) {
      defaultTtl.clear();
      defaultTtl.add(seconds);
    }
  }
}
```

- `State` is a private nested class. It holds the lists of records and problems, the current origin and the position text.
- The comment explains `previousOwner` and `defaultTtl`: they are one-element lists that stand for "set or not set", because this code keeps `Optional` for return types only. The methods give the value back as an `Optional` or replace it.

### `zone/Zone.java`

```java
package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
```

- Imports: `RandomGenerator` (Java 17) is the interface of random-number sources; `Stream` is used for `flatMap`; `Collections.reverse` and the set classes appear in the checks.

```java
/**
 * One zone: its origin, its records and the weights of its weighted names. A zone can only be made
 * by {@link #of}, which checks it, so an invalid zone cannot exist. Immutable and thread-safe.
 *
 * <p>The checks catch the misconfigurations of failure mode 3 before a server loads them: records
 * outside the zone, a missing or extra SOA, no NS at the apex, a CNAME at the apex or beside other
 * data, a CNAME that points at a name the zone does not have, a CNAME loop, TTLs that differ inside
 * one record set (RFC 2181 section 5.2), duplicate records, a name server without an address
 * (broken delegation) and weights for addresses the name does not have.
 */
public final class Zone {

  // A CNAME chain inside one zone longer than this is a loop or a mistake.
  private static final int MAX_CNAME_HOPS = 8;

  private final DomainName origin;
  private final List<ResourceRecord> records;
  private final Weights weights;

  private Zone(DomainName origin, List<ResourceRecord> records, Weights weights) {
    this.origin = origin;
    this.records = List.copyOf(records);
    this.weights = weights;
  }
```

- `Zone` is a class, not a record, because the constructor must stay private so that only `of` makes a zone. The Javadoc lists every check; each is a method below.
- `MAX_CNAME_HOPS` is 8: a chain of aliases inside one zone longer than that is a loop or a mistake.
- The three fields are `final`. The private constructor copies the record list.

```java
  /**
   * Checks records and builds a zone.
   *
   * @param origin the apex of the zone
   * @param records the records
   * @param weights the weights of weighted names, or {@link Weights#NONE}
   * @return the zone, or one line per problem
   */
  public static Result<Zone, List<String>> of(
      DomainName origin, List<ResourceRecord> records, Weights weights) {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(weights, "weights");
    Zone candidate = new Zone(origin, records, weights);
    List<String> problems = candidate.problems();
    return problems.isEmpty() ? Result.success(candidate) : Result.error(problems);
  }
```

- `of` builds a candidate zone and asks it for its `problems()`. No problems: a success. Otherwise: an error holding one text per problem. There is no other way to make a zone, so an invalid zone cannot exist.

```java
  /**
   * Returns the apex.
   *
   * @return for example {@code seatwave.example.}
   */
  public DomainName origin() {
    return origin;
  }

  /**
   * Returns every record.
   *
   * @return the records, in file order
   */
  public List<ResourceRecord> records() {
    return records;
  }

  /**
   * Returns the weights.
   *
   * @return the weights of weighted names
   */
  public Weights weights() {
    return weights;
  }

  /**
   * Returns the records of one name and type.
   *
   * @param owner the name
   * @param type the type
   * @return the records, in file order; empty if there are none
   */
  public List<ResourceRecord> recordsAt(DomainName owner, RecordType type) {
    return records.stream()
        .filter(record -> record.owner().equals(owner) && record.type() == type)
        .toList();
  }
```

- Three accessors: `origin`, `records`, `weights`.
- `recordsAt(owner, type)` filters the records with a stream: keep those whose owner equals the name and whose type is the type. `toList()` gives an unmodifiable list.

```java
  /**
   * Returns the addresses (A and AAAA) of one name.
   *
   * @param owner the name
   * @return the addresses, in file order
   */
  public List<InetAddress> addressesOf(DomainName owner) {
    return records.stream()
        .filter(record -> record.owner().equals(owner))
        .map(ResourceRecord::data)
        .flatMap(data ->
            data instanceof RecordData.Address(var address) ? Stream.of(address) : Stream.empty())
        .toList();
  }

  /**
   * Returns the zone's SOA record.
   *
   * @return the one SOA at the apex, which {@link #of} guarantees
   */
  public ResourceRecord soa() {
    return recordsAt(origin, RecordType.SOA).getFirst();
  }
```

- `addressesOf` returns every address (A and AAAA) of a name. `.map(ResourceRecord::data)` takes the data of each record; `flatMap` turns each data into zero or one address: `data instanceof RecordData.Address(var address) ? Stream.of(address) : Stream.empty()`. Records that are not addresses vanish.
- `soa()` returns the one SOA at the apex. `getFirst()` (Java 21) is safe because `of` guarantees exactly one.

```java
  /**
   * Builds a changed copy of this zone and checks it like any other.
   *
   * @param newRecords the records of the copy
   * @param newWeights the weights of the copy
   * @return the copy, or one line per problem
   */
  public Result<Zone, List<String>> with(List<ResourceRecord> newRecords, Weights newWeights) {
    return of(origin, newRecords, newWeights);
  }
```

- `with` builds a changed copy of this zone and checks it like any other. The migration guard uses it, so a migration can never produce an invalid zone.

```java
  /**
   * Answers one question from this zone. For a weighted name, the address the weights pick is moved
   * to the top, as CoreDNS's weighted policy does.
   *
   * @param question the question
   * @param random picks the weighted address; not shared between threads
   * @return the answer, a referral, no data, no such name, or out of zone
   */
  public Lookup lookup(Question question, RandomGenerator random) {
    Objects.requireNonNull(question, "question");
    Objects.requireNonNull(random, "random");
    DomainName name = question.name();
    if (!name.isAtOrBelow(origin)) {
      return new Lookup.OutOfZone();
    }
    Optional<Lookup> referral = referralFor(name);
    if (referral.isPresent()) {
      return referral.get();
    }
    List<ResourceRecord> atName =
        records.stream().filter(record -> record.owner().equals(name)).toList();
    if (atName.isEmpty()) {
      // A name with records below it exists even when it has none itself (RFC 8020).
      boolean hasChildren = records.stream().anyMatch(record -> record.owner().isAtOrBelow(name));
      return hasChildren ? new Lookup.NoData(soa()) : new Lookup.NameError(soa());
    }
    return answer(name, question, random, 0);
  }
```

- `lookup` is the heart of the server. A question outside the zone is `OutOfZone`. Then `referralFor` looks for a delegation. Then it collects the records at the name. If there are none: a name that has records below it still exists (RFC 8020), so the answer is `NoData`; if nothing is below it either, it is `NameError`. If there are records, `answer` builds the reply.

```java
  private Lookup answer(DomainName name, Question question, RandomGenerator random, int hops) {
    List<ResourceRecord> cname = recordsAt(name, RecordType.CNAME);
    Optional<RecordType> type = question.type();
    if (!cname.isEmpty() && type.orElse(RecordType.A) != RecordType.CNAME) {
      List<ResourceRecord> chain = new ArrayList<>(cname);
      DomainName target = ((RecordData.Host) cname.getFirst().data()).target();
      if (target.isAtOrBelow(origin) && hops < MAX_CNAME_HOPS) {
        if (answer(target, question, random, hops + 1) instanceof Lookup.Answer(var rest)) {
          chain.addAll(rest);
        }
      }
      return new Lookup.Answer(chain);
    }
    List<ResourceRecord> matching = type.map(found -> recordsAt(name, found)).orElse(List.of());
    if (matching.isEmpty()) {
      return new Lookup.NoData(soa());
    }
    return new Lookup.Answer(weighted(name, matching, random));
  }
```

- `answer` first looks for a CNAME at the name. If there is one and the question is not for the CNAME itself (a question with an unknown type counts as an address question), the chain starts with the CNAME. If the target is inside this zone and the hop count is below the maximum, the method calls itself for the target (**recursion**: a method calling itself) and adds what it finds. The pattern `instanceof Lookup.Answer(var rest)` tests and unpacks in one step. A target outside the zone is not followed: a server for one zone cannot know it.
- Otherwise it takes the records of the asked type. `type.map(found -> recordsAt(name, found)).orElse(List.of())` gives an empty list for a type this lab does not serve. No match is `NoData`; a match goes through `weighted`.

```java
  private Optional<Lookup> referralFor(DomainName name) {
    // The highest cut below the apex wins: walk from just under the apex down to the name.
    List<DomainName> chain = new ArrayList<>(name.selfAndAncestors());
    Collections.reverse(chain);
    for (DomainName candidate : chain) {
      if (!candidate.isAtOrBelow(origin) || candidate.equals(origin)) {
        continue;
      }
      List<ResourceRecord> nameServers = recordsAt(candidate, RecordType.NS);
      if (!nameServers.isEmpty()) {
        List<ResourceRecord> glue = new ArrayList<>();
        for (ResourceRecord nameServer : nameServers) {
          DomainName host = ((RecordData.Host) nameServer.data()).target();
          glue.addAll(recordsAt(host, RecordType.A));
          glue.addAll(recordsAt(host, RecordType.AAAA));
        }
        return Optional.of(new Lookup.Referral(nameServers, glue));
      }
    }
    return Optional.empty();
  }
```

- `referralFor` looks for a delegation point between the apex and the name. `name.selfAndAncestors()` lists the name and its ancestors, from the name up to the root; `Collections.reverse(chain)` flips it so the walk goes from the top down. The comment says why: the highest cut below the apex wins.
- A candidate above the zone or equal to the apex is skipped (`continue`). The first candidate that has NS records is a delegation. The glue is the A and AAAA records that this zone holds for each name server's host. The result is a `Referral`.

```java
  private List<ResourceRecord> weighted(
      DomainName name, List<ResourceRecord> answer, RandomGenerator random) {
    boolean isAddress =
        answer.getFirst().type() == RecordType.A || answer.getFirst().type() == RecordType.AAAA;
    if (!isAddress || answer.size() == 1 || !weights.byName().containsKey(name)) {
      return answer;
    }
    int total = 0;
    for (ResourceRecord record : answer) {
      total += weights.weightOf(name, ((RecordData.Address) record.data()).address());
    }
    int pick = random.nextInt(total);
    int chosen = 0;
    for (int index = 0; index < answer.size(); index++) {
      pick -= weights.weightOf(name, ((RecordData.Address) answer.get(index).data()).address());
      if (pick < 0) {
        chosen = index;
        break;
      }
    }
    // Only the first record is chosen; the rest keep their order, as in CoreDNS.
    List<ResourceRecord> ordered = new ArrayList<>(answer);
    ordered.addFirst(ordered.remove(chosen));
    return List.copyOf(ordered);
  }
```

- `weighted` picks the first address of a weighted name. If the records are not addresses, or there is only one, or the name has no weights, the answer stays as it is.
- Otherwise: `total` is the sum of the weights. `random.nextInt(total)` gives a number from 0 up to total minus 1. The loop subtracts each record's weight from `pick` until it goes below zero; that record is chosen. With weights 90 and 10, a pick from 0 to 89 chooses the first record, and 90 to 99 the second.
- The chosen record moves to the top and the others keep their order, as in CoreDNS: `ordered.addFirst(ordered.remove(chosen))` removes the element at index `chosen` and inserts it at the front (`addFirst` is Java 21).

```java
  private List<String> problems() {
    List<String> problems = new ArrayList<>();
    for (ResourceRecord record : records) {
      if (!record.owner().isAtOrBelow(origin)) {
        problems.add(record.owner() + " is outside the zone " + origin);
      }
    }
    long soaCount = records.stream().filter(record -> record.type() == RecordType.SOA).count();
    if (soaCount != 1 || recordsAt(origin, RecordType.SOA).size() != 1) {
      problems.add("the zone needs exactly one SOA record, at its apex " + origin);
    }
    if (recordsAt(origin, RecordType.NS).isEmpty()) {
      problems.add("the zone needs at least one NS record at its apex " + origin);
    }
    checkAliases(problems);
    checkRecordSets(problems);
    checkNameServers(problems);
    checkWeights(problems);
    return problems;
  }
```

- `problems` runs every check and collects the messages: a record outside the zone, the number of SOA records (exactly one, and at the apex), at least one NS record at the apex, and four more checks below.

```java
  private void checkAliases(List<String> problems) {
    for (ResourceRecord alias : records) {
      if (alias.type() != RecordType.CNAME) {
        continue;
      }
      DomainName owner = alias.owner();
      if (owner.equals(origin)) {
        problems.add(
            "a CNAME at the zone apex "
                + origin
                + " is not allowed, because the apex must also hold the SOA and NS records"
                + " (RFC 1034 section 3.6.2); use A and AAAA records or a provider's alias record");
      } else if (records.stream()
          .anyMatch(other -> other.owner().equals(owner) && other != alias)) {
        problems.add("a CNAME at " + owner + " must be the only record of that name");
      }
      DomainName target = ((RecordData.Host) alias.data()).target();
      if (target.isAtOrBelow(origin)
          && records.stream().noneMatch(record -> record.owner().equals(target))) {
        problems.add(
            "the CNAME " + owner + " points at " + target + ", which the zone does not have");
      }
      if (hasAliasLoop(owner)) {
        problems.add("the CNAME chain from " + owner + " loops");
      }
    }
  }
```

- `checkAliases` looks at each CNAME. A CNAME at the apex is refused, because the apex must also hold the SOA and NS records and a CNAME must be the only record of its name (RFC 1034 section 3.6.2). A CNAME that is not alone at its name is refused (`other != alias` compares object identity: a different object with the same owner). A CNAME that points at a name in this zone that has no records is refused. A chain that loops is refused.

```java
  private boolean hasAliasLoop(DomainName start) {
    Set<DomainName> seen = new HashSet<>();
    DomainName current = start;
    while (seen.add(current)) {
      List<ResourceRecord> alias = recordsAt(current, RecordType.CNAME);
      if (alias.isEmpty()) {
        return false;
      }
      current = ((RecordData.Host) alias.getFirst().data()).target();
    }
    return true;
  }
```

- `hasAliasLoop` follows the chain of CNAMEs from a name. `seen.add(current)` returns false when the name was already in the set, which ends the `while` loop with `true` (a loop). If a name has no CNAME, the chain ends: `false`.

```java
  private void checkRecordSets(List<String> problems) {
    Set<String> seen = new HashSet<>();
    Set<String> reported = new HashSet<>();
    for (ResourceRecord record : records) {
      String identity = record.owner() + " " + record.type() + " " + record.data();
      if (!seen.add(identity)) {
        problems.add("the record " + record + " appears twice");
      }
      String set = record.owner() + " " + record.type();
      boolean hasOtherTtl =
          recordsAt(record.owner(), record.type()).stream()
              .anyMatch(other -> other.ttlSeconds() != record.ttlSeconds());
      if (hasOtherTtl && reported.add(set)) {
        problems.add(
            "the records of "
                + set
                + " have different TTLs; one set has one TTL (RFC 2181 section 5.2)");
      }
    }
  }
```

- `checkRecordSets` finds duplicates and unequal TTLs. A record's identity is its owner, type and data as one text; a text seen twice is a duplicate. Within one set (same owner and type) all TTLs must be equal (RFC 2181 section 5.2); `reported` makes sure each set is reported once.

```java
  private void checkNameServers(List<String> problems) {
    for (ResourceRecord nameServer : records) {
      if (nameServer.type() != RecordType.NS) {
        continue;
      }
      DomainName host = ((RecordData.Host) nameServer.data()).target();
      // A name server inside this zone can only be found through an address held here (glue).
      if (host.isAtOrBelow(origin) && addressesOf(host).isEmpty()) {
        problems.add(
            "broken delegation: the name server "
                + host
                + " of "
                + nameServer.owner()
                + " has no A or AAAA record (glue) in the zone");
      }
    }
  }
```

- `checkNameServers` finds a broken delegation. For each NS record whose host is inside this zone, the zone must hold an address for that host (glue); otherwise nobody could find the name server.

```java
  private void checkWeights(List<String> problems) {
    weights
        .byName()
        .forEach((name, weighted) -> {
          List<InetAddress> addresses = addressesOf(name);
          if (addresses.isEmpty()) {
            problems.add("weights are given for " + name + ", which has no A or AAAA records");
          }
          for (InetAddress address : weighted.keySet()) {
            if (!addresses.isEmpty() && !addresses.contains(address)) {
              problems.add(
                  "a weight is given for "
                      + address.getHostAddress()
                      + ", which "
                      + name
                      + " does not have");
            }
          }
        });
  }
}
```

- `checkWeights` checks the weights against the records. Weights for a name with no address records are a problem. A weight for an address that the name does not have is a problem (this is mistake 4 of `zones/broken/`).

### `zone/ZoneLoader.java`

```java
package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads a zone from the three settings every program uses: {@code <prefix>.origin}, {@code
 * <prefix>.file} and {@code <prefix>.weights}, where the weights are a file path or the word {@code
 * none}. Stateless and thread-safe.
 */
public final class ZoneLoader {

  /** The value of a weights key that means "this zone has no weighted names". */
  public static final String NO_WEIGHTS = "none";

  private ZoneLoader() {}
```

- `ZoneLoader` reads the three settings that name a zone: `<prefix>.origin`, `<prefix>.file` and `<prefix>.weights`, where the weights are a file path or the word `none` (`NO_WEIGHTS`).

```java
  /**
   * Reads the three keys and loads the zone they name.
   *
   * @param reader the settings, which records a problem for each missing key
   * @param prefix the key prefix, for example {@code zone}
   * @return the zone, or one line per problem in the files or the zone; a missing key is recorded
   *     in the reader only, so the caller reports it once with the reader's other problems
   */
  public static Result<Zone, List<String>> load(SettingsReader reader, String prefix) {
    Objects.requireNonNull(reader, "reader");
    String origin = reader.text(prefix + ".origin");
    String file = reader.text(prefix + ".file");
    String weights = reader.text(prefix + ".weights");
    if (origin.isEmpty() || file.isEmpty() || weights.isEmpty()) {
      return Result.error(List.of());
    }
    return load(origin, Path.of(file), weights);
  }
```

- The first `load` reads the three keys from a `SettingsReader`. If any is missing, it returns an error with an **empty** list: the reader has recorded the missing key already, and the caller reports it once together with the other problems. Otherwise it calls the second `load`.

```java
  /**
   * Loads a zone file and its weights file.
   *
   * @param originText the apex, for example {@code seatwave.example.}
   * @param zoneFile the zone file
   * @param weightsFile a weights file path, or {@code none}
   * @return the zone, or one line per problem
   */
  public static Result<Zone, List<String>> load(
      String originText, Path zoneFile, String weightsFile) {
    List<String> problems = new ArrayList<>();
    DomainName origin = DomainName.ROOT;
    switch (DomainName.parse(originText)) {
      case Result.Success<DomainName, String>(DomainName parsed) -> origin = parsed;
      case Result.Error<DomainName, String>(String problem) -> problems.add("origin: " + problem);
    }
    List<ResourceRecord> records = List.of();
    Weights weights = Weights.NONE;
    try {
      switch (ZoneFile.parse(Files.readString(zoneFile, StandardCharsets.UTF_8), origin)) {
        case Result.Success<List<ResourceRecord>, List<String>>(List<ResourceRecord> parsed) ->
            records = parsed;
        case Result.Error<List<ResourceRecord>, List<String>>(List<String> found) ->
            found.forEach(problem -> problems.add(zoneFile.getFileName() + " " + problem));
      }
      if (!weightsFile.equals(NO_WEIGHTS)) {
        switch (Weights.parse(Files.readString(Path.of(weightsFile), StandardCharsets.UTF_8))) {
          case Result.Success<Weights, List<String>>(Weights parsed) -> weights = parsed;
          case Result.Error<Weights, List<String>>(List<String> found) -> problems.addAll(found);
        }
      }
    } catch (IOException unreadable) {
      // A missing file is a mistake in the settings, so it is reported like any other problem.
      problems.add("cannot read " + unreadable.getMessage());
    }
    if (!problems.isEmpty()) {
      return Result.error(problems);
    }
    return Zone.of(origin, records, weights);
  }
```

- The second `load` collects problems in a list. The origin is parsed with `DomainName.parse`; on error a problem is recorded, and `origin` stays the root as a stand-in.
- It reads the zone file as UTF-8 (`Files.readString`, Java 11) and parses it. The `switch` with record patterns assigns `records` on success; on error it adds each problem with the file name: `zoneFile.getFileName() + " " + problem`.
- Unless the weights setting is `none`, the weights file is read and parsed the same way.
- `catch (IOException unreadable)` handles a missing or unreadable file: it is a mistake in the settings, so it becomes a problem line, not a crash.
- If any problem was found, the result is an error. Only when all files are fine is `Zone.of` called, which checks the zone itself.

### `server/Responder.java`

```java
package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.zone.Lookup;
import io.plainround.seatwave.dns.zone.Zone;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

```

- Imports from the `message` and `zone` packages. `Responder` is the only server class that knows the rules.

```java
/**
 * Turns one query packet into one response packet, from one zone. It holds no state and touches no
 * socket, so every rule of the server is tested here without a network.
 *
 * <p>The rules: a packet shorter than a header, or one that is itself a response, gets no answer at
 * all (answering responses lets two servers bounce packets forever); a packet that cannot be read
 * gets FORMERR; an opcode other than a standard query gets NOTIMP; a query must carry exactly one
 * question (RFC 9619); a name outside the zone gets REFUSED; otherwise the zone answers, refers to
 * a child zone, says "no data" or says NXDOMAIN, with the SOA in the authority section so the "no"
 * can be cached for the shorter of the SOA's TTL and its minimum field (RFC 2308 section 3). A
 * response longer than 512 octets is cut to its header and question with the truncated flag set
 * (RFC 1035 section 4.2.1). Thread-safe if each thread brings its own random generator.
 */
public final class Responder {

  /** The largest UDP message without EDNS (RFC 1035 section 4.2.1). */
  public static final int MAX_UDP_OCTETS = 512;

  // The opcode bits and the recursion-desired bit are copied from the query (RFC 1035 4.1.1).
  private static final int COPIED_FLAGS = 0x7800 | Message.RECURSION_DESIRED_FLAG;
  private static final int FLAGS_OFFSET = 2;
  private static final int BYTE_MASK = 0xFF;
  private static final int BITS_PER_BYTE = 8;

  private Responder() {}

```

- The Javadoc lists the rules, which the code follows in order:
  1. A packet shorter than a header, or one that is itself a response, gets **no answer at all**. Answering a response would let two servers bounce packets forever.
  2. A packet that cannot be read gets FORMERR.
  3. An opcode other than a standard query gets NOTIMP.
  4. A query must carry exactly one question (RFC 9619).
  5. A name outside the zone gets REFUSED.
  6. Otherwise the zone answers, refers, says "no data" or says NXDOMAIN, with the SOA in the authority section so the "no" can be cached for the shorter of the SOA's TTL and its minimum field (RFC 2308 section 3).
  7. A response longer than 512 octets is cut to its header and question with the truncated flag set (RFC 1035 section 4.2.1).
- `MAX_UDP_OCTETS` is 512: the largest DNS message over UDP without EDNS.
- `COPIED_FLAGS` is `0x7800 | RECURSION_DESIRED_FLAG`. `0x7800` is the four opcode bits. RFC 1035 section 4.1.1 says a response copies the opcode and the recursion-desired bit from the query. `|` is bitwise or.
- `FLAGS_OFFSET` is 2: the flags start at byte 2 of the header.

```java
  /**
   * Answers one packet.
   *
   * @param packet the query as it arrived
   * @param zone the zone to answer from
   * @param random picks the first address of a weighted name
   * @return the response, or empty if the packet must not be answered
   */
  public static Optional<byte[]> respond(byte[] packet, Zone zone, RandomGenerator random) {
    Objects.requireNonNull(packet, "packet");
    Objects.requireNonNull(zone, "zone");
    if (packet.length < MessageCodec.HEADER_OCTETS || isResponse(packet)) {
      return Optional.empty();
    }
    int queryFlags =
        ((packet[FLAGS_OFFSET] & BYTE_MASK) << BITS_PER_BYTE)
            | (packet[FLAGS_OFFSET + 1] & BYTE_MASK);
    Message response =
        switch (MessageCodec.decode(packet)) {
          case Result.Success<Message, MessageProblem>(Message query) ->
              answer(query, zone, random);
          case Result.Error<Message, MessageProblem> unreadable ->
              reply(MessageCodec.idOf(packet), queryFlags, List.of(), ResponseCode.FORMAT_ERROR);
        };
    byte[] encoded = MessageCodec.encode(response);
    if (encoded.length > MAX_UDP_OCTETS) {
      Message cut =
          new Message(
              response.id(),
              response.flags() | Message.TRUNCATED_FLAG,
              response.questions(),
              List.of(),
              List.of(),
              List.of());
      return Optional.of(MessageCodec.encode(cut));
    }
    return Optional.of(encoded);
  }
```

- `respond` is the whole server, without a socket. A short packet, or one whose response bit is set (`isResponse`), gets `Optional.empty()`: no reply.
- `queryFlags` reads the flags straight from bytes 2 and 3, because even a packet the codec cannot read needs its flags copied into the FORMERR reply.
- The `switch (MessageCodec.decode(packet))` over the `Result` gives the `response`: a readable query goes to `answer`; an unreadable one gets an empty FORMERR reply with the same identifier (`idOf`) and no questions. `case Result.Error<Message, MessageProblem> unreadable` matches the error without unpacking it.
- The response is encoded. If it is over 512 bytes, a cut version is made: same identifier, the flags with the truncated bit added (`flags | TRUNCATED_FLAG`), the questions, and empty sections. The client sees TC and should retry over TCP, which this lab does not offer.

```java
  private static Message answer(Message query, Zone zone, RandomGenerator random) {
    if (query.opcode() != 0) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.NOT_IMPLEMENTED);
    }
    if (query.questions().size() != 1) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.FORMAT_ERROR);
    }
    Question question = query.questions().getFirst();
    if (question.classCode() != Question.CLASS_INTERNET) {
      return reply(query.id(), query.flags(), query.questions(), ResponseCode.REFUSED);
    }
    int answerFlags = flags(query.flags(), ResponseCode.NO_ERROR) | Message.AUTHORITATIVE_FLAG;
    return switch (zone.lookup(question, random)) {
      case Lookup.Answer(var records) ->
          new Message(query.id(), answerFlags, query.questions(), records, List.of(), List.of());
      case Lookup.Referral(var nameServers, var glue) ->
          new Message(
              query.id(),
              flags(query.flags(), ResponseCode.NO_ERROR),
              query.questions(),
              List.of(),
              nameServers,
              glue);
      case Lookup.NoData(var soa) ->
          new Message(
              query.id(),
              answerFlags,
              query.questions(),
              List.of(),
              List.of(negative(soa)),
              List.of());
      case Lookup.NameError(var soa) ->
          new Message(
              query.id(),
              flags(query.flags(), ResponseCode.NAME_ERROR) | Message.AUTHORITATIVE_FLAG,
              query.questions(),
              List.of(),
              List.of(negative(soa)),
              List.of());
      case Lookup.OutOfZone() ->
          reply(query.id(), query.flags(), query.questions(), ResponseCode.REFUSED);
    };
  }
```

- `answer` handles a decoded query. Opcode other than 0: NOTIMP. Not exactly one question: FORMERR. A class other than IN: REFUSED.
- `answerFlags` is the response flags plus the authoritative bit (AA). The `switch` over `zone.lookup(...)` has one arm per kind of `Lookup`:
  - `Answer`: the records go in the answer section.
  - `Referral`: **no** AA flag (the server is not authoritative for the child zone), the NS records in the authority section and the glue in the additional section.
  - `NoData`: AA set, the SOA in the authority section.
  - `NameError`: response code NXDOMAIN, AA set, the SOA in the authority section.
  - `OutOfZone()`: REFUSED, no sections.
- The patterns such as `Lookup.Referral(var nameServers, var glue)` take the record's components out.

```java
  private static ResourceRecord negative(ResourceRecord soa) {
    long minimum = ((RecordData.Authority) soa.data()).minimumSeconds();
    return soa.withTtl(Math.min(soa.ttlSeconds(), minimum));
  }

  private static Message reply(
      int id, int queryFlags, List<Question> questions, ResponseCode code) {
    return new Message(id, flags(queryFlags, code), questions, List.of(), List.of(), List.of());
  }

  private static int flags(int queryFlags, ResponseCode code) {
    return Message.RESPONSE_FLAG | (queryFlags & COPIED_FLAGS) | code.code();
  }

  private static boolean isResponse(byte[] packet) {
    return ((packet[FLAGS_OFFSET] & BYTE_MASK) << BITS_PER_BYTE & Message.RESPONSE_FLAG) != 0;
  }
}
```

- `negative` lowers the SOA's TTL to the smaller of its TTL and its minimum field (RFC 2308 section 3), so a cache keeps the "no" for that long. `Math.min` picks the smaller.
- `reply` builds an answer with only a code: the flags are the response bit, the copied query flags and the code.
- `flags` computes `RESPONSE_FLAG | (queryFlags & COPIED_FLAGS) | code.code()`: response, the opcode and recursion-desired bits of the query, and the response code in the low four bits.
- `isResponse` tests the top bit of byte 2: `(byte & 0xFF) << 8` moves it to the top of the 16-bit word, and `& RESPONSE_FLAG` keeps it.

### `server/DnsServer.java`

```java
package io.plainround.seatwave.dns.server;

import io.plainround.seatwave.dns.migration.ZoneStore;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * An authoritative DNS server for one zone, on UDP, on the loopback address only. One platform
 * thread receives a packet, asks the {@link Responder} and sends the reply; the work per packet is
 * a few microseconds, so one thread is enough for a lab and no queue can grow without bound.
 *
 * <p>The socket is bound before {@link #start} returns, so a test can send a query at once, without
 * waiting. Ports below 1024 are refused: they are privileged, and this lab never needs
 * administrator rights. Thread-safe: the counters may be read from any thread.
 */
public final class DnsServer implements AutoCloseable {
```

- The imports are the JDK's UDP classes (`DatagramPacket`, `DatagramSocket`), the address classes, `Duration`, `AtomicLong` (a number that many threads can add to safely) and `RandomGenerator`.
- The class implements `AutoCloseable`, so it can be used in a `try (...)` block that closes it at the end.
- The Javadoc explains the design: one platform thread receives a packet, asks the `Responder`, and sends the reply. A packet takes microseconds, so one thread is enough and no queue can grow without bound. The socket is bound before `start` returns, so a test can send a query at once. Ports below 1024 are refused: they are privileged, and this lab never needs administrator rights.

```java
  /** Port 0 asks the operating system for any free port; tests use it. */
  public static final int ANY_FREE_PORT = 0;

  /** The lowest port a lab server may use; lower ports need administrator rights. */
  public static final int LOWEST_UNPRIVILEGED_PORT = 1024;

  private static final int HIGHEST_PORT = 65_535;
  // One octet more than the largest query accepted, so an oversized packet can be told apart.
  private static final int RECEIVE_BUFFER_OCTETS = Responder.MAX_UDP_OCTETS + 1;
  private static final Duration STOP_WAIT = Duration.ofSeconds(2);

  private final DatagramSocket socket;
  private final ZoneStore store;
  private final RandomGenerator random;
  private final Thread receiver;
  private final AtomicLong answered = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();

  private DnsServer(DatagramSocket socket, ZoneStore store, RandomGenerator random) {
    this.socket = socket;
    this.store = store;
    this.random = random;
    this.receiver = Thread.ofPlatform().name("dns-" + socket.getLocalPort()).unstarted(this::serve);
  }
```

- `ANY_FREE_PORT` is 0: asking the operating system for any free port, which tests use. `LOWEST_UNPRIVILEGED_PORT` is 1024.
- `RECEIVE_BUFFER_OCTETS` is 513: one byte more than the largest query accepted, so a packet that is too long can be told apart. A `DatagramPacket` cuts data that does not fit in its buffer, and reports the length it kept; with 513 bytes of room, a length of 513 means "longer than 512".
- `STOP_WAIT` is two seconds.
- The fields: the socket, the zone store, the random generator, the thread, and two `AtomicLong` counters for answered and dropped packets.
- The private constructor makes the thread: `Thread.ofPlatform().name("dns-" + port).unstarted(this::serve)` builds a platform thread (an operating-system thread) with a name and the method `serve` as its job, without starting it. `Thread.ofPlatform()` is Java 21.

```java
  /**
   * Binds the socket on 127.0.0.1 and starts answering.
   *
   * @param port the UDP port, 1024 to 65,535, or {@link #ANY_FREE_PORT}
   * @param store the zone to answer from
   * @param random picks weighted answers; used only by this server's thread
   * @return the running server
   * @throws IOException if the port is taken
   */
  public static DnsServer start(int port, ZoneStore store, RandomGenerator random)
      throws IOException {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(random, "random");
    if (port != ANY_FREE_PORT && (port < LOWEST_UNPRIVILEGED_PORT || port > HIGHEST_PORT)) {
      throw new IllegalArgumentException("the port must be from 1024 to 65535, but was " + port);
    }
    DatagramSocket socket =
        new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    DnsServer server = new DnsServer(socket, store, random);
    server.receiver.start();
    return server;
  }
```

- `start` checks its arguments, then checks the port: it must be 0 or from 1024 to 65,535, else `IllegalArgumentException`.
- `new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))` makes a UDP socket and **binds** it (claims the port) on the loopback address: 127.0.0.1. Packets from other computers cannot reach it. If the port is taken, it throws `IOException` (a `BindException`).
- Then it makes the server and starts the thread.

```java
  /**
   * Returns the port the server listens on.
   *
   * @return the bound port
   */
  public int port() {
    return socket.getLocalPort();
  }

  /**
   * Returns how many queries got a response.
   *
   * @return the count since start
   */
  public long answered() {
    return answered.get();
  }

  /**
   * Returns how many packets got no response: responses, packets shorter than a header, and packets
   * longer than 512 octets.
   *
   * @return the count since start
   */
  public long dropped() {
    return dropped.get();
  }
```

- `port()` returns the bound port (useful when the port was 0). `answered()` and `dropped()` return the counters. `dropped` counts packets that got no response: responses, packets shorter than a header, and packets longer than 512 octets.

```java
  /** Closes the socket and waits briefly for the receiving thread to end. */
  @Override
  public void close() {
    socket.close();
    try {
      receiver.join(STOP_WAIT);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
```

- `close` closes the socket. A thread blocked in `receive` then throws `SocketException` and ends. `receiver.join(STOP_WAIT)` waits at most two seconds for the thread (`Thread.join(Duration)` is Java 19). If the waiting thread is interrupted, the code sets the interrupt flag again (`Thread.currentThread().interrupt()`), which is the right way to hand an interrupt on to the caller.

```java
  private void serve() {
    byte[] buffer = new byte[RECEIVE_BUFFER_OCTETS];
    while (!socket.isClosed()) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (SocketException closed) {
        // close() makes receive fail; that is the normal way this loop ends.
        return;
      } catch (IOException failed) {
        dropped.incrementAndGet();
        continue;
      }
      if (packet.getLength() > Responder.MAX_UDP_OCTETS) {
        dropped.incrementAndGet();
        continue;
      }
      byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
      Optional<byte[]> response = Responder.respond(query, store.current(), random);
      if (response.isEmpty()) {
        dropped.incrementAndGet();
        continue;
      }
      send(response.get(), packet);
    }
  }
```

- `serve` is the loop of the receiving thread. It makes one buffer and runs while the socket is open.
- `socket.receive(packet)` blocks until a datagram arrives, then fills the buffer. A `SocketException` means `close()` was called: the normal end of the loop (`return`). Any other `IOException` counts the packet as dropped and goes on (`continue`).
- A packet longer than 512 octets is dropped. Otherwise only the received bytes are copied (`Arrays.copyOf(packet.getData(), packet.getLength())`), because the buffer is reused for the next packet.
- `Responder.respond(query, store.current(), random)` gets the response, using the zone that is current at this moment. An empty result means no reply. Otherwise `send` sends it.

```java
  private void send(byte[] response, DatagramPacket query) {
    try {
      socket.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
      answered.incrementAndGet();
    } catch (IOException failed) {
      // A client that went away cannot be answered; the next packet is unaffected.
      dropped.incrementAndGet();
    }
  }
}
```

- `send` sends the response to the address the query came from (`query.getSocketAddress()`). If that fails (the client went away), the packet counts as dropped, and the next packet is not affected.

### `server/ServeSettings.java`

```java
package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the {@code serve} command runs: one listener per zone, each on its own port, for a fixed
 * time. Read from a settings file with no defaults.
 *
 * @param listeners the listeners, in file order
 * @param serveFor how long to serve before stopping
 * @param randomSeed the seed of the generator that picks weighted answers, so a run can be repeated
 *     exactly
 */
public record ServeSettings(List<Listener> listeners, Duration serveFor, long randomSeed) {
```

- `ServeSettings` is what the `serve` command needs: the listeners, how long to serve, and a random seed. A **seed** is the number a pseudo-random generator starts from; the same seed gives the same sequence, so a run can be repeated exactly.
- `Listener` is a nested record defined at the end.

```java
  private static final Pattern LISTENER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");
  private static final int MAX_SERVE_SECONDS = 3_600;
  private static final int HIGHEST_PORT = 65_535;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if there is no listener or two share a port
   */
  public ServeSettings {
    listeners = List.copyOf(listeners);
    Objects.requireNonNull(serveFor, "serveFor");
    if (listeners.isEmpty()) {
      throw new IllegalArgumentException("at least one listener is needed");
    }
    if (listeners.stream().map(Listener::port).distinct().count() != listeners.size()) {
      throw new IllegalArgumentException("two listeners share a port");
    }
  }
```

- `LISTENER_NAME` allows a lower-case letter followed by up to 19 lower-case letters, digits or hyphens. `MAX_SERVE_SECONDS` is 3,600 (an hour).
- The compact constructor copies the list and refuses no listener and two listeners on one port (`distinct().count()` counts different ports).

```java
  /**
   * Reads the settings and loads every zone they name.
   *
   * @param reader the settings
   * @return the settings, or one line per problem in the settings or in a zone
   */
  public static Result<ServeSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    List<String> names =
        reader.wordsIn(
            "listeners",
            List.of(reader.text("listeners").split(" *, *")).stream()
                .filter(name -> LISTENER_NAME.matcher(name).matches())
                .toList());
    int seconds = reader.intBetween("serve.seconds", 1, MAX_SERVE_SECONDS);
    long seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<Listener> listeners = new ArrayList<>();
    List<String> zoneProblems = new ArrayList<>();
    for (String name : names) {
      int port =
          reader.intBetween(
              "listener." + name + ".port", DnsServer.LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      switch (ZoneLoader.load(reader, "listener." + name + ".zone")) {
        case Result.Success<Zone, List<String>>(Zone zone) ->
            listeners.add(new Listener(name, port, zone));
        case Result.Error<Zone, List<String>>(List<String> problems) ->
            problems.forEach(problem -> zoneProblems.add(name + ": " + problem));
      }
    }
    List<String> problems = new ArrayList<>(reader.problems());
    problems.addAll(zoneProblems);
    // Checked here as well as in the constructor, so a person sees a settings problem, not a crash.
    if (listeners.stream().map(Listener::port).distinct().count() != listeners.size()) {
      problems.add("two listeners share a port");
    }
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(new ServeSettings(listeners, Duration.ofSeconds(seconds), seed));
  }
```

- `from` reads and checks every key, and loads every zone. It collects problems and reports them all together.
- `reader.text("listeners").split(" *, *")` cuts the list at commas, ignoring spaces around them. `reader.wordsIn(...)` checks each name against the allow-list built by filtering the same names with the regular expression: a name that does not match the pattern is not in the list, so it is reported.
- `intBetween` reads whole numbers within limits: the number of seconds (1 to 3,600) and the seed (0 to the largest `int`; the seed is stored in a `long`).
- For each listener: the port (from 1024 to 65,535) and the zone via `ZoneLoader.load(reader, "listener.<name>.zone")`. A zone problem is recorded with the listener's name in front.
- Problems from the reader come first, then the zone problems. If any, the result is an error. Otherwise a `ServeSettings`.

```java
  /**
   * One zone on one port.
   *
   * @param name the listener's name in the settings
   * @param port the UDP port on 127.0.0.1
   * @param zone the zone it serves
   */
  public record Listener(String name, int port, Zone zone) {

    /** Checks the fields. */
    public Listener {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(zone, "zone");
    }
  }
}
```

- `Listener` is one zone on one port: its name, the UDP port, and the zone. The constructor refuses a null name or zone.

### `server/ServeCli.java`

```java
package io.plainround.seatwave.dns.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point that serves the lab zones on 127.0.0.1 for a fixed time, so a reader can
 * query them with {@code dig @127.0.0.1 -p <port>}. Exit status 2 means bad usage, settings or zone
 * files; 1 means a port could not be bound.
 */
public final class ServeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;

  private ServeCli() {}

```

- `ServeCli` is the command-line entry point. The Javadoc defines the exit status: 2 for bad usage, settings or zone files; 1 for a port that could not be bound.
- An **exit status** is the number a program gives back to the shell when it ends. 0 is success; other numbers are failures. The comment says that 2 is the shell convention for "the command was used incorrectly".

```java
  /**
   * Serves the zones named in a settings file.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <serve.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (ServeSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<ServeSettings, List<String>>(ServeSettings settings) -> serve(settings);
        case Result.Error<ServeSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException failed) {
      System.err.println("serving failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

- `main` is where the program starts. `arguments` holds the words typed after the command. Exactly one is needed, the settings file; otherwise a usage message goes to the error stream (`System.err`) and `System.exit(2)` ends the program.
- `SettingsReader.load(Path.of(arguments[0]))` reads the properties file; `new SettingsReader(...)` wraps it; `ServeSettings.from(...)` returns a `Result`.
- The `switch` has two arms: on success, `serve(settings)`; on error, each problem is printed with the prefix `invalid:` and the program exits with 2.
- `catch (IOException failed)` handles a file that cannot be read or a port that cannot be bound, and exits with 1.

```java
  private static void serve(ServeSettings settings) throws IOException, InterruptedException {
    SplittableRandom seeds = new SplittableRandom(settings.randomSeed());
    List<DnsServer> servers = new ArrayList<>();
    try {
      for (ServeSettings.Listener listener : settings.listeners()) {
        // Each server's thread gets its own generator, split from one seed, so runs repeat.
        servers.add(
            DnsServer.start(
                listener.port(), new ZoneStore(listener.zone(), Clock.systemUTC()), seeds.split()));
      }
      System.out.printf(
          Locale.ROOT, "serving on 127.0.0.1 (UDP) for %d s:%n", settings.serveFor().toSeconds());
      for (ServeSettings.Listener listener : settings.listeners()) {
        System.out.printf(
            Locale.ROOT,
            "  %-9s port %d  zone %-18s %d records%n",
            listener.name(),
            listener.port(),
            listener.zone().origin(),
            listener.zone().records().size());
      }
      Thread.sleep(settings.serveFor());
      for (int index = 0; index < servers.size(); index++) {
        System.out.printf(
            Locale.ROOT,
            "  %-9s answered %d, dropped %d%n",
            settings.listeners().get(index).name(),
            servers.get(index).answered(),
            servers.get(index).dropped());
      }
    } finally {
      servers.forEach(DnsServer::close);
    }
  }
```

- `serve` makes one `SplittableRandom` from the seed, and one server per listener. `seeds.split()` gives each server its own generator, split off the first, so runs repeat and no two threads share a generator (a generator is not safe to share).
- `new ZoneStore(listener.zone(), Clock.systemUTC())` wraps the zone (see `java-migration-walkthrough.md`); `Clock.systemUTC()` is the real clock.
- It prints a header and one line per listener: `printf` with `Locale.ROOT`; `%-9s` is a text left-aligned in nine characters, `%d` a whole number, `%-18s` a text in 18 characters, `%n` a line break.
- `Thread.sleep(settings.serveFor())` makes the main thread wait for the serving time while the server threads work (`Thread.sleep(Duration)` is Java 19).
- After the wait, it prints how many packets each server answered and dropped.
- The `finally` block closes every server whatever happens: `servers.forEach(DnsServer::close)`.

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only.

**1. Start the servers** (stops after 300 seconds):

```text
./gradlew -q serve --args='config/serve.properties'
```

**2. Ask the root server for `seatwave.example`:** a referral, not an answer.

```text
dig @127.0.0.1 -p 5301 seatwave.example A +norecurse
```

```text
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 27921
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 1
;; AUTHORITY SECTION:
example.		172800	IN	NS	a.tld-lab.example.
;; ADDITIONAL SECTION:
a.tld-lab.example.	172800	IN	A	127.0.0.1
;; MSG SIZE  rcvd: 105
```

The flags show `qr` but not `aa`: the root server says "ask someone else". The glue is in the additional section.

**3. Follow a CNAME.** `www` is an alias of the apex inside the same zone:

```text
dig @127.0.0.1 -p 5303 www.seatwave.example A +norecurse
```

```text
;; flags: qr aa; QUERY: 1, ANSWER: 2, AUTHORITY: 0, ADDITIONAL: 0
;; ANSWER SECTION:
www.seatwave.example.	3600	IN	CNAME	seatwave.example.
seatwave.example.	3600	IN	A	192.0.2.10
```

**4. Ask for a type the name does not have, and for a name outside the zone:**

```text
dig @127.0.0.1 -p 5303 seatwave.example MX +norecurse
dig @127.0.0.1 -p 5303 example.com A +norecurse
```

```text
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 54790
;; flags: qr aa; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 0
;; AUTHORITY SECTION:
seatwave.example.	300	IN	SOA	ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300
;; ->>HEADER<<- opcode: QUERY, status: REFUSED, id: 31394
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 0, ADDITIONAL: 0
```

The first is "no data" (NOERROR with an SOA of TTL 300, the smaller of the SOA's TTL 3600 and its minimum 300). The second is REFUSED.

**5. Make the server refuse a broken zone:**

```text
./gradlew -q serve --args='config/serve-broken.properties'
```

It prints the four `invalid:` lines quoted in the README and exits with status 2, which Gradle reports as a failed build.

**6. Stop.** Press Ctrl-C in the terminal of step 1, or wait. No process is left running.

## Mistakes people make

- **Answering responses.** A server that answers a response can ping-pong forever with another one. `Responder` drops them.
- **Sending a huge answer over UDP.** Over 512 bytes the answer is cut and flagged; the client must retry over TCP.
- **Putting a CNAME beside other records.** A CNAME must be the only record of its name, and it cannot be at the apex. `Zone.of` refuses both.
- **Forgetting the glue.** A name server inside its own zone needs an address in that zone. `Zone.of` refuses the zone without it.
- **Giving one record set different TTLs.** A set has one TTL (RFC 2181 section 5.2). `Zone.of` refuses it.
- **Letting one random generator be used by two threads.** Each server has its own, split from one seed.
- **Binding to all addresses.** The socket is bound to the loopback address, so only this machine can reach the server.

## Check yourself

1. What is the difference between a referral and an answer? (A referral says "ask these name servers" and has no AA flag; an answer is final and has AA set.)
2. Why does a zone hold glue for `ns1.seatwave.example.`? (The name server lives inside the zone it serves; without an address in the zone nobody could find it.)
3. What does the server do with a packet whose response bit is set? (Nothing: no reply, so two servers cannot bounce packets forever.)
4. Why is a received packet copied with `Arrays.copyOf` before it is handled? (The receive buffer is reused for the next packet.)
5. How does `weighted` choose the first address for weights 90 and 10? (It picks a number from 0 to 99; 0 to 89 selects the first record and 90 to 99 the second.)

## Further reading

- RFC 1035, sections 4 and 5 (messages, master files): https://www.rfc-editor.org/rfc/rfc1035
- RFC 1034, section 3.6.2 (aliases and CNAME rules): https://www.rfc-editor.org/rfc/rfc1034
- RFC 2181, section 5.2 (one TTL per record set): https://www.rfc-editor.org/rfc/rfc2181
- RFC 2308, Negative Caching of DNS Queries: https://www.rfc-editor.org/rfc/rfc2308
- RFC 8020, NXDOMAIN: There Really Is Nothing Underneath: https://www.rfc-editor.org/rfc/rfc8020
- RFC 9619, In the DNS, QDCOUNT Is (Usually) One: https://www.rfc-editor.org/rfc/rfc9619
- The Java SE 25 API, `java.net.DatagramSocket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/DatagramSocket.html
- The Java SE 25 API, `java.util.random.RandomGenerator`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/random/RandomGenerator.html
- CoreDNS `loadbalance` plugin: https://coredns.io/plugins/loadbalance/
