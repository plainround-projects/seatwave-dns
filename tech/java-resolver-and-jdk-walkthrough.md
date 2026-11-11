# The `resolver` and `jdk` packages, line by line

## What it is

This file reads the Java code of two packages, one line at a time.

- The `resolver` package has seven files: `Upstream.java`, `DnsClient.java`, `InMemoryUpstream.java`, `CachingResolver.java`, `CachePolicy.java`, `Resolution.java` and `LookupProblem.java`. Together they are the **client side of DNS**: code that asks a server a question and keeps the answer for as long as its time to live allows.
- The `jdk` package has three files: `LabResolverProvider.java`, `LabResolver.java` and `JdkLookupCli.java`. Together they plug the lab server into the JDK's own name lookup, `InetAddress`.

A **resolver** is a program that finds the answer to a DNS question on behalf of someone else. A **cache** is a memory of earlier answers, kept so the same question need not be asked twice. The **TTL** (time to live) of a record is the number of seconds a cache may keep it.

## Why this repository uses it

Module 6 asks what a TTL really does. A TTL is an instruction to caches: "you may keep this for N seconds". To see its effect you need caches that obey it, caches that keep answers longer than they should, and the cache that every Java program already has. This code provides all three:

- `CachingResolver` is a cache that honors TTLs as far as its `CachePolicy` allows.
- A `CachePolicy` with a minimum hold longer than the TTL is a client that **pins** old answers (failure mode 4 of the module).
- `InetAddress.getByName` is the JDK's own lookup, with its own cache whose time comes from `networkaddress.cache.ttl` and not from the record's TTL. The `jdk` package shows that.

## Where it appears

| File | What it does |
|---|---|
| `resolver/Upstream.java` | The one-method interface for "where a question goes on a cache miss". |
| `resolver/DnsClient.java` | An `Upstream` that sends a real UDP packet and waits for the matching answer. |
| `resolver/InMemoryUpstream.java` | An `Upstream` that hands the same bytes straight to `Responder`, without a socket. |
| `resolver/CachingResolver.java` | The cache: hit, miss, negative answers, stale answers. |
| `resolver/CachePolicy.java` | The hold-time rules of one cache. |
| `resolver/Resolution.java` | The result of one lookup, and where it came from. |
| `resolver/LookupProblem.java` | Why a question got no usable answer, as values. |
| `jdk/LabResolverProvider.java` | The plug-in the JDK discovers through `META-INF/services`. |
| `jdk/LabResolver.java` | The JDK resolver that asks the lab server. |
| `jdk/JdkLookupCli.java` | The `jdkLookup` command that measures the JVM's cache. |
| `src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider` | The file that tells the JDK about the plug-in (see `java-udp-and-resolver-api.md`). |

## How it works underneath

**A lookup through `CachingResolver`.** The resolver reads the clock. It looks for an entry for (name, type). If there is one and the clock is before its expiry time, that is a **hit**: the answer comes from memory and no packet is sent. Otherwise it is a **miss**: it asks the `Upstream`. A good answer is stored with an expiry time of now plus the **hold time**. A failure is not stored; if the resolver has an old, expired entry and the policy allows it, it serves that stale entry (RFC 8767).

**The hold time of a positive answer** is the smallest TTL of the records in it, raised to the policy's minimum hold or lowered to its maximum hold. An honest resolver has minimum 0, so it keeps the answer exactly as long as the TTL says. A pinning resolver has a minimum above the TTL, so it keeps the old address after the record has changed.

**The hold time of a negative answer** ("no such name", or "no data") is the smaller of the SOA's TTL and its minimum field (RFC 2308), capped by the policy's `maximumNegativeHold`.

**Time is injected.** `CachingResolver` never calls `System.currentTimeMillis()`. It receives a `java.time.Clock`. A test or a simulation gives it a `SimulatedClock` that moves only when told to, so an experiment of two days runs in a second and gives the same numbers every time. The real command line gives it `Clock.systemUTC()`.

**Two upstreams, the same bytes.** `DnsClient` sends the encoded question in a UDP datagram to a server. `InMemoryUpstream` encodes the question, calls `Responder.respond` in the same process, and decodes the reply. Both go through `MessageCodec`, so a simulation exercises the same code as the real server, only faster.

**The JDK's own lookup.** `InetAddress.getByName("seatwave.example")` normally asks the operating system. Since Java 18 a program can ask the JDK to use a different **resolver**, supplied by a class that is listed in a `META-INF/services` file (JEP 418). The JDK keeps its own cache on top of any resolver. With the provider set up, the JVM asks the lab server once for A and once for AAAA, and after that it answers 999 more lookups from its own cache. The README's output shows `server queries` of 2 for 1,000 lookups.

## Walkthrough of this repository's use

### `resolver/Upstream.java`

```java
package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;

/**
 * Where a resolver sends a question it cannot answer from its cache. Two kinds exist: {@link
 * DnsClient}, which sends a real UDP packet, and {@link InMemoryUpstream}, which hands the same
 * bytes to the server's {@code Responder} directly, so a simulation of hours runs in a second.
 */
@FunctionalInterface
public interface Upstream {

  /**
   * Asks one question.
   *
   * @param question the question
   * @return the response, or why there is none
   */
  Result<Message, LookupProblem> ask(Question question);
}
```

- `@FunctionalInterface` marks an interface with exactly one abstract method. Such an interface can be written as a lambda. The compiler fails if a second abstract method is added.
- `ask(Question)` returns `Result<Message, LookupProblem>`: the response, or why there is none. A server that does not answer is an expected failure of a network, so it is a value.
- The Javadoc names the two implementations.

### `resolver/LookupProblem.java`

```java
package io.plainround.seatwave.dns.resolver;

import io.plainround.seatwave.dns.message.MessageProblem;
import java.time.Duration;
import java.util.Objects;

/** Why a question got no usable response. Every variant is an expected failure of the network. */
public sealed interface LookupProblem {

  /**
   * Says why, in one line.
   *
   * @return the reason
   */
  String describe();
```

- A sealed interface with a `describe()` method, like `MessageProblem` and `MigrationRefusal`.

```java
  /**
   * No response arrived in time.
   *
   * @param waited how long the client waited
   */
  record TimedOut(Duration waited) implements LookupProblem {

    /** Checks the field. */
    public TimedOut {
      Objects.requireNonNull(waited, "waited");
    }

    @Override
    public String describe() {
      return "no response within " + waited.toMillis() + " ms";
    }
  }
```

- `TimedOut` holds how long the client waited. `waited.toMillis()` converts a `Duration` to milliseconds for the text.

```java
  /**
   * The server's port is closed: the operating system answered "port unreachable".
   *
   * @param detail the operating system's message
   */
  record Unreachable(String detail) implements LookupProblem {

    /** Checks the field. */
    public Unreachable {
      Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String describe() {
      return "the server is unreachable: " + detail;
    }
  }
```

- `Unreachable` means the server's port is closed: the operating system answered "port unreachable".

```java
  /**
   * A response arrived but could not be read.
   *
   * @param problem what was wrong with it
   */
  record Malformed(MessageProblem problem) implements LookupProblem {

    /** Checks the field. */
    public Malformed {
      Objects.requireNonNull(problem, "problem");
    }

    @Override
    public String describe() {
      return "unreadable response: " + problem.describe();
    }
  }
}
```

- `Malformed` means a response arrived that could not be read; it wraps the `MessageProblem` and uses its `describe()`.

### `resolver/DnsClient.java`

```java
package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
```

- Imports: `SecureRandom` (a random-number generator suitable for security), `UncheckedIOException` (an unchecked wrapper around an `IOException`), `PortUnreachableException` and `SocketTimeoutException` (two kinds of network failure), and the datagram classes.

```java
/**
 * Sends one question at a time to one DNS server over UDP and waits for the matching response.
 *
 * <p>Each query gets a random identifier from {@link SecureRandom}, and a response counts only if
 * its identifier and its question match the query; anything else is ignored until the timeout. That
 * is the defense RFC 5452 asks for against forged answers. The client asks without the
 * recursion-desired flag, as a resolver asks an authoritative server. Not thread-safe: one client
 * per thread; close it to release its socket.
 */
public final class DnsClient implements Upstream, AutoCloseable {

  // Identifiers are 16 bits; the response buffer holds the largest UDP message without EDNS.
  private static final int ID_SPACE = 1 << 16;
  private static final int RESPONSE_BUFFER_OCTETS = 512;

  private final DatagramSocket socket;
  private final Duration timeout;
  private final SecureRandom random = new SecureRandom();
```

- The Javadoc says what the client does: it sends one question at a time to one server over UDP and waits for the matching answer. Each query gets a random identifier from `SecureRandom`. An answer counts only if both its identifier and its question match; anything else is ignored until the timeout. That is the defense RFC 5452 asks for against forged answers: an attacker who cannot see the query must guess a 16-bit number.
- `ID_SPACE` is `1 << 16`, which is 65,536: the number of possible identifiers. `<<` shifts bits left; `1 << 16` is 2 to the power 16. `RESPONSE_BUFFER_OCTETS` is 512.
- The socket, the timeout and the generator are fields. The class implements `Upstream` and `AutoCloseable`.

```java
  /**
   * Opens a socket connected to one server on the loopback address.
   *
   * @param serverPort the server's UDP port
   * @param timeout the longest wait for each response
   */
  public DnsClient(int serverPort, Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("the timeout must be positive");
    }
    try {
      socket = new DatagramSocket();
      // connect() makes the socket accept packets from this server only, and makes the system
      // report a closed port as PortUnreachableException instead of a silent timeout.
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort));
      socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    } catch (IOException failed) {
      throw new UncheckedIOException("cannot open a UDP socket", failed);
    }
  }
```

- The constructor refuses a zero or negative timeout.
- `new DatagramSocket()` opens a UDP socket on a port the system picks. `socket.connect(address)` on a UDP socket does not open a connection (UDP has none). It only fixes the remote address: the socket then accepts packets from that address only, and the system reports a closed port as `PortUnreachableException` on the next send or receive, instead of a silent timeout. The address is the loopback address, 127.0.0.1, and the server's port.
- `socket.setSoTimeout(ms)` sets the longest time a `receive` may block. Without it, a lost packet would block the program forever. `Math.toIntExact` converts a `long` to an `int` and throws if it does not fit.
- An `IOException` while opening is wrapped in an `UncheckedIOException` so the constructor needs no `throws`.

```java
  @Override
  public Result<Message, LookupProblem> ask(Question question) {
    Objects.requireNonNull(question, "question");
    int id = random.nextInt(ID_SPACE);
    byte[] query = MessageCodec.encode(Message.query(id, question));
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      socket.send(new DatagramPacket(query, query.length));
      byte[] buffer = new byte[RESPONSE_BUFFER_OCTETS];
      while (true) {
        long remainingMillis = (deadline - System.nanoTime()) / Duration.ofMillis(1).toNanos();
        if (remainingMillis <= 0) {
          return Result.error(new LookupProblem.TimedOut(timeout));
        }
        socket.setSoTimeout(Math.toIntExact(remainingMillis));
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        byte[] bytes = Arrays.copyOf(packet.getData(), packet.getLength());
        switch (MessageCodec.decode(bytes)) {
          case Result.Success<Message, MessageProblem>(Message response) -> {
            if (isAnswerTo(response, id, question)) {
              return Result.success(response);
            }
          }
          case Result.Error<Message, MessageProblem>(MessageProblem problem) -> {
            return Result.error(new LookupProblem.Malformed(problem));
          }
        }
      }
    } catch (SocketTimeoutException timedOut) {
      return Result.error(new LookupProblem.TimedOut(timeout));
    } catch (PortUnreachableException closedPort) {
      // The exception carries no message of its own, so name the port that refused the packet.
      return Result.error(
          new LookupProblem.Unreachable("nothing is listening on port " + socket.getPort()));
    } catch (IOException failed) {
      throw new UncheckedIOException("the UDP socket failed", failed);
    }
  }
```

- `ask` builds the query with a random identifier and no recursion-desired flag (`Message.query`), and encodes it.
- `deadline` is a point in time on the monotonic clock `System.nanoTime()` plus the timeout. `nanoTime` is the right clock for measuring elapsed time: it never jumps when someone changes the time of day.
- It sends the datagram, then loops. Each time round it computes the time left in milliseconds (`/ Duration.ofMillis(1).toNanos()` converts nanoseconds to milliseconds). If none is left, it returns `TimedOut`. Otherwise it sets the socket timeout to the time left and calls `receive`, so the **total** wait never exceeds the timeout even when wrong packets keep arriving.
- It copies the received bytes out of the buffer (`getLength()` is how many arrived) and decodes them. A response that decodes fine is returned only if `isAnswerTo` says it matches; otherwise the loop goes on and waits for another packet. A packet that cannot be decoded gives `Malformed`.
- Three `catch` clauses: `SocketTimeoutException` gives `TimedOut`; `PortUnreachableException` gives `Unreachable`; any other `IOException` is a surprise and becomes an `UncheckedIOException`.

```java
  /** Closes the socket. */
  @Override
  public void close() {
    socket.close();
  }

  private static boolean isAnswerTo(Message response, int id, Question question) {
    return response.isResponse()
        && response.id() == id
        && response.questions().size() == 1
        && response.questions().getFirst().equals(question);
  }
}
```

- `close` closes the socket and releases the port.
- `isAnswerTo` checks that the packet is a response, that its identifier equals the one sent, and that it repeats exactly the one question asked. `getFirst()` is Java 21.

### `resolver/InMemoryUpstream.java`

```java
package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.Responder;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * An upstream that encodes the question, hands the bytes to the server's {@link Responder} and
 * decodes the reply, without a socket. The bytes are exactly those a UDP server would send, so a
 * simulation exercises the same code as the real server, only faster. Not thread-safe.
 */
public final class InMemoryUpstream implements Upstream {

  // Identifiers matter only on a shared network; here a counter keeps runs repeatable.
  private static final int ID_SPACE = 1 << 16;

  private final ZoneStore store;
  private final RandomGenerator random;
  private int nextId;
  private long queries;
```

- This upstream asks `Responder` directly. The comment explains the identifier: it matters only on a shared network, so here a counter keeps runs repeatable.

```java
  /**
   * Creates the upstream.
   *
   * @param store the zone the stand-in server answers from
   * @param random picks weighted answers
   */
  public InMemoryUpstream(ZoneStore store, RandomGenerator random) {
    this.store = Objects.requireNonNull(store, "store");
    this.random = Objects.requireNonNull(random, "random");
  }
```

- The constructor takes the `ZoneStore` that the stand-in server answers from, and a random generator for weighted answers.

```java
  @Override
  public Result<Message, LookupProblem> ask(Question question) {
    queries++;
    nextId = (nextId + 1) % ID_SPACE;
    byte[] query = MessageCodec.encode(Message.query(nextId, question));
    Optional<byte[]> response = Responder.respond(query, store.current(), random);
    if (response.isEmpty()) {
      return Result.error(new LookupProblem.Unreachable("the server dropped the query"));
    }
    return switch (MessageCodec.decode(response.get())) {
      case Result.Success<Message, MessageProblem>(Message message) -> Result.success(message);
      case Result.Error<Message, MessageProblem>(MessageProblem problem) ->
          Result.error(new LookupProblem.Malformed(problem));
    };
  }
```

- `ask` counts the query, advances the identifier (modulo 65,536), encodes the query, and calls `Responder.respond(query, store.current(), random)`. An empty reply means the server dropped the query: `Unreachable`. Otherwise the reply bytes are decoded again, which exercises the codec in both directions.

```java
  /**
   * Returns how many questions reached the stand-in server.
   *
   * @return the count since creation
   */
  public long queries() {
    return queries;
  }
}
```

- `queries()` says how many questions reached the stand-in server. The simulation uses it to compute the server's query rate.

### `resolver/CachePolicy.java`

```java
package io.plainround.seatwave.dns.resolver;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How long a caching resolver keeps answers. An honest resolver keeps a positive answer for its
 * TTL; one with a minimum hold above the TTL is a client that "pins" old answers (failure mode 4).
 *
 * @param minimumHold the shortest time a positive answer is kept, even if its TTL is shorter
 * @param maximumHold the longest time a positive answer is kept, even if its TTL is longer
 * @param maximumNegativeHold the longest time a "no such name" or "no data" answer is kept
 * @param serveStaleFor how long after expiry an answer may still be served when the server cannot
 *     be reached (RFC 8767); zero turns it off
 */
public record CachePolicy(
    Duration minimumHold,
    Duration maximumHold,
    Duration maximumNegativeHold,
    Duration serveStaleFor) {
```

- The four durations: the shortest time a positive answer is kept even if its TTL is shorter (`minimumHold`; a value above the TTL is a pinning client); the longest (`maximumHold`); the longest a negative answer is kept; and how long after expiry an answer may still be served when the server cannot be reached (RFC 8767; zero turns it off).

```java
  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if a duration is negative or the minimum exceeds the maximum
   */
  public CachePolicy {
    for (Duration duration :
        List.of(minimumHold, maximumHold, maximumNegativeHold, serveStaleFor)) {
      Objects.requireNonNull(duration, "duration");
      if (duration.isNegative()) {
        throw new IllegalArgumentException("a cache duration must not be negative");
      }
    }
    if (minimumHold.compareTo(maximumHold) > 0) {
      throw new IllegalArgumentException("the minimum hold must not exceed the maximum hold");
    }
  }
```

- The compact constructor loops over the four durations. Each must be non-null and not negative. The minimum must not exceed the maximum.

```java
  /**
   * Returns how long a positive answer with this TTL is kept.
   *
   * @param ttl the answer's TTL
   * @return the TTL, raised to the minimum hold or lowered to the maximum hold
   */
  public Duration holdFor(Duration ttl) {
    if (ttl.compareTo(minimumHold) < 0) {
      return minimumHold;
    }
    return ttl.compareTo(maximumHold) > 0 ? maximumHold : ttl;
  }
}
```

- `holdFor(ttl)` returns the minimum hold if the TTL is below it, the maximum hold if the TTL is above it, otherwise the TTL. `compareTo` returns a negative number, zero or a positive number.

### `resolver/Resolution.java`

```java
package io.plainround.seatwave.dns.resolver;

import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/**
 * The result of one lookup through a caching resolver.
 *
 * @param source where the answer came from
 * @param code the response code; {@link ResponseCode#SERVER_FAILURE} when no server answered
 * @param records the answer records, empty for a negative answer or a failure
 */
public record Resolution(Source source, ResponseCode code, List<ResourceRecord> records) {

  /** Checks and copies the fields. */
  public Resolution {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(code, "code");
    records = List.copyOf(records);
  }
```

- The result of one lookup: where the answer came from (`source`), the response code, and the records. The constructor refuses nulls and copies the list.

```java
  /**
   * Returns the addresses in the answer, in answer order.
   *
   * @return the A and AAAA addresses; empty if there are none
   */
  public List<InetAddress> addresses() {
    return records.stream()
        .map(ResourceRecord::data)
        .filter(RecordData.Address.class::isInstance)
        .map(data -> ((RecordData.Address) data).address())
        .toList();
  }
```

- `addresses()` returns the A and AAAA addresses in answer order. `filter(RecordData.Address.class::isInstance)` keeps only the data that is an `Address` (`Class::isInstance` is a method reference to the instance test); `map(data -> ((RecordData.Address) data).address())` casts and takes the address.

```java
  /** Where an answer came from. */
  public enum Source {
    /** The resolver asked the server, because it had no valid copy. */
    SERVER,
    /** The resolver answered from its cache, inside the hold time. */
    CACHE,
    /** The server could not be reached, and the resolver served an expired copy (RFC 8767). */
    STALE,
    /** The server could not be reached, and the resolver had nothing to serve. */
    FAILED
  }
}
```

- `Source` is an enum nested in the record: `SERVER` (asked the server), `CACHE` (answered from memory inside the hold time), `STALE` (the server could not be reached and an expired copy was served) and `FAILED` (the server could not be reached and nothing could be served).

### `resolver/CachingResolver.java`

```java
package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
```

- Imports for the message types, `Clock`, `Duration`, `Instant` (a point in time), and `HashMap`.

```java
/**
 * A resolver with a cache, standing for the recursive resolver of an internet provider, or for a
 * client library that caches. It honors TTLs as its {@link CachePolicy} allows: a positive answer
 * is kept for the smallest TTL in it, raised or lowered by the policy; a negative answer for the
 * shorter of the SOA's TTL and its minimum field (RFC 2308 section 5); and when the server cannot
 * be reached, an expired answer may be served for a while (RFC 8767).
 *
 * <p>Time comes from an injected {@link Clock}, so a test or a simulation decides what "now" is.
 * Not thread-safe: one resolver belongs to one thread.
 */
public final class CachingResolver {

  private final Upstream upstream;
  private final Clock clock;
  private final CachePolicy policy;
  private final Map<Key, Entry> cache = new HashMap<>();
  private long hits;
  private long misses;
```

- The Javadoc: a cache standing for the recursive resolver of an internet provider, or for a client library that caches. It is **not thread-safe**: one resolver belongs to one thread.
- The fields: the upstream, the clock, the policy, the cache (a `HashMap` from `Key` to `Entry`), and two counters, `hits` and `misses`.

```java
  /**
   * Creates a resolver with an empty cache.
   *
   * @param upstream where questions go on a cache miss
   * @param clock the clock
   * @param policy how long answers are kept
   */
  public CachingResolver(Upstream upstream, Clock clock, CachePolicy policy) {
    this.upstream = Objects.requireNonNull(upstream, "upstream");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.policy = Objects.requireNonNull(policy, "policy");
  }
```

- The constructor refuses nulls. `Objects.requireNonNull(x, "x")` returns `x`, so it can be used in the assignment.

```java
  /**
   * Resolves a name, from the cache when it holds a valid copy.
   *
   * @param name the name
   * @param type the record type
   * @return the answer and where it came from
   */
  public Resolution resolve(DomainName name, RecordType type) {
    Instant now = clock.instant();
    Key key = new Key(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
    Optional<Entry> cached = Optional.ofNullable(cache.get(key));
    if (cached.isPresent() && now.isBefore(cached.get().expiresAt())) {
      hits++;
      return cached.get().as(Resolution.Source.CACHE);
    }
    misses++;
    return switch (upstream.ask(Question.of(name, type))) {
      case Result.Success<Message, LookupProblem>(Message response) -> store(key, response, now);
      case Result.Error<Message, LookupProblem> failed -> {
        boolean canServeStale =
            cached.isPresent()
                && now.isBefore(cached.get().expiresAt().plus(policy.serveStaleFor()));
        yield canServeStale
            ? cached.get().as(Resolution.Source.STALE)
            : new Resolution(Resolution.Source.FAILED, ResponseCode.SERVER_FAILURE, List.of());
      }
    };
  }
```

- `resolve` reads `now` once. `Key` is a record of name and type; being a record, it has `equals` and `hashCode`, so it works as a map key.
- `Optional.ofNullable(cache.get(key))` wraps a possible `null` from the map.
- A **hit**: there is an entry and `now.isBefore(expiresAt)`. The test is strict: at the exact expiry instant the entry is already stale. The hit counter goes up and the entry is returned with source `CACHE`.
- A **miss**: the counter goes up and the upstream is asked, with a `switch` over the `Result`. On success, `store` records the answer. On error, the resolver may serve stale: `canServeStale` is true when there is an entry and `now` is before its expiry plus `serveStaleFor`. If so, the old entry is returned with source `STALE`. Otherwise the result is `FAILED` with code `SERVER_FAILURE` and no records.
- `yield` gives the value of the block to the `switch` expression.

```java
  /**
   * Returns the share of lookups answered from the cache.
   *
   * @return hits divided by all lookups, or 0 before the first lookup
   */
  public double cacheHitRatio() {
    long lookups = hits + misses;
    return lookups == 0 ? 0 : (double) hits / lookups;
  }

  /**
   * Returns how many questions went to the server.
   *
   * @return the misses since creation
   */
  public long serverQueries() {
    return misses;
  }
```

- `cacheHitRatio` is hits divided by all lookups, or 0 before the first. `(double) hits / lookups` converts to a decimal number first; dividing two `long`s would drop the fraction.
- `serverQueries` returns the misses: how many questions went to the server.

```java
  private Resolution store(Key key, Message response, Instant now) {
    Optional<ResponseCode> code = ResponseCode.fromCode(response.responseCodeValue());
    boolean isUsable =
        code.isPresent()
            && (code.get() == ResponseCode.NO_ERROR || code.get() == ResponseCode.NAME_ERROR);
    if (!isUsable) {
      // REFUSED, SERVFAIL and the rest are not cached here (RFC 9520 allows a brief hold).
      return new Resolution(
          Resolution.Source.SERVER, code.orElse(ResponseCode.SERVER_FAILURE), List.of());
    }
    List<ResourceRecord> answers = response.answers();
    boolean hasData = answers.stream().anyMatch(record -> record.type() == key.type());
    Duration hold;
    if (hasData) {
      long smallestTtl = answers.stream().mapToLong(ResourceRecord::ttlSeconds).min().orElse(0);
      hold = policy.holdFor(Duration.ofSeconds(smallestTtl));
    } else {
      long negativeTtl =
          response.authority().stream()
              .filter(record -> record.type() == RecordType.SOA)
              .mapToLong(soa ->
                  Math.min(soa.ttlSeconds(), ((RecordData.Authority) soa.data()).minimumSeconds()))
              .findFirst()
              .orElse(0);
      Duration negative = Duration.ofSeconds(negativeTtl);
      hold =
          negative.compareTo(policy.maximumNegativeHold()) > 0
              ? policy.maximumNegativeHold()
              : negative;
    }
    Entry entry = new Entry(code.get(), hasData ? answers : List.of(), now.plus(hold));
    cache.put(key, entry);
    return entry.as(Resolution.Source.SERVER);
  }
```

- `store` decides what to keep. It looks up the response code. Only NOERROR and NXDOMAIN are usable. Any other code (REFUSED, SERVFAIL and the rest) is returned to the caller with the server's code but is **not cached**; the comment notes RFC 9520 allows a brief hold, which this lab does not use.
- `hasData` is true when the answer holds a record of the type asked for.
- With data, the hold is the policy's `holdFor` applied to the **smallest TTL** in the answer (`mapToLong(...).min().orElse(0)`). A CNAME chain is held for the shortest of its records.
- Without data (no data, or a name that does not exist), the hold is the SOA's TTL or minimum field, whichever is smaller, capped by `maximumNegativeHold`. The SOA is found in the authority section (`filter(record -> record.type() == RecordType.SOA)`). With no SOA the hold is zero.
- The entry is stored with its expiry, `now.plus(hold)`, and returned with source `SERVER`.

```java
  private record Key(DomainName name, RecordType type) {}

  private record Entry(ResponseCode code, List<ResourceRecord> records, Instant expiresAt) {

    Resolution as(Resolution.Source source) {
      return new Resolution(source, code, records);
    }
  }
}
```

- `Key` and `Entry` are private nested records. `Entry.as(source)` makes a `Resolution` with the stored code and records and the given source.

### `jdk/LabResolverProvider.java`

```java
package io.plainround.seatwave.dns.jdk;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.time.Duration;
import java.util.Optional;

/**
 * Plugs the lab's DNS server into the JDK's own name lookup ({@code InetAddress}), through the
 * resolver service-provider interface of JDK 18 and later (JEP 418). The JDK keeps its own cache on
 * top of any resolver, so a program that calls {@code InetAddress.getByName("seatwave.example")}
 * shows exactly what the JVM's cache does with a DNS TTL.
 *
 * <p>The provider is off unless a program sets {@link #PORT_PROPERTY} before its first lookup; then
 * it answers names under {@code seatwave.example} from the lab server on 127.0.0.1 and refuses
 * every other name, so no lookup ever leaves the machine. When the property is not set, the JDK's
 * built-in resolver is used, which keeps tests and the other programs unaffected.
 */
public final class LabResolverProvider extends InetAddressResolverProvider {
```

- `InetAddressResolverProvider` is an abstract class of the JDK (package `java.net.spi`, Java 18). A subclass tells the JDK how to find an address for a name. `InetAddressResolver` is what the provider hands out.
- The Javadoc says the important thing: the provider is **off** unless a program sets a system property before its first lookup. When off, the JDK's built-in resolver is used, so tests and the other commands are not affected.

```java
  /** The system property that holds the lab server's UDP port. */
  public static final String PORT_PROPERTY = "seatwave.lab.dns.port";

  /** The system property that holds the timeout of each query, in milliseconds. */
  public static final String TIMEOUT_PROPERTY = "seatwave.lab.dns.timeout.millis";

  /** Creates the provider; the JDK calls this through the service loader. */
  public LabResolverProvider() {
    super();
  }
```

- The two property names. A **system property** is a named text value inside the JVM, set with `System.setProperty` or with `-Dname=value` on the command line.
- The public no-argument constructor is required: the JDK's service loader creates the provider by calling it. `super()` calls the parent constructor.

```java
  @Override
  public InetAddressResolver get(Configuration configuration) {
    Optional<String> port = Optional.ofNullable(System.getProperty(PORT_PROPERTY));
    Optional<String> timeout = Optional.ofNullable(System.getProperty(TIMEOUT_PROPERTY));
    if (port.isEmpty() || timeout.isEmpty()) {
      return configuration.builtinResolver();
    }
    return new LabResolver(
        Integer.parseInt(port.get()), Duration.ofMillis(Long.parseLong(timeout.get())));
  }

  @Override
  public String name() {
    return "seatwave-lab";
  }
}
```

- `get(Configuration)` is called by the JDK when it first needs a resolver. If either property is missing, it returns `configuration.builtinResolver()`, the JDK's normal one. Otherwise it returns a `LabResolver` built from the two properties: `Integer.parseInt` reads the port and `Long.parseLong` the timeout in milliseconds.
- `name()` returns a short name for the provider: `seatwave-lab`.

### `jdk/LabResolver.java`

```java
package io.plainround.seatwave.dns.jdk;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The JDK resolver that asks the lab server. It answers only names under {@code seatwave.example};
 * any other name is refused with {@link UnknownHostException}, never passed to the system's
 * resolver. Thread-safe: the JDK may call it from any thread, so each lookup opens its own
 * short-lived client.
 */
final class LabResolver implements InetAddressResolver {
```

- The class is package-private (no `public`): only the provider uses it. It implements `InetAddressResolver`, which has two methods: `lookupByName` and `lookupByAddress`.
- The Javadoc says it answers only names under `seatwave.example`; any other name is refused with `UnknownHostException`, never passed to the system's resolver. That is how the lab guarantees that no lookup leaves the machine.

```java
  private static final DomainName LAB_ZONE = new DomainName(List.of("seatwave", "example"));

  private final int port;
  private final Duration timeout;

  LabResolver(int port, Duration timeout) {
    this.port = port;
    this.timeout = timeout;
  }
```

- `LAB_ZONE` is the name `seatwave.example.`. The constructor keeps the port and the timeout.

```java
  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    DomainName name =
        switch (DomainName.parse(host)) {
          case Result.Success<DomainName, String>(DomainName parsed) -> parsed;
          case Result.Error<DomainName, String>(String problem) ->
              throw new UnknownHostException(problem);
        };
    if (!name.isAtOrBelow(LAB_ZONE)) {
      throw new UnknownHostException("the lab resolver answers only names under " + LAB_ZONE);
    }
    int characteristics = lookupPolicy.characteristics();
    List<RecordType> order = new ArrayList<>();
    if ((characteristics & LookupPolicy.IPV4) != 0) {
      order.add(RecordType.A);
    }
    if ((characteristics & LookupPolicy.IPV6) != 0) {
      order.add(
          (characteristics & LookupPolicy.IPV6_FIRST) != 0 ? 0 : order.size(), RecordType.AAAA);
    }
    List<InetAddress> addresses = new ArrayList<>();
    try (DnsClient client = new DnsClient(port, timeout)) {
      for (RecordType type : order) {
        addresses.addAll(addressesOf(client.ask(Question.of(name, type)), type, host));
      }
    }
    if (addresses.isEmpty()) {
      throw new UnknownHostException(host + ": no address in the lab zone");
    }
    return addresses.stream();
  }
```

- `lookupByName(host, lookupPolicy)` is called by the JDK whenever a program calls `InetAddress.getByName` or `getAllByName`. It returns a stream of addresses or throws `UnknownHostException`.
- The name is parsed. A `switch` expression gives the name on success; on error, the arm is `throw new UnknownHostException(problem)`. A `switch` arm may throw instead of giving a value.
- A name outside `seatwave.example` is refused.
- `lookupPolicy.characteristics()` is a set of bits that says which families the program wants: `IPV4`, `IPV6`, and the order. The code builds a list of record types to ask: A if IPv4 is wanted, AAAA if IPv6 is wanted, inserted at the front when `IPV6_FIRST` is set (`list.add(index, element)`).
- It opens a `DnsClient` in a `try (...)` block so it is closed afterwards. Each lookup opens its own short-lived client, because the JDK may call the resolver from any thread and a `DnsClient` is not thread-safe. For each type it asks the question and collects the addresses.
- If no address was found, it throws `UnknownHostException`. Otherwise it returns the addresses as a stream.

```java
  @Override
  public String lookupByAddress(byte[] address) throws UnknownHostException {
    throw new UnknownHostException("the lab resolver does not answer reverse lookups");
  }
```

- `lookupByAddress` is the reverse lookup (address to name). The lab does not answer them.

```java
  private static List<InetAddress> addressesOf(
      Result<Message, LookupProblem> response, RecordType type, String host)
      throws UnknownHostException {
    return switch (response) {
      case Result.Success<Message, LookupProblem>(Message message) ->
          message.answers().stream()
              .filter(record -> record.type() == type)
              .map(record -> ((RecordData.Address) record.data()).address())
              .toList();
      case Result.Error<Message, LookupProblem>(LookupProblem problem) ->
          throw new UnknownHostException(host + ": " + problem.describe());
    };
  }
}
```

- `addressesOf` turns one `Result` into addresses. On success: from the answer records, keep those of the type asked for and take the address out of each. On error: `UnknownHostException` with the problem text. `throws UnknownHostException` is declared because it is a checked exception.

### `jdk/JdkLookupCli.java`

```java
package io.plainround.seatwave.dns.jdk;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.DnsServer;
import io.plainround.seatwave.dns.zone.Zone;
import io.plainround.seatwave.dns.zone.ZoneLoader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.Security;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Command-line entry point that measures the JDK's own name lookup, {@code InetAddress.getByName},
 * against the lab server through {@link LabResolverProvider}: the first lookup, repeated lookups
 * served by the JVM's cache, and the same for a name that does not exist. It also counts how many
 * queries reached the server, which shows the cache at work. Exit status 2 means bad usage or
 * settings; 1 means the run failed.
 */
public final class JdkLookupCli {
```

- The command that measures the JDK's lookup. It starts a lab server in the same process, sets the properties, and calls `InetAddress.getByName` 1,000 times for a name that exists and 1,000 times for one that does not. It also counts the queries that reached the server.

```java
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_FAILED = 1;
  private static final int HIGHEST_PORT = 65_535;
  private static final int MAX_LOOKUPS = 100_000;
  private static final int MAX_SECONDS = 600;
  private static final int MAX_TIMEOUT_MILLIS = 10_000;
  private static final double NANOS_PER_MICRO = 1_000;
  // The names looked up here are not weighted, so the seed changes no answer; it only has to be
  // fixed.
  private static final int SEED = 1;

  private JdkLookupCli() {}
```

- The constants: exit codes (2 for bad usage, 1 for a failure), limits for the settings, and `NANOS_PER_MICRO` (1,000) to convert nanoseconds to microseconds. `SEED` is fixed, because the names looked up are not weighted.

```java
  /**
   * Runs the measurement.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: jdkLookup <jdk-lookup.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
```

- `main` needs exactly one argument.

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      int port = reader.intBetween("server.port", DnsServer.LOWEST_UNPRIVILEGED_PORT, HIGHEST_PORT);
      int cacheSeconds = reader.intBetween("jvm.cache.ttl.seconds", 0, MAX_SECONDS);
      int negativeSeconds = reader.intBetween("jvm.cache.negative.ttl.seconds", 0, MAX_SECONDS);
      int lookups = reader.intBetween("lookup.count", 1, MAX_LOOKUPS);
      int timeoutMillis = reader.intBetween("lookup.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
      String name = reader.text("lookup.name");
      String missing = reader.text("lookup.missing.name");
      Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
```

- It reads the settings with the `SettingsReader`: the port, the JVM cache times, the number of lookups, the timeout, the two names, and the zone (via `ZoneLoader`). Each `intBetween(key, minimum, maximum)` records a problem for a missing or bad key and returns a harmless value.

```java
      List<String> problems = new ArrayList<>(reader.problems());
      if (zone instanceof Result.Error<Zone, List<String>>(List<String> zoneProblems)) {
        problems.addAll(zoneProblems);
      }
      if (!problems.isEmpty()
          || !(zone instanceof Result.Success<Zone, List<String>>(Zone loaded))) {
        problems.forEach(problem -> System.err.println("invalid: " + problem));
        System.exit(EXIT_BAD_USAGE);
        return;
      }
```

- The problems of the reader and the zone are gathered. If there is any problem (or the zone did not load), each is printed with the prefix `invalid:` and the program exits with status 2. The `|| !(zone instanceof Result.Success<...>(Zone loaded))` part both tests the zone and, when it passes, makes `loaded` available below.

```java
      // Set before the first lookup: the JVM reads its cache policy and loads providers once.
      Security.setProperty("networkaddress.cache.ttl", Integer.toString(cacheSeconds));
      Security.setProperty("networkaddress.cache.negative.ttl", Integer.toString(negativeSeconds));
      System.setProperty(LabResolverProvider.PORT_PROPERTY, Integer.toString(port));
      System.setProperty(LabResolverProvider.TIMEOUT_PROPERTY, Integer.toString(timeoutMillis));
      try (DnsServer server =
          DnsServer.start(
              port, new ZoneStore(loaded, Clock.systemUTC()), new SplittableRandom(SEED))) {
```

- The comment gives the reason for the order: the JVM reads its cache policy and loads the provider **once**, at the first lookup, so everything must be set before it.
- `Security.setProperty("networkaddress.cache.ttl", ...)` sets the JDK's cache time for successful lookups, in seconds, and `networkaddress.cache.negative.ttl` the time for failed ones. These are **security properties** (the file `java.security`), not system properties. The two system properties give the provider its port and timeout.
- The `try (DnsServer server = DnsServer.start(...))` starts the lab server on the chosen port and closes it at the end.

```java
        System.out.printf(
            Locale.ROOT,
            "Java %s on %s %s; InetAddress.getByName through the lab resolver to 127.0.0.1:%d;"
                + " networkaddress.cache.ttl=%d, networkaddress.cache.negative.ttl=%d%n",
            Runtime.version(),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            port,
            cacheSeconds,
            negativeSeconds);
        System.out.printf(
            Locale.ROOT,
            "%-28s %-14s %8s %12s %12s %15s%n",
            "lookup",
            "result",
            "lookups",
            "first us",
            "median us",
            "server queries");
```

- It prints a header: the Java version (`Runtime.version()`), the operating system name and architecture, the port and the two cache times. Then the column titles. `%-28s` left-aligns a text in 28 characters; `%8s` right-aligns in 8; `%12s`, `%15s` likewise.

```java
        System.out.print(measure(server, name, lookups));
        System.out.print(measure(server, missing, lookups));
      }
    } catch (IOException failed) {
      System.err.println("the lookup run failed: " + failed.getMessage());
      System.exit(EXIT_FAILED);
    }
  }
```

- The two measurements are printed. A failed run is reported on the error stream and ends with status 1.

```java
  private static String measure(DnsServer server, String name, int lookups) {
    long queriesBefore = server.answered();
    long[] nanos = new long[lookups];
    String result = "";
    for (int lookupIndex = 0; lookupIndex < lookups; lookupIndex++) {
      long started = System.nanoTime();
      result = lookUp(name);
      nanos[lookupIndex] = System.nanoTime() - started;
    }
    long first = nanos[0];
    long[] repeated = Arrays.copyOfRange(nanos, 1, nanos.length);
    Arrays.sort(repeated);
    double median = repeated.length == 0 ? 0 : repeated[repeated.length / 2] / NANOS_PER_MICRO;
    return String.format(
        Locale.ROOT,
        "%-28s %-14s %8d %12.1f %12.1f %15d%n",
        name,
        result,
        lookups,
        first / NANOS_PER_MICRO,
        median,
        server.answered() - queriesBefore);
  }
```

- `measure` remembers how many queries the server had answered. It calls `lookUp` `lookups` times and records the time of each with `System.nanoTime()`.
- `first` is the first lookup: it includes loading the provider and the cost of the first real query. The remaining times are copied (`Arrays.copyOfRange(nanos, 1, nanos.length)`) and sorted, and the **median** is the middle one: half the lookups were faster. Dividing by `NANOS_PER_MICRO` (a `double`) gives microseconds with decimals.
- The last number is the difference in `server.answered()`: how many queries reached the server during these lookups.
- `String.format(Locale.ROOT, ...)` builds the row: `%12.1f` is a decimal number with one digit after the point in 12 characters.

```java
  private static String lookUp(String name) {
    try {
      return InetAddress.getByName(name).getHostAddress();
    } catch (UnknownHostException noSuchName) {
      // A name that does not exist is an expected result here, and the JVM caches it too.
      return "UnknownHost";
    }
  }
}
```

- `lookUp` calls `InetAddress.getByName(name).getHostAddress()`. An `UnknownHostException` for a name that does not exist is an expected result here (the JVM caches it too), so it returns the text `UnknownHost`.

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only.

**1. Run the JDK's own lookup against the lab server.** The command starts its own server on port 5356 and stops it again.

```text
./gradlew -q jdkLookup --args='config/jdk-lookup.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; InetAddress.getByName through the lab resolver to 127.0.0.1:5356; networkaddress.cache.ttl=30, networkaddress.cache.negative.ttl=10
lookup                       result          lookups     first us    median us  server queries
seatwave.example             192.0.2.10         1000      15722.8          1.1               2
nowhere.seatwave.example     UnknownHost        1000       1368.3          3.4               2
```

Read the last column: 1,000 lookups cost 2 queries each (one A, one AAAA). The other 999 came from the JVM's cache. The times differ on every run; the `2` does not.

**2. Run the simulated experiment**, which uses `CachingResolver` with the `InMemoryUpstream` and a `SimulatedClock`:

```text
./gradlew -q ttlExperiment --args='config/ttl-experiment.properties'
```

The table has one row per arm, for example `one-hour-lowered   3600   60   0%   8.24   0.9176   30   57   59   59   120   0.0%`: with a one-hour TTL lowered to 60 seconds, the server sees 8.24 queries per second and the caches have a hit ratio of 0.9176; half of the resolvers give the new address 30 seconds after the switch.

**3. Ask a closed port.** A throw-away Java program in a scratch folder, run with `java Probe.java` on the compiled classes, built a `DnsClient` for a port that nobody listens on and a second one for a socket that never answers:

```text
closed port: Error[error=Unreachable[detail=null]]
silent server: Error[error=TimedOut[waited=PT0.3S]] after ~304 ms
```

The first shows `PortUnreachableException` turned into `Unreachable` (the system's message was empty, which Java prints as `null`). The second shows that a lost packet costs exactly the timeout, never more.

## Mistakes people make

- **Waiting without a timeout.** A UDP packet can be lost, and `receive` then blocks forever. `DnsClient` sets a timeout on every receive and counts the total.
- **Accepting any packet that arrives.** `DnsClient` checks the identifier and the question; anything else is ignored.
- **Reading the clock inside the cache.** `CachingResolver` takes a `Clock`, so a test can move time.
- **Setting the JVM cache properties too late.** They are read at the first lookup. The commands set them first.
- **Believing a short DNS TTL changes a Java program's behavior.** The JVM's own cache keeps an answer for `networkaddress.cache.ttl` seconds whatever the record's TTL says. The live run shows the `jvm` client moving to the new address only when its 30 seconds ran out.
- **Letting a custom resolver reach the real network.** `LabResolver` refuses every name outside `seatwave.example` and never calls the built-in resolver.
- **Caching a REFUSED or SERVFAIL as if it were a fact.** `store` caches only NOERROR and NXDOMAIN.

## Check yourself

1. What makes an entry a hit in `CachingResolver`? (An entry exists and the clock is strictly before its expiry time.)
2. Why does the resolver hold a CNAME chain for the smallest TTL in it? (Any record in the chain may change first; a cache must not keep any part longer than its TTL.)
3. What does `connect` do on a UDP socket? (It fixes the remote address: only that address is accepted, and a closed port shows up as `PortUnreachableException`. No connection is opened.)
4. Why is the provider off unless a system property is set? (So tests and the other commands use the JDK's normal resolver.)
5. Why were there only 2 server queries for 1,000 lookups? (The JVM caches successful lookups for `networkaddress.cache.ttl` seconds, and failed ones for `networkaddress.cache.negative.ttl`; the first lookup of each name asks A and AAAA.)

## Further reading

- RFC 1035, section 4.2 (transport: UDP and TCP): https://www.rfc-editor.org/rfc/rfc1035
- RFC 2308, Negative Caching of DNS Queries: https://www.rfc-editor.org/rfc/rfc2308
- RFC 5452, Measures for Making DNS More Resilient against Forged Answers: https://www.rfc-editor.org/rfc/rfc5452
- RFC 8767, Serving Stale Data to Improve DNS Resiliency: https://www.rfc-editor.org/rfc/rfc8767
- RFC 9520, Negative Caching of DNS Resolution Failures: https://www.rfc-editor.org/rfc/rfc9520
- JEP 418, Internet-Address Resolution SPI: https://openjdk.org/jeps/418
- The Java SE 25 API, `java.net.spi.InetAddressResolverProvider`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/spi/InetAddressResolverProvider.html
- The Java SE 25 API, `java.net.InetAddress` (the `networkaddress.cache.ttl` properties): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
