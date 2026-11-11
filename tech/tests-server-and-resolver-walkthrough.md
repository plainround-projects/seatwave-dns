# Tests of the `server` and `resolver` packages, file by file

## What it is

This file walks through five test classes: `ResponderTest` (8 tests), `DnsServerTest` (3) and `ServeSettingsTest` (3) in the `server` package, and `CachingResolverTest` (8) and `DnsClientTest` (5) in the `resolver` package. That is 27 of the 107 tests. The other 80 are in `tests-message-and-zone-walkthrough.md` (43) and `tests-other-packages-walkthrough.md` (37).

An **automated test** runs a piece of the program and checks the result (`junit.md` and `assertj.md` explain the tools). Two kinds appear. A **unit test** checks one piece with no network (`ResponderTest`, `ServeSettingsTest`, `CachingResolverTest`). An **integration test** starts real parts that talk over a real socket on the loopback address, `127.0.0.1`, the address that always means "this computer" (`DnsServerTest`, `DnsClientTest`).

## Why this repository uses it

The `server` package promises that one packet in gives one packet out, with the right flags and response code, and that a bad packet never stops the server. The `resolver` package promises that a caching resolver honors TTLs exactly as RFC 1035 and RFC 2308 say, can be told to behave badly (pinning old answers) so the experiment can measure it, and that the client side of UDP does not trust a reply without checking it.

## Where it appears

| Path | What it tests |
| --- | --- |
| `src/test/java/io/plainround/seatwave/dns/server/ResponderTest.java` | `Responder`: one query packet in, one response packet out. |
| `src/test/java/io/plainround/seatwave/dns/server/DnsServerTest.java` | `DnsServer`: a real UDP server on the loopback address. |
| `src/test/java/io/plainround/seatwave/dns/server/ServeSettingsTest.java` | `ServeSettings`: the settings of the `serve` task. |
| `src/test/java/io/plainround/seatwave/dns/resolver/CachingResolverTest.java` | `CachingResolver` and `CachePolicy`: TTL, negative caching, pinning, stale answers. |
| `src/test/java/io/plainround/seatwave/dns/resolver/DnsClientTest.java` | `DnsClient`: timeout, closed port, wrong id, unreadable reply. |

## How it works underneath

**UDP** is the simple way to send a packet: no connection, no promise of delivery. A **socket** is the operating system's handle for sending and receiving packets on a **port**, a number that tells the system which program should get an incoming packet. In Java a `DatagramSocket` is a UDP socket and a `DatagramPacket` is one packet. Port 0 means "choose any free port"; the program then asks which one it got.

For the integration tests, in order:

1. The test starts a `DnsServer` on port 0 (`DnsServer.ANY_FREE_PORT`) with the seatwave zone. It asks `server.port()` which port the system gave.
2. A `DnsClient` is created for that port, with a timeout.
3. The client sends a question and waits for the reply, or for the timeout.
4. The `try ( ... )` header closes both at the end, even when a check fails.

**How these tests stay deterministic.** A test of a network program with TTLs is deterministic when it gives the same result on every run and every machine. Five habits do it here.

- **An injected clock.** The caching resolver does not call `Instant.now()`. It receives a `Clock`, and the tests hand it a `SimulatedClock` that moves only when the test says `advanceTo(...)`. An hour of TTL passes in one microsecond, and the boundary between "last second of the TTL" and "TTL ended" is exact. No test sleeps.
- **A fixed seed.** Weighted answers draw from `new SplittableRandom(SEED)`, a generator that starts from a fixed number and gives the same sequence on every run.
- **Loopback and free ports.** No two tests share a port and no byte leaves the machine.
- **Counts, not durations.** `server.answered()`, `server.dropped()` and `resolver.serverQueries()` are exact whatever the speed of the machine.
- **Order by construction.** `DnsServerTest` needs the server to have handled two bad packets before it checks the counters. It does not sleep: the server has one thread, so the answer to a later good question can only arrive after the earlier packets were handled.

## Walkthrough of this repository's use

### `ResponderTest` (8 tests)

```java
package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.zone.TestZones;
import io.plainround.seatwave.dns.zone.Weights;
import io.plainround.seatwave.dns.zone.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ResponderTest {

  private static final int ID = 4242;
  private static final long SEED = 1;
  private static final int CHAOS_CLASS = 3;
  private static final int NOTIFY_OPCODE_FLAGS = 4 << 11;
  private static final int TXT_RECORDS_OVER_512_OCTETS = 20;
  private static final long SOA_MINIMUM = 300;
```

`Responder.respond(queryBytes, zone, random)` is a pure function: bytes in, optional bytes out. That is why it is tested with no socket. The constants: `CHAOS_CLASS = 3` (the DNS class CH, not IN), `NOTIFY_OPCODE_FLAGS = 4 << 11` (the opcode field sits in bits 11 to 14 of the flags, and opcode 4 is NOTIFY; `<<` shifts a number's bits to the left), 20 TXT records for the long answer, and the SOA minimum of 300 seconds.

```java
  @Test
  void answersAuthoritativelyAndCopiesRecursionDesired() {
    Message query =
        new Message(
            ID,
            Message.RECURSION_DESIRED_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(),
            List.of(),
            List.of());

    Message response = respond(MessageCodec.encode(query), TestZones.seatwave());

    assertThat(response.id()).isEqualTo(ID);
    assertThat(response.isResponse()).isTrue();
    assertThat(response.isAuthoritative()).isTrue();
    assertThat(response.flags() & Message.RECURSION_DESIRED_FLAG).isNotZero();
    assertThat(response.answers())
        .extracting(ResourceRecord::toString)
        .containsExactly("seatwave.example. 3600 IN A 192.0.2.10");
  }
```

**Proves:** the response is authoritative (`aa`), carries the query's id, copies the **recursion desired** flag from the query, and answers with the one A record. The flag test `response.flags() & Message.RECURSION_DESIRED_FLAG` uses `&`, the bit-AND: it keeps only that one bit, and `isNotZero()` says the bit was set.

```java
  @Test
  void saysNxdomainWithTheSoaAtItsNegativeTtl() {
    Message response =
        respond(query("nowhere.seatwave.example", RecordType.A), TestZones.seatwave());

    assertThat(response.responseCodeValue()).isEqualTo(ResponseCode.NAME_ERROR.code());
    assertThat(response.authority())
        .singleElement()
        .satisfies(soa -> assertThat(soa.ttlSeconds()).isEqualTo(SOA_MINIMUM));
  }
```

**Proves:** for a name that does not exist, the response code is NXDOMAIN (`NAME_ERROR`) and the authority section holds the SOA with the **negative TTL**, 300 seconds, which is the SOA's last field. Caches use that number to remember "no such name" (RFC 2308). **Why it matters:** the caching resolver test below depends on it.

```java
  @Test
  void refersWithoutTheAuthoritativeFlag() {
    Message response = respond(query("seatwave.example", RecordType.A), TestZones.example());

    assertThat(response.isAuthoritative()).isFalse();
    assertThat(response.authority())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.NS);
    assertThat(response.additional())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.A);
  }
```

**Proves:** a server that is not authoritative for a name refers instead of answering. The `example.` zone gets a question about `seatwave.example`: the response has no `aa` flag, the authority section holds the NS record and the additional section holds the A record (glue).

```java
  @Test
  void saysNoDataWithTheSoa() {
    Message response =
        respond(query("book.seatwave.example", RecordType.TXT), TestZones.seatwave());

    assertThat(response.responseCodeValue()).isEqualTo(ResponseCode.NO_ERROR.code());
    assertThat(response.answers()).isEmpty();
    assertThat(response.authority())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.SOA);
  }
```

**Proves:** "no data": a TXT question for `book.seatwave.example` (which has only A records) gives NOERROR, no answers, and the SOA in the authority section.

```java
  @Test
  void refusesNamesOutsideTheZoneAndOtherClasses() {
    Message outside = respond(query("example.com", RecordType.A), TestZones.seatwave());
    Message chaos =
        respond(
            MessageCodec.encode(
                Message.query(
                    ID, new Question(TestNames.name("seatwave.example"), 1, CHAOS_CLASS))),
            TestZones.seatwave());

    assertThat(outside.responseCodeValue()).isEqualTo(ResponseCode.REFUSED.code());
    assertThat(chaos.responseCodeValue()).isEqualTo(ResponseCode.REFUSED.code());
  }
```

**Proves:** a name outside the zone (`example.com`) is REFUSED, and so is a question in another **class** (CH, "chaos"). A server must not pretend to know what it does not serve.

```java
  @Test
  void answersFormerrToUnreadableQueriesAndNotimpToOtherOpcodes() {
    byte[] unreadable = query("seatwave.example", RecordType.A);
    unreadable[MessageCodec.HEADER_OCTETS] = (byte) 0xC0;
    Message notify =
        new Message(
            ID,
            NOTIFY_OPCODE_FLAGS,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.SOA)),
            List.of(),
            List.of(),
            List.of());
    Message twoQuestions =
        new Message(
            ID,
            0,
            List.of(
                Question.of(TestNames.name("seatwave.example"), RecordType.A),
                Question.of(TestNames.name("seatwave.example"), RecordType.AAAA)),
            List.of(),
            List.of(),
            List.of());

    assertThat(respond(unreadable, TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.FORMAT_ERROR.code());
    assertThat(respond(MessageCodec.encode(notify), TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.NOT_IMPLEMENTED.code());
    assertThat(respond(MessageCodec.encode(twoQuestions), TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.FORMAT_ERROR.code());
  }
```

**Proves:** three more refusals, each with the right code. (1) A query whose first name octet is overwritten with `0xC0` (a compression pointer) cannot be decoded: the server answers FORMERR (`FORMAT_ERROR`), not silence. The hostile bytes are made by changing one octet of a good packet at offset `MessageCodec.HEADER_OCTETS` (12), the start of the question. (2) A NOTIFY message (opcode 4) is NOTIMP (`NOT_IMPLEMENTED`): the lab does not do zone transfers. (3) A query with two questions is FORMERR: the lab supports one question per packet.

```java
  @Test
  void neverAnswersAResponseOrAPacketShorterThanAHeader() {
    Message response =
        new Message(
            ID,
            Message.RESPONSE_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(),
            List.of(),
            List.of());

    assertThat(Responder.respond(MessageCodec.encode(response), TestZones.seatwave(), random()))
        .isEmpty();
    assertThat(Responder.respond(new byte[] {1, 2}, TestZones.seatwave(), random())).isEmpty();
  }
```

**Proves:** the server never answers a response (a packet with the QR flag set), and never answers a packet shorter than a header (two octets). `Optional.empty()` is the answer. **Why it matters:** if a server answered responses, two servers could answer each other for ever, and an attacker could use the server to bounce packets at a victim.

```java
  @Test
  void cutsAnAnswerLongerThan512OctetsToItsHeaderAndQuestion() {
    Zone zone = TestZones.seatwave();
    List<ResourceRecord> records = new ArrayList<>(zone.records());
    for (int index = 0; index < TXT_RECORDS_OVER_512_OCTETS; index++) {
      records.add(
          new ResourceRecord(
              TestNames.name("big.seatwave.example"),
              RecordType.TXT,
              60,
              new RecordData.Text(List.of("padding-" + index + "-".repeat(30)))));
    }
    Zone big = zone.with(records, Weights.NONE).discardError().orElseThrow();

    Message response = respond(query("big.seatwave.example", RecordType.TXT), big);

    assertThat(response.flags() & Message.TRUNCATED_FLAG).isNotZero();
    assertThat(response.answers()).isEmpty();
    assertThat(response.questions()).hasSize(1);
  }
```

**Proves:** the 512-octet limit of UDP. The test adds 20 TXT records of about 40 octets each under `big.seatwave.example`, using `zone.with(records, Weights.NONE)` to get a new zone. The answer would be longer than 512 octets, so the response is cut to the header and the question and carries the truncated flag (`TRUNCATED_FLAG`), which tells a client to ask again over TCP. The README lists this among the simplifications: the lab has no TCP.

```java
  private static byte[] query(String name, RecordType type) {
    return MessageCodec.encode(Message.query(ID, Question.of(TestNames.name(name), type)));
  }

  private static Message respond(byte[] query, Zone zone) {
    byte[] bytes = Responder.respond(query, zone, random()).orElseThrow();
    return MessageCodec.decode(bytes).discardError().orElseThrow();
  }

  private static SplittableRandom random() {
    return new SplittableRandom(SEED);
  }
```

Three helpers. `query(name, type)` encodes a normal question. `respond(queryBytes, zone)` runs the responder with a seeded generator and decodes the reply, failing loudly if there is none (`orElseThrow`). `random()` makes a new generator from `SEED = 1` for each call.

### `DnsServerTest` (3 tests)

```java
package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DnsServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final int DNS_PORT = 53;
  private static final int OVERSIZED = 600;
```

`TIMEOUT` is 2 seconds, the longest the client waits. `DNS_PORT = 53` is the real DNS port, used to test the refusal. `OVERSIZED = 600` is more than the 512 octets a DNS packet over UDP may carry.

```java
  @Test
  void answersAQueryOverUdpOnTheLoopbackAddress() throws Exception {
    try (DnsServer server = start();
        DnsClient client = new DnsClient(server.port(), TIMEOUT)) {
      Message response =
          client
              .ask(Question.of(TestNames.name("www.seatwave.example"), RecordType.A))
              .discardError()
              .orElseThrow();

      assertThat(response.answers())
          .extracting(ResourceRecord::type)
          .containsExactly(RecordType.CNAME, RecordType.A);
      assertThat(server.answered()).isEqualTo(1);
    }
  }
```

**Proves:** the whole path over a real socket. A server starts on a free port, a client asks for the A record of `www.seatwave.example`, and the answer is a CNAME followed by the A record. The server counted exactly one answered packet. **Why it matters:** this is the only test that covers bind, receive, decode, respond, encode and send together.

```java
  @Test
  void dropsResponsesAndOversizedPacketsAndKeepsServing() throws Exception {
    try (DnsServer server = start();
        DnsClient client = new DnsClient(server.port(), TIMEOUT);
        DatagramSocket raw = new DatagramSocket()) {
      byte[] response =
          MessageCodec.encode(
              new Message(1, Message.RESPONSE_FLAG, List.of(), List.of(), List.of(), List.of()));
      send(raw, response, server.port());
      send(raw, new byte[OVERSIZED], server.port());

      // The server has one thread, so this answer arrives only after both bad packets were handled.
      client
          .ask(Question.of(TestNames.name("seatwave.example"), RecordType.A))
          .discardError()
          .orElseThrow();

      assertThat(server.dropped()).isEqualTo(2);
      assertThat(server.answered()).isEqualTo(1);
    }
  }
```

**Proves:** the server drops what it must not answer and keeps serving. A raw `DatagramSocket` sends two bad packets: a well-formed **response** and 600 zero octets. Then the real client asks a good question. The comment gives the reason this needs no sleep: the server has one thread, so the good answer can only arrive after the two bad packets were handled. The counters then show `dropped() == 2` and `answered() == 1`. **Why it matters:** this is how a malformed packet is attacked at the network level, not only in the codec: send junk and show the next legitimate client is still served.

```java
  @Test
  void refusesAPrivilegedPort() {
    assertThatThrownBy(() -> DnsServer.start(DNS_PORT, store(), new SplittableRandom(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the port must be from 1024 to 65535, but was 53");
  }
```

**Proves:** the server refuses port 53 with a clear message: `the port must be from 1024 to 65535, but was 53`. The lab never needs administrator rights, and it can never open the real DNS port by mistake.

```java
  private static void send(DatagramSocket socket, byte[] bytes, int port) throws Exception {
    socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
  }

  private static DnsServer start() throws Exception {
    return DnsServer.start(DnsServer.ANY_FREE_PORT, store(), new SplittableRandom(1));
  }

  private static ZoneStore store() {
    return new ZoneStore(TestZones.seatwave(), Clock.systemUTC());
  }
```

Helpers. `send` puts bytes into a UDP packet addressed to the loopback address and the port. `start()` starts a server on a free port with a seeded generator. `store()` wraps the seatwave zone in a `ZoneStore`, the object through which a served zone may change, with the real UTC clock (no test here changes the zone).

### `ServeSettingsTest` (3 tests)

```java
package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ServeSettingsTest {

  private static final int ROOT_PORT = 5301;
  private static final int SEATWAVE_PORT = 5303;
  private static final Duration FIVE_MINUTES = Duration.ofSeconds(300);

  @Test
  void readsTheThreeLabListeners() throws Exception {
    ServeSettings settings = read("config/serve.properties").discardError().orElseThrow();

    assertThat(settings.listeners())
        .extracting(ServeSettings.Listener::name)
        .containsExactly("root", "tld", "seatwave");
    assertThat(settings.listeners().getFirst().port()).isEqualTo(ROOT_PORT);
    assertThat(settings.listeners().getLast().port()).isEqualTo(SEATWAVE_PORT);
    assertThat(settings.serveFor()).isEqualTo(FIVE_MINUTES);
  }
```

**Proves:** `config/serve.properties` gives three listeners named `root`, `tld` and `seatwave`, in that order, on ports 5301 and 5303 for the first and the last, and runs for 300 seconds (`Duration.ofSeconds(300)`). `extracting(ServeSettings.Listener::name)` turns each listener into its name before the list is checked.

```java
  @Test
  void refusesTheBrokenZoneWithItsFourMistakes() throws Exception {
    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>) read("config/serve-broken.properties"))
            .error();

    assertThat(problems).hasSize(4).allMatch(problem -> problem.startsWith("seatwave: "));
  }
```

**Proves:** the broken settings file (`config/serve-broken.properties`) yields four problems, each prefixed with the listener name `seatwave: `. They are the four mistakes in the README's "A zone with four mistakes".

```java
  @Test
  void refusesAPrivilegedPortAndMissingKeys() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("listener.root.port", "53");
    properties.remove("listener.tld.zone.file");

    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>)
                ServeSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems)
        .containsExactly(
            "listener.root.port must be from 1024 to 65535, but was 53",
            "listener.tld.zone.file is missing");
  }
```

**Proves:** two mistakes in one settings file are both reported in one run, in order: port 53 is below 1024 (`listener.root.port must be from 1024 to 65535, but was 53`) and a removed key is missing (`listener.tld.zone.file is missing`). The test edits a loaded `Properties` object instead of a file, so no file is written.

```java
  @Test
  void refusesTwoListenersOnOnePort() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/serve.properties"));
    properties.setProperty("listener.tld.port", String.valueOf(ROOT_PORT));

    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>)
                ServeSettings.from(new SettingsReader(properties)))
            .error();

    assertThat(problems).containsExactly("two listeners share a port");
  }
```

**Proves:** two listeners on one port are a settings problem, reported as one line like every other mistake, and not a crash. The test loads the good settings, gives the `tld` listener the root listener's port (`ROOT_PORT`, 5301), and expects exactly one problem: `two listeners share a port`. It matters because two sockets cannot share a UDP port, and a person who mistypes a port should read one clear line.

```java
  private static Result<ServeSettings, List<String>> read(String file) throws Exception {
    return ServeSettings.from(new SettingsReader(SettingsReader.load(Path.of(file))));
  }
```

The helper reads a settings file and returns the result, success or a list of problem texts.

### `CachingResolverTest` (8 tests)

```java
package io.plainround.seatwave.dns.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.experiment.SimulatedClock;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.zone.TestZones;
import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CachingResolverTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final Instant START = Instant.EPOCH;
  private static final long ONE_HOUR = 3_600;
  private static final double TOLERANCE = 1e-9;
  private static final long NEGATIVE_TTL = 300;
  private static final Duration DAY = Duration.ofDays(1);
  private static final Duration PIN = Duration.ofHours(2);
  private static final Duration STALE = Duration.ofMinutes(30);
  private static final CachePolicy HONEST = new CachePolicy(Duration.ZERO, DAY, DAY, Duration.ZERO);

  private final SimulatedClock clock = new SimulatedClock(START);
  private final InMemoryUpstream upstream =
      new InMemoryUpstream(new ZoneStore(TestZones.seatwave(), clock), new SplittableRandom(1));
```

A **caching resolver** stands for the recursive resolver of an internet provider: it asks a name server once and keeps the answer for the TTL. This one has a `CachePolicy` of four durations: the shortest time it keeps an answer (`minimumHold`), the longest (`maximumHold`), the longest it keeps a negative answer, and how long it may serve an expired answer when the server cannot be reached (`serveStaleFor`). `HONEST` is `(0, 1 day, 1 day, 0)`: it obeys the TTL as given.

The two instance fields give each test a fresh `SimulatedClock` starting at `Instant.EPOCH` (1 January 1970) and a fresh `InMemoryUpstream`, a stand-in server that asks the `ZoneStore` directly with no socket (JUnit builds a new test object for every test, `junit.md`). The clock is shared by the store and the resolver, so "time" is one thing.

```java
  @Test
  void answersFromTheCacheUntilTheTtlEnds() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);

    Resolution first = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR - 1));
    Resolution cached = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR));
    Resolution expired = resolver.resolve(APEX, RecordType.A);

    assertThat(first.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(cached.source()).isEqualTo(Resolution.Source.CACHE);
    assertThat(expired.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(cached.addresses()).containsExactly(TestNames.address("192.0.2.10"));
    assertThat(resolver.serverQueries()).isEqualTo(2);
    assertThat(resolver.cacheHitRatio()).isCloseTo(1.0 / 3, offset(TOLERANCE));
  }
```

**Proves:** the TTL boundary, to the second. The zone's TTL is 3,600. The first lookup goes to the server (`SERVER`). One second before the TTL ends (3,599 s) the answer comes from the `CACHE`. At exactly 3,600 s the cache entry has expired and the server is asked again. Two server queries and a hit ratio of one third follow. **Determinism:** `clock.advanceTo(...)` moves time without waiting.

```java
  @Test
  void keepsAnAnswerLongerThanItsTtlWhenThePolicyPinsIt() {
    CachingResolver pinning =
        new CachingResolver(upstream, clock, new CachePolicy(PIN, DAY, DAY, Duration.ZERO));

    pinning.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR + 1));

    assertThat(pinning.resolve(APEX, RecordType.A).source()).isEqualTo(Resolution.Source.CACHE);
  }
```

**Proves:** **pinning**. A resolver whose `minimumHold` is two hours keeps a one-hour answer for two hours, so one hour and one second later it still answers from the cache. Some real software does this, and it is why lowering a TTL does not move every client at once (README, failure mode 4).

```java
  @Test
  void capsTheHoldAtTheMaximum() {
    CachingResolver capped =
        new CachingResolver(
            upstream,
            clock,
            new CachePolicy(Duration.ZERO, Duration.ofSeconds(10), DAY, Duration.ZERO));

    capped.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(10));

    assertThat(capped.resolve(APEX, RecordType.A).source()).isEqualTo(Resolution.Source.SERVER);
  }
```

**Proves:** `maximumHold`. A resolver that caps holds at 10 seconds asks the server again after 10 seconds even though the TTL is 3,600.

```java
  @Test
  void cachesANoForTheSoaMinimum() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);
    DomainName missing = TestNames.name("nowhere.seatwave.example");

    Resolution first = resolver.resolve(missing, RecordType.A);
    clock.advanceTo(START.plusSeconds(NEGATIVE_TTL - 1));
    Resolution cached = resolver.resolve(missing, RecordType.A);
    clock.advanceTo(START.plusSeconds(NEGATIVE_TTL));
    Resolution again = resolver.resolve(missing, RecordType.A);

    assertThat(first.code()).isEqualTo(ResponseCode.NAME_ERROR);
    assertThat(cached.source()).isEqualTo(Resolution.Source.CACHE);
    assertThat(again.source()).isEqualTo(Resolution.Source.SERVER);
  }
```

**Proves:** **negative caching** (RFC 2308). A lookup for a name that does not exist (`nowhere.seatwave.example`) returns NXDOMAIN, and the resolver remembers "no" for the SOA's minimum, 300 seconds: from the cache at 299 s, from the server at 300 s.

```java
  @Test
  void servesAStaleAnswerWhileTheServerIsDownAndOnlyForAWhile() {
    AtomicBoolean isDown = new AtomicBoolean();
    Upstream flaky =
        question ->
            isDown.get()
                ? Result.error(new LookupProblem.TimedOut(Duration.ofSeconds(1)))
                : upstream.ask(question);
    CachingResolver resolver =
        new CachingResolver(flaky, clock, new CachePolicy(Duration.ZERO, DAY, DAY, STALE));

    resolver.resolve(APEX, RecordType.A);
    isDown.set(true);
    clock.advanceTo(START.plusSeconds(ONE_HOUR + 1));
    Resolution stale = resolver.resolve(APEX, RecordType.A);
    clock.advanceTo(START.plusSeconds(ONE_HOUR).plus(STALE));
    Resolution failed = resolver.resolve(APEX, RecordType.A);

    assertThat(stale.source()).isEqualTo(Resolution.Source.STALE);
    assertThat(stale.addresses()).containsExactly(TestNames.address("192.0.2.10"));
    assertThat(failed.source()).isEqualTo(Resolution.Source.FAILED);
    assertThat(failed.code()).isEqualTo(ResponseCode.SERVER_FAILURE);
  }
```

**Proves:** **serving stale** (RFC 8767). The upstream here is a lambda (`question -> ...`) that fails with a timeout while `isDown` is true. The policy allows 30 minutes of stale service. The test looks up once, switches the server off, moves one second past the TTL and gets the old address marked `STALE`. At exactly 30 minutes after expiry the stale window is closed and the result is `FAILED` with SERVFAIL (`SERVER_FAILURE`). `AtomicBoolean` is a boolean that can be changed safely from several threads; here it is simply a switch the lambda can read.

```java
  @Test
  void doesNotCacheARefusal() {
    CachingResolver resolver = new CachingResolver(upstream, clock, HONEST);
    DomainName outside = TestNames.name("example.com");

    Resolution first = resolver.resolve(outside, RecordType.A);
    Resolution second = resolver.resolve(outside, RecordType.A);

    assertThat(first.code()).isEqualTo(ResponseCode.REFUSED);
    assertThat(second.source()).isEqualTo(Resolution.Source.SERVER);
    assertThat(upstream.queries()).isEqualTo(2);
  }
```

**Proves:** a REFUSED answer is not cached: the second lookup goes to the server again, and the upstream has seen two queries.

```java
  @Test
  void reportsAZeroHitRatioBeforeTheFirstLookup() {
    assertThat(new CachingResolver(upstream, clock, HONEST).cacheHitRatio()).isZero();
  }
```

**Proves:** before any lookup the hit ratio is 0, not a division by zero.

```java
  @Test
  void refusesANegativeOrInvertedPolicy() {
    assertThatThrownBy(() -> new CachePolicy(Duration.ofSeconds(-1), DAY, DAY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CachePolicy(DAY.plusSeconds(1), DAY, DAY, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a policy with a negative duration or a minimum above the maximum cannot be built.

### `DnsClientTest` (5 tests)

```java
package io.plainround.seatwave.dns.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DnsClientTest {

  private static final Duration SHORT = Duration.ofMillis(200);
  private static final Duration LONG = Duration.ofSeconds(2);
  private static final Question QUESTION =
      Question.of(TestNames.name("seatwave.example"), RecordType.A);
  private static final int BUFFER = 512;
```

The client side of UDP. `SHORT` is 200 ms (for the test that expects silence), `LONG` is 2 s, and `BUFFER = 512` is the largest classic DNS packet.

```java
  @Test
  void timesOutWhenTheServerNeverAnswers() throws Exception {
    try (DatagramSocket silent =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(silent.getLocalPort(), SHORT)) {
      assertThat(client.ask(QUESTION)).isEqualTo(Result.error(new LookupProblem.TimedOut(SHORT)));
    }
  }
```

**Proves:** if nobody answers, the client gives up after its timeout and returns `TimedOut(SHORT)` as a value, not as an exception. The "server" is a socket that is bound but never read, so the question arrives and nothing comes back.

```java
  @Test
  void reportsAClosedPortAsUnreachable() throws Exception {
    int closedPort;
    try (DatagramSocket probe =
        new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
      closedPort = probe.getLocalPort();
    }
    try (DnsClient client = new DnsClient(closedPort, LONG)) {
      assertThat(client.ask(QUESTION))
          .isInstanceOf(Result.Error.class)
          .extracting(result -> ((Result.Error<Message, LookupProblem>) result).error())
          .isInstanceOf(LookupProblem.Unreachable.class);
    }
  }
```

**Proves:** a closed port is reported as `Unreachable`. The test opens a socket only to learn a free port number and closes it at once (the `try` block ends), so nothing listens there. The operating system then answers a packet sent to that port with an error message, and the client turns it into `LookupProblem.Unreachable`. The check `isInstanceOf(Result.Error.class)` and then `extracting(...)` reads the error out of the result.

```java
  @Test
  void ignoresAResponseWithTheWrongIdentifierAndTakesTheRightOne() throws Exception {
    try (DatagramSocket fake =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(fake.getLocalPort(), LONG)) {
      Thread server =
          Thread.ofVirtual()
              .start(() -> {
                try {
                  DatagramPacket query = new DatagramPacket(new byte[BUFFER], BUFFER);
                  fake.receive(query);
                  Message parsed =
                      MessageCodec.decode(Arrays.copyOf(query.getData(), query.getLength()))
                          .discardError()
                          .orElseThrow();
                  reply(fake, query, (parsed.id() + 1) & 0xFFFF);
                  reply(fake, query, parsed.id());
                } catch (IOException failed) {
                  throw new UncheckedIOException(failed);
                }
              });

      Result<Message, LookupProblem> answer = client.ask(QUESTION);
      server.join(LONG);

      assertThat(answer).isInstanceOf(Result.Success.class);
    }
  }
```

**Proves:** the client ignores a reply with the wrong id and takes the right one. The test plays a fake server on a **virtual thread** (`Thread.ofVirtual().start(...)`, a very light thread that Java 21 and later provide). It receives the question, decodes it, then sends two replies: first with the id plus one (`& 0xFFFF` keeps it within 16 bits), then with the right id. The client must skip the first and return the second. `server.join(LONG)` waits for the fake server to finish. **Why it matters:** a UDP reply can come from anybody. Matching the id is the minimum defense against a forged answer. The id is random in real life, which is why it helps.

```java
  @Test
  void reportsAnUnreadableResponseAsMalformed() throws Exception {
    try (DatagramSocket fake =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(fake.getLocalPort(), LONG)) {
      Thread server =
          Thread.ofVirtual()
              .start(() -> {
                try {
                  DatagramPacket query = new DatagramPacket(new byte[BUFFER], BUFFER);
                  fake.receive(query);
                  fake.send(new DatagramPacket(new byte[] {1, 2, 3}, 3, query.getSocketAddress()));
                } catch (IOException failed) {
                  throw new UncheckedIOException(failed);
                }
              });

      Result<Message, LookupProblem> answer = client.ask(QUESTION);
      server.join(LONG);

      assertThat(((Result.Error<Message, LookupProblem>) answer).error().describe())
          .isEqualTo("unreadable response: the packet ends inside the header");
    }
  }
```

**Proves:** a reply the codec cannot read is reported as a problem and not as an exception. The fake server sends three octets, shorter than a header. The client's error text is `unreadable response: the packet ends inside the header`.

```java
  @Test
  void refusesAZeroTimeout() {
    assertThatThrownBy(() -> new DnsClient(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a timeout of zero is refused with `IllegalArgumentException`.

```java
  private static void reply(DatagramSocket socket, DatagramPacket query, int id)
      throws IOException {
    byte[] response =
        MessageCodec.encode(
            new Message(
                id, Message.RESPONSE_FLAG, List.of(QUESTION), List.of(), List.of(), List.of()));
    socket.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
  }
```

The helper `reply` builds a response message with the question and the given id, encodes it and sends it back to the address the query came from.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Run the two packages and count:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.dns.server.*' --tests 'io.plainround.seatwave.dns.resolver.*' | grep -c PASSED
```

   Output: `27`.

2. Run the time-boundary test:

```
./gradlew --offline cleanTest test --tests '*CachingResolverTest.answersFromTheCacheUntilTheTtlEnds'
```

   Output: `CachingResolverTest > answersFromTheCacheUntilTheTtlEnds() PASSED`.

3. Move the boundary by one second, in a scratch copy only. In `CachingResolverTest`, change `START.plusSeconds(ONE_HOUR - 1)` on line 42 to `START.plusSeconds(ONE_HOUR)`. Run the command of step 2. Output: `CachingResolverTest > answersFromTheCacheUntilTheTtlEnds() FAILED` with `AssertionFailedError at CachingResolverTest.java:48` and `expected: CACHE` / ` but was: SERVER`: at exactly 3,600 s the entry has expired. Undo the edit afterwards.

4. Look at the counters the server prints:

```
./gradlew -q serve --args='config/serve.properties'
```

   When it ends (after 300 seconds) it prints the three lines `root      answered N, dropped 0`, `tld       answered N, dropped 0` and `seatwave  answered N, dropped 0`; in my 90-second session with a few `dig` commands the numbers were 2, 1 and 20. These are the counters `DnsServerTest` checks in a tiny case.

## Mistakes people make

- **Calling `Instant.now()` inside the code under test.** Then a test of TTLs must really wait. This code takes a `Clock`.
- **Sleeping to wait for a server.** Slow and flaky. The tests wait for a reply with a timeout, or reason about order.
- **Trusting any reply.** A UDP reply must match the id of the question.
- **Hard-coding a port.** Another program may hold it. The tests use port 0.
- **Leaving a socket open.** The `try ( ... )` headers close every server, client and socket.

## Check yourself

1. *Why can `CachingResolverTest` test a one-hour TTL in a few milliseconds?* It gets a `SimulatedClock` that the test moves with `advanceTo`.
2. *What is pinning, and which test shows it?* A resolver that keeps an answer longer than its TTL; `keepsAnAnswerLongerThanItsTtlWhenThePolicyPinsIt`.
3. *Why does `DnsServerTest` not need a sleep before it reads the counters?* The server has one thread, so the later good answer proves the two bad packets were already handled.
4. *What does the client do with a reply that has the wrong id?* Ignores it and keeps waiting for the right one.
5. *What is the NXDOMAIN response's authority section for?* It carries the SOA, whose last field is the negative TTL that caches use.

## Further reading

- RFC 1035, section 4 (message format) and section 4.2.1 (UDP use): <https://www.rfc-editor.org/rfc/rfc1035>
- RFC 2308, negative caching of DNS queries: <https://www.rfc-editor.org/rfc/rfc2308>
- RFC 8767, serving stale data: <https://www.rfc-editor.org/rfc/rfc8767>
- `java.net.DatagramSocket` in Java 25: <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/DatagramSocket.html>
