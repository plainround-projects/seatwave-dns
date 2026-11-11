# Tests of the `message` and `zone` packages, file by file

## What it is

This file walks through the test classes of two packages. In `message`: `DomainNameTest` (6 tests), `ValuesTest` (7) and `MessageCodecTest` (12), plus the helper `TestNames`. In `zone`: `WeightsTest` (3), `ZoneFileTest` (3), `ZoneLoaderTest` (4) and `ZoneTest` (8), plus the helper `TestZones`. That is 43 of the 107 tests. The other 64 are in `tests-server-and-resolver-walkthrough.md` (27) and `tests-other-packages-walkthrough.md` (37).

An **automated test** runs a piece of the program and checks the result (`junit.md` and `assertj.md` explain the tools). All tests in this file are **unit tests**: they use no network. They check the two foundations of the lab: the codec that turns DNS messages into bytes and back, and the zone that decides what to answer.

## Why this repository uses it

Each package carries promises of the lesson. The `message` package promises that a hostile packet is turned into a typed error value and never into a crash or an endless loop: every incoming packet is checked at the boundary (lengths, counts, compression pointers, labels). The `zone` package promises that a zone file is read completely or refused with every mistake listed, that a lookup gives exactly the answer, referral, "no data", "no such name" or "not mine" that RFC 1034 describes, and that weighted names put an address first in proportion to its weight.

## Where it appears

| Path | What it tests |
| --- | --- |
| `src/test/java/io/plainround/seatwave/dns/message/TestNames.java` | Helper: builds names, addresses and records from text known to be valid. |
| `src/test/java/io/plainround/seatwave/dns/message/DomainNameTest.java` | `DomainName`: spelling, limits, place in the tree. |
| `src/test/java/io/plainround/seatwave/dns/message/ValuesTest.java` | `ResourceRecord`, `RecordData`, `Question`, `Message`, `RecordType`, `ResponseCode`: what each refuses and how each prints. |
| `src/test/java/io/plainround/seatwave/dns/message/MessageCodecTest.java` | `MessageCodec`: the round trip and every kind of malformed packet. |
| `src/test/java/io/plainround/seatwave/dns/zone/TestZones.java` | Helper: loads the project's own zone files. |
| `src/test/java/io/plainround/seatwave/dns/zone/WeightsTest.java` | `Weights`: the CoreDNS weights file format. |
| `src/test/java/io/plainround/seatwave/dns/zone/ZoneFileTest.java` | `ZoneFile`: the master-file format and its error messages. |
| `src/test/java/io/plainround/seatwave/dns/zone/ZoneLoaderTest.java` | `ZoneLoader`: files, origins, settings keys. |
| `src/test/java/io/plainround/seatwave/dns/zone/ZoneTest.java` | `Zone`: validation and lookup. |

## How it works underneath

A **DNS message** is a block of bytes. A byte is eight bits; the DNS standard calls it an **octet**. The layout, RFC 1035 section 4:

1. A **header** of exactly 12 octets: a 16-bit **id**, 16 bits of **flags** (response or query, authoritative, recursion wanted, the response code, the kind of request), and four 16-bit **counts**: how many questions, answers, authority records and additional records follow.
2. The questions, then the three groups of records.
3. A **name** is written as a series of **labels**. Each label is one length octet followed by that many characters, and the name ends with a zero octet. So `seatwave.example.` is `8 seatwave 7 example 0`. A label may have at most 63 octets, and a whole name at most 255.
4. **Name compression**: to save space, a name may end with a two-octet **pointer** whose first two bits are `11` (the octet `0xC0` or more) and whose other 14 bits are an offset from the start of the message. It means "continue reading the name from there". A pointer is the classic attack surface of a DNS reader: a pointer that points at itself, or two pointers that point at each other, would make a careless reader loop for ever.
5. A **record** is: owner name, 2-octet type, 2-octet class, 4-octet TTL, 2-octet data length, then the data.

**How malformed packets are attacked here.** A normal encoder can only write good packets, so a test cannot use it to make a bad one. `MessageCodecTest` has its own small builder, `Packet`, that writes bytes one by one. With it a test writes exactly the hostile bytes it wants: a header that promises 65,535 questions, a label that says 10 octets and then stops, a pointer to itself, a record whose data length is wrong. The decoder must answer each with a typed error (a `MessageProblem`), as a value, within the time of one call.

**How these tests stay deterministic.** Four habits do it. First, no network and no clock: every test in this file is a pure function of its inputs. Second, `TestZones` reads the zone files of the repository by relative paths, so tests and the lab read the same records. Third, chance comes from a **seed**: `ZoneTest` and the weighted lookups use `new SplittableRandom(SEED)`, a random-number generator that starts from a fixed number and so gives the same sequence on every run. Fourth, the tolerance of a statistical check is derived (three standard errors), not guessed.

## Walkthrough of this repository's use

### `TestNames`: the helper

```java
package io.plainround.seatwave.dns.message;

import java.net.Inet4Address;
import java.net.InetAddress;

/** Test helpers that build names, addresses and records from text that is known to be valid. */
public final class TestNames {

  // The SOA timers of the lab zone: refresh 2 h, retry 15 min, expire 14 days.
  private static final long REFRESH_SECONDS = 7_200;
  private static final long RETRY_SECONDS = 900;
  private static final long EXPIRE_SECONDS = 1_209_600;

  private TestNames() {}
```

`TestNames` is `public final` with a private constructor, so it is a bag of static helper methods that nobody can instantiate. It lives in the `message` package but is used by tests in several packages. The three constants are the SOA timers of the lab zone: refresh 2 hours (7,200 seconds), retry 15 minutes (900) and expire 14 days (1,209,600). They have names because a bare 900 would be a mystery number.

```java
  /**
   * Parses a name that the test knows is valid.
   *
   * @param text the name
   * @return the name
   */
  public static DomainName name(String text) {
    return DomainName.parse(text).discardError().orElseThrow();
  }

  /**
   * Parses an address literal; never looks anything up.
   *
   * @param literal for example 192.0.2.10
   * @return the address
   */
  public static InetAddress address(String literal) {
    return InetAddress.ofLiteral(literal);
  }
```

`name(text)` parses a name that the test knows is valid. `DomainName.parse` returns a result type (`result4j.md`): a success or a typed error. `discardError().orElseThrow()` turns "success or error" into the value, or throws if it was an error. In a test that is what we want: a mistake in the test's own input should fail loudly. `address(literal)` uses `InetAddress.ofLiteral`, which reads an address written as digits and **never asks DNS**, so no helper can send a lookup.

```java
  /**
   * Builds an A or AAAA record.
   *
   * @param owner the owner name
   * @param ttlSeconds the TTL
   * @param literal the address
   * @return the record
   */
  public static ResourceRecord addressRecord(String owner, long ttlSeconds, String literal) {
    InetAddress address = address(literal);
    RecordType type = address instanceof Inet4Address ? RecordType.A : RecordType.AAAA;
    return new ResourceRecord(name(owner), type, ttlSeconds, new RecordData.Address(address));
  }

  /**
   * Builds an NS or CNAME record.
   *
   * @param owner the owner name
   * @param type NS or CNAME
   * @param ttlSeconds the TTL
   * @param target the target name
   * @return the record
   */
  public static ResourceRecord hostRecord(
      String owner, RecordType type, long ttlSeconds, String target) {
    return new ResourceRecord(name(owner), type, ttlSeconds, new RecordData.Host(name(target)));
  }
```

`addressRecord` builds an A or AAAA record: it picks the type from the address class (`Inet4Address` means IPv4, so A; otherwise AAAA). `hostRecord` builds an NS or CNAME, whose data is a name (a `RecordData.Host`).

```java
  /**
   * Builds an SOA record whose negative-caching TTL is the given minimum.
   *
   * @param owner the zone apex
   * @param ttlSeconds the TTL of the SOA itself
   * @param minimumSeconds the minimum field
   * @return the record
   */
  public static ResourceRecord soa(String owner, long ttlSeconds, long minimumSeconds) {
    return new ResourceRecord(
        name(owner),
        RecordType.SOA,
        ttlSeconds,
        new RecordData.Authority(
            name("ns1." + owner),
            name("hostmaster." + owner),
            1,
            REFRESH_SECONDS,
            RETRY_SECONDS,
            EXPIRE_SECONDS,
            minimumSeconds));
  }
```

`soa` builds an SOA record for a zone. The primary server is `ns1.` plus the zone and the contact is `hostmaster.` plus the zone, the serial is 1, and the last field, the negative-caching TTL, is a parameter, because tests vary it.

### `DomainNameTest` (6 tests)

```java
package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainNameTest {

  private static final int TOO_LONG_LABEL = 64;
  private static final int LABELS_OVER_255_OCTETS = 5;
  private static final int BOOK_WIRE_OCTETS = 23;
```

The three constants are named limits: a label of 64 octets (one over the maximum of 63), five labels for a name over 255 octets, and 23 octets for the wire length of `book.seatwave.example`.

```java
  @Test
  void readsAbsoluteAndRelativeSpellingsAsOneLowerCaseName() {
    DomainName withDot = TestNames.name("SeatWave.Example.");
    DomainName withoutDot = TestNames.name("seatwave.example");

    assertThat(withDot).isEqualTo(withoutDot);
    assertThat(withDot.labels()).containsExactly("seatwave", "example");
    assertThat(withDot).hasToString("seatwave.example.");
  }
```

**Proves:** `SeatWave.Example.` and `seatwave.example` are the same name, and names are stored in lower case. **Why it matters:** DNS names are case-insensitive, and a zone must find `SeatWave.Example` when the file says `seatwave.example`. A final dot means "complete from the root"; without it the library still reads a complete name.

```java
  @Test
  void readsTheRoot() {
    assertThat(TestNames.name(".")).isEqualTo(DomainName.ROOT);
    assertThat(DomainName.ROOT).hasToString(".");
    assertThat(DomainName.ROOT.wireLength()).isEqualTo(1);
  }
```

**Proves:** a single dot is the root name, prints as `.`, and takes one octet on the wire (the zero octet).

```java
  @Test
  void refusesEmptyLabelsLongLabelsAndCharactersOutsideTheAllowList() {
    assertThat(problem("")).isEqualTo("a name must not be empty");
    assertThat(problem("seatwave..example")).startsWith("a name must not have an empty label");
    assertThat(problem("a".repeat(TOO_LONG_LABEL) + ".example"))
        .startsWith("a label is longer than 63 octets");
    assertThat(problem("seat wave.example")).startsWith("a label may hold only");
    assertThat(problem("-seatwave.example")).startsWith("a label may hold only");
  }
```

**Proves:** five bad names are refused with specific messages: empty text, an empty label (`seatwave..example`), a label of 64 octets, a space inside a label, and a leading hyphen. Labels are limited to letters, digits, hyphen and underscore, an allow-list stricter than the protocol (the README lists it among the simplifications). **Why it matters:** a name is the one thing every other part trusts, so bad text must not become a name.

```java
  @Test
  void refusesANameLongerThan255Octets() {
    String label = "a".repeat(DomainName.MAX_LABEL_OCTETS);
    String name = String.join(".", Collections.nCopies(LABELS_OVER_255_OCTETS, label));

    assertThat(problem(name)).startsWith("a name is longer than 255 octets");
  }
```

**Proves:** five labels of 63 octets give a name longer than 255 octets (4 × 64 + 1 for the zero octet is already 257), and it is refused.

```java
  @Test
  void refusesAnInvalidLabelInTheConstructor() {
    assertThatThrownBy(() -> new DomainName(List.of("Upper")))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** even code that skips `parse` cannot build a bad name. The constructor itself throws `IllegalArgumentException` for the upper-case label `Upper`. The rule is kept at two doors.

```java
  @Test
  void knowsItsPlaceInTheTree() {
    DomainName book = TestNames.name("book.seatwave.example");

    assertThat(book.isAtOrBelow(TestNames.name("seatwave.example"))).isTrue();
    assertThat(book.isAtOrBelow(DomainName.ROOT)).isTrue();
    assertThat(book.isAtOrBelow(TestNames.name("example.com"))).isFalse();
    assertThat(TestNames.name("example").isAtOrBelow(book)).isFalse();
    assertThat(book.selfAndAncestors())
        .extracting(DomainName::toString)
        .containsExactly("book.seatwave.example.", "seatwave.example.", "example.", ".");
    // 1 + 4 (book) + 1 + 8 (seatwave) + 1 + 7 (example) + the final zero octet.
    assertThat(book.wireLength()).isEqualTo(BOOK_WIRE_OCTETS);
  }
```

**Proves:** the tree relations. `isAtOrBelow` says whether a name is inside a zone: `book.seatwave.example` is at or below `seatwave.example` and below the root, but not below `example.com`, and a parent is not below its child. `selfAndAncestors()` lists the name and every parent up to the root. `wireLength()` is 23: `1 + 4`, `1 + 8`, `1 + 7` and the zero octet, as the comment says. **Why it matters:** the zone uses these relations to decide "is this name mine?" and "which child zone is it in?".

```java
  private static String problem(String text) {
    return ((Result.Error<DomainName, String>) DomainName.parse(text)).error();
  }
```

A private helper. `DomainName.parse(text)` returns a `Result`. The test casts it to the error case and takes the message. A cast is safe because each caller has chosen text that must fail.

### `ValuesTest` (7 tests)

```java
package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValuesTest {

  private static final int MX_TYPE = 15;
  private static final int TOO_BIG = 0x1_0000;
  private static final int TEXT_LIMIT = 255;
```

The imports and three constants: 15 is the type code of MX (a record type the lab does not know), `0x1_0000` is 65,536, one more than a 16-bit field can hold (the underscore only makes the number easier to read), and 255 is the limit of one text string.

```java
  @Test
  void refusesDataThatDoesNotMatchTheType() {
    RecordData ipv6 = new RecordData.Address(TestNames.address("2001:db8::10"));
    RecordData host = new RecordData.Host(TestNames.name("seatwave.example"));

    assertThatThrownBy(() -> new ResourceRecord(TestNames.name("x.example"), RecordType.A, 1, ipv6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ResourceRecord(TestNames.name("x.example"), RecordType.TXT, 1, host))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a record whose data does not fit its type cannot be built. An A record may not hold an IPv6 address (`RecordData.Address` with `2001:db8::10`), and a TXT record may not hold a name. Both throw `IllegalArgumentException`. **Why it matters:** the zone and the codec can then trust every `ResourceRecord` they receive.

```java
  @Test
  void refusesATtlOutsideTheRangeOfRfc2181() {
    assertThatThrownBy(() -> TestNames.addressRecord("x.example", -1, "192.0.2.10"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            TestNames.addressRecord("x.example", ResourceRecord.MAX_TTL_SECONDS + 1, "192.0.2.10"))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** the TTL range of RFC 2181: a TTL is from 0 to 2,147,483,647. `-1` and `MAX_TTL_SECONDS + 1` are refused.

```java
  @Test
  void writesEachRecordTheWayDigDoes() {
    assertThat(TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"))
        .hasToString("seatwave.example. 3600 IN A 192.0.2.10");
    assertThat(
            TestNames.hostRecord("www.seatwave.example", RecordType.CNAME, 60, "seatwave.example"))
        .hasToString("www.seatwave.example. 60 IN CNAME seatwave.example.");
    assertThat(TestNames.soa("seatwave.example", 3600, 300))
        .hasToString(
            "seatwave.example. 3600 IN SOA ns1.seatwave.example. hostmaster.seatwave.example."
                + " 1 7200 900 1209600 300");
    assertThat(
            new ResourceRecord(
                TestNames.name("seatwave.example"),
                RecordType.TXT,
                3600,
                new RecordData.Text(List.of("one", "two"))))
        .hasToString("seatwave.example. 3600 IN TXT \"one\" \"two\"");
  }
```

**Proves:** every record prints the way dig prints it: owner, TTL, class `IN`, type, data. The SOA prints all seven fields in dig's order; the TXT prints each string in double quotes. The test strings in `hasToString` are the same texts the README shows from dig, so a learner can compare them. A long expected string is built with `+` across two lines to respect the 100-column limit of the formatter (`spotless-google-java-format.md`).

```java
  @Test
  void refusesEmptyOrLongTextAndSoaNumbersBeyond32Bits() {
    assertThatThrownBy(() -> new RecordData.Text(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordData.Text(List.of("x".repeat(TEXT_LIMIT + 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TestNames.soa("seatwave.example", 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** an empty TXT list, a TXT string of 256 characters and an SOA number below zero are all refused. SOA numbers are 32-bit unsigned in the protocol; the code keeps them in a `long` and refuses what a 32-bit field cannot hold.

```java
  @Test
  void keepsUnknownQuestionTypesAndRefusesCodesBeyond16Bits() {
    Question mail =
        new Question(TestNames.name("seatwave.example"), MX_TYPE, Question.CLASS_INTERNET);

    assertThat(mail.type()).isEmpty();
    assertThat(Question.of(TestNames.name("seatwave.example"), RecordType.AAAA).type())
        .contains(RecordType.AAAA);
    assertThatThrownBy(() -> new Question(TestNames.name("x.example"), TOO_BIG, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** a question with a type code the lab does not know (MX, 15) is kept, with an empty known type, so the server can answer it with "no data" instead of failing on it. A type code above 16 bits (65,536) is refused. `Question.of` builds the usual case.

```java
  @Test
  void readsTheFlagsOfAMessage() {
    Message response =
        new Message(
            1,
            Message.RESPONSE_FLAG | Message.AUTHORITATIVE_FLAG | ResponseCode.NAME_ERROR.code(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThat(response.isResponse()).isTrue();
    assertThat(response.isAuthoritative()).isTrue();
    assertThat(response.opcode()).isZero();
    assertThat(ResponseCode.fromCode(response.responseCodeValue()))
        .contains(ResponseCode.NAME_ERROR);
    assertThatThrownBy(() -> new Message(TOO_BIG, 0, List.of(), List.of(), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** the flag bits of a message. `new Message(id, flags, ...)` takes the 16 flag bits as one number. The test builds a response that is authoritative with the "no such name" code by OR-ing three constants (`|` joins bits). The accessors `isResponse()`, `isAuthoritative()`, `opcode()` (the kind of request, 0 is an ordinary query) and `responseCodeValue()` read them back. A message id of 65,536 is refused.

```java
  @Test
  void findsTypesByCodeAndByName() {
    assertThat(RecordType.fromCode(RecordType.AAAA.code())).contains(RecordType.AAAA);
    assertThat(RecordType.fromCode(MX_TYPE)).isEmpty();
    assertThat(RecordType.fromName("cname")).contains(RecordType.CNAME);
    assertThat(RecordType.fromName("MX")).isEmpty();
    assertThat(ResponseCode.fromCode(MX_TYPE)).isEmpty();
  }
```

**Proves:** the lookups of types and codes by number and by name: `fromName("cname")` works in lower case, `fromName("MX")` is empty, and an unknown response code is empty.

### `MessageCodecTest` (12 tests)

```java
package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

  private static final int ID = 0x1234;
  private static final int OPT_TYPE = 41;
  private static final int EDNS_UDP_SIZE = 1232;
  private static final int POINTER = 0xC0;
  private static final int RESERVED_LABEL = 0x40;
  private static final int HEADER_END = 12;
  private static final int FOUR = 4;
  private static final int THREE = 3;
  private static final int TTL_WITH_TOP_BIT = 0x80;
  private static final int TEN = 10;
```

The constants name the magic numbers of the wire format: the test id `0x1234`; type 41 (the EDNS option record that dig adds) and the size 1232 it advertises; `0xC0` (the first octet of a compression pointer, bits `11`); `0x40` (the reserved label type `01`); 12 (the header length); and the small numbers 4, 3 and 10 for lengths in the hostile packets.

```java
  @Test
  void writesAndReadsEveryRecordTypeUnchanged() {
    Message message =
        new Message(
            ID,
            Message.RESPONSE_FLAG | Message.AUTHORITATIVE_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(
                TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"),
                TestNames.addressRecord("seatwave.example", 3600, "2001:db8::10"),
                TestNames.hostRecord(
                    "www.seatwave.example", RecordType.CNAME, 3600, "seatwave.example"),
                new ResourceRecord(
                    TestNames.name("seatwave.example"),
                    RecordType.TXT,
                    3600,
                    new RecordData.Text(List.of("seatwave-lab", "second string")))),
            List.of(
                TestNames.hostRecord(
                    "seatwave.example", RecordType.NS, 3600, "ns1.seatwave.example")),
            List.of(TestNames.soa("seatwave.example", 3600, 300)));

    Result<Message, MessageProblem> decoded = MessageCodec.decode(MessageCodec.encode(message));

    assertThat(decoded.discardError().orElseThrow()).isEqualTo(message);
  }
```

**Proves:** the round trip. A response with a question, four answer records (an A, an AAAA, a CNAME and a TXT with two strings), an NS and an SOA is encoded to bytes and decoded again, and the result equals the original message (a record's `equals` compares all its parts). **Why it matters:** one test covers every record type the lab speaks, in both directions.

```java
  @Test
  void followsACompressionPointerThatPointsBackward() {
    Packet packet = Packet.header(1, 1, 0, 0);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    // The answer's owner is a pointer to the question's name at offset 12.
    packet.bytes(POINTER, HEADER_END).shorts(RecordType.A.code(), 1).ints(60).shorts(FOUR);
    packet.bytes(192, 0, 2, 10);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.answers().getFirst()).hasToString("seatwave.example. 60 IN A 192.0.2.10");
  }
```

**Proves:** a valid compression pointer is followed. The packet is built by hand: a header with one question and one answer; the question name at offset 12; and an answer whose owner is just the two octets `0xC0 0x0C`, a pointer to offset 12. After the pointer come the type A, class 1, a TTL of 60 (4 octets, from `ints(60)`), a data length of 4 and the address `192.0.2.10`. The decoded answer prints as `seatwave.example. 60 IN A 192.0.2.10`.

```java
  @Test
  void refusesAPointerToItself() {
    Packet packet = Packet.header(1, 0, 0, 0);
    packet.bytes(POINTER, HEADER_END).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet))
        .isEqualTo(new MessageProblem.BadName("a compression pointer must point backward"));
  }
```

**Proves, attack 1:** a pointer to itself. At offset 12 the packet holds a pointer to offset 12. The decoder's rule is that a pointer must point strictly backward, before the place the name started, so this is refused with `BadName("a compression pointer must point backward")`.

```java
  @Test
  void refusesTwoPointersThatPointAtEachOther() {
    Packet packet = Packet.header(1, 0, 0, 0);
    // A label at 12, then a pointer at 14 back to 12; the label is followed by the pointer again.
    packet.bytes(1, 'a', POINTER, HEADER_END).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isInstanceOf(MessageProblem.BadName.class);
  }
```

**Proves, attack 2:** two pointers that lead round in a circle. The label `a` sits at 12, then at 14 a pointer back to 12. Reading would go label, pointer, label, pointer, for ever. The decoder remembers where the last jump landed and requires each new pointer to point before that, so the second round is refused as a `BadName`. **Why it matters:** this is how a reader is protected from an endless loop, the oldest trick against DNS software.

```java
  @Test
  void refusesAReservedLabelType() {
    Packet packet = Packet.header(1, 0, 0, 0);
    packet.bytes(RESERVED_LABEL, 0).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isEqualTo(new MessageProblem.BadName("a reserved label type"));
  }
```

**Proves, attack 3:** a label octet of `0x40` (bits `01`) is a reserved label type, not a length, and is refused with `BadName("a reserved label type")`.

```java
  @Test
  void refusesANameLongerThan255OctetsOnTheWire() {
    Packet packet = Packet.header(1, 0, 0, 0);
    String label = "a".repeat(DomainName.MAX_LABEL_OCTETS);
    packet.name(label, label, label, label).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isEqualTo(new MessageProblem.BadName("longer than 255 octets"));
  }
```

**Proves, attack 4:** a name that is longer than 255 octets on the wire. Four labels of 63 octets are each legal, but together they are 257 octets, and the decoder refuses the name.

```java
  @Test
  void refusesAShortHeaderCountsTooLargeAndACutName() {
    assertThat(MessageCodec.decode(new byte[] {1, 2, THREE}))
        .isEqualTo(Result.error(new MessageProblem.Truncated("header")));
    Packet tooMany = Packet.header(0xFFFF, 0, 0, 0);
    assertThat(problem(tooMany)).isInstanceOf(MessageProblem.BadCounts.class);
    Packet cut = Packet.header(1, 0, 0, 0);
    // A label that says 10 octets, followed by only 4.
    cut.bytes(TEN, 's', 'e', 'a', 't');
    assertThat(problem(cut)).isEqualTo(new MessageProblem.Truncated("name"));
  }
```

**Proves, attacks 5 to 7:** (a) three octets are fewer than a 12-octet header, so the error is `Truncated("header")`. (b) A header that claims 65,535 questions in a packet that cannot hold them is refused as `BadCounts`. (c) A label that says 10 octets followed by only four ends inside the name: `Truncated("name")`. Together these are the length checks at the boundary.

```java
  @Test
  void refusesAnAddressOfTheWrongLengthAndBytesAfterTheLastRecord() {
    Packet wrongLength = Packet.header(0, 1, 0, 0);
    wrongLength.name("seatwave", "example").shorts(RecordType.A.code(), 1).ints(60).shorts(THREE);
    wrongLength.bytes(192, 0, 2);
    assertThat(problem(wrongLength))
        .isEqualTo(new MessageProblem.BadRecord("an address record must hold 4 octets"));

    Packet trailing = Packet.header(1, 0, 0, 0);
    trailing.name("seatwave", "example").shorts(RecordType.A.code(), 1).bytes(0, 0, 0, 0, 0);
    assertThat(problem(trailing)).isEqualTo(new MessageProblem.TrailingBytes(FOUR + 1));
  }
```

**Proves, attacks 8 and 9:** an A record with a data length of 3 (an IPv4 address has 4 octets) is `BadRecord("an address record must hold 4 octets")`, and a message with unused octets after its last record is `TrailingBytes` with the count (5). **Why it matters:** the message must end exactly where the header says; extra octets are a way to hide data.

```java
  @Test
  void refusesTextThatRunsPastItsRecordAndASoaOfTheWrongLength() {
    Packet text = Packet.header(0, 1, 0, 0);
    text.name("seatwave", "example").shorts(RecordType.TXT.code(), 1).ints(60).shorts(THREE);
    text.bytes(FOUR + 1, 'a', 'b');
    assertThat(problem(text)).isInstanceOf(MessageProblem.BadRecord.class);

    Packet authority = Packet.header(0, 1, 0, 0);
    authority.name("seatwave", "example").shorts(RecordType.SOA.code(), 1).ints(60).shorts(THREE);
    authority.bytes(0, 0, 0);
    assertThat(problem(authority))
        .isEqualTo(new MessageProblem.BadRecord("an SOA record must end in 20 octets"));
  }
```

**Proves, attacks 10 and 11:** a TXT record whose string says it has 5 octets while the record data holds only 3 is a `BadRecord`, and an SOA record whose data is 3 octets (not the 20 that follow the two names) is `BadRecord("an SOA record must end in 20 octets")`. A length inside a length must never be believed without checking it against the outer one.

```java
  @Test
  void skipsTheEdnsOptionRecordThatDigAdds() {
    Packet packet = Packet.header(1, 0, 0, 1);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    packet.bytes(0).shorts(OPT_TYPE, EDNS_UDP_SIZE).ints(0).shorts(0);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.questions()).hasSize(1);
    assertThat(decoded.additional()).isEmpty();
  }
```

**Proves:** a normal packet from dig is read. dig adds an EDNS option record (type 41) to the additional section, with the root name (a single zero octet), the sender's UDP size in the class field and zero TTL and data. The decoder skips it: the decoded message has one question and an empty additional list. The README lists "No EDNS" among the simplifications.

```java
  @Test
  void readsATtlWithTheTopBitSetAsZero() {
    Packet packet = Packet.header(0, 1, 0, 0);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    packet.bytes(TTL_WITH_TOP_BIT, 0, 0, 0).shorts(FOUR).bytes(192, 0, 2, 10);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.answers().getFirst().ttlSeconds()).isZero();
  }
```

**Proves:** a TTL whose top bit is set (`0x80000000`) is read as zero. RFC 2181 says a TTL above 2^31 - 1 is to be treated as zero.

```java
  @Test
  void readsTheIdentifierOfAnyPacketWithAHeader() {
    assertThat(MessageCodec.idOf(Packet.header(1, 0, 0, 0).toBytes())).isEqualTo(ID);
  }

  private static MessageProblem problem(Packet packet) {
    return ((Result.Error<Message, MessageProblem>) MessageCodec.decode(packet.toBytes())).error();
  }
```

**Proves:** `idOf` reads the id (`0x1234`) of any packet that has a header, even one the decoder will reject, so the server can reply "format error" with the right id. The helper `problem(packet)` casts the failed result to its `MessageProblem`.

```java
  /** Builds a raw packet byte by byte, so a test can write what no encoder would. */
  static final class Packet {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static Packet header(int questions, int answers, int authority, int additional) {
      Packet packet = new Packet();
      return packet.shorts(ID, 0, questions, answers, authority, additional);
    }

    Packet name(String... labels) {
      for (String label : labels) {
        out.write(label.length());
        out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
      }
      out.write(0);
      return this;
    }

    Packet bytes(int... values) {
      for (int value : values) {
        out.write(value);
      }
      return this;
    }

    Packet shorts(int... values) {
      for (int value : values) {
        out.write(value >> Byte.SIZE);
        out.write(value);
      }
      return this;
    }

    Packet ints(int value) {
      return shorts(value >>> Short.SIZE, value & 0xFFFF);
    }

    byte[] toBytes() {
      return out.toByteArray();
    }
  }
```

The `Packet` builder. It collects octets in a `ByteArrayOutputStream`, an in-memory byte list. `header(...)` writes the id, zero flags and the four counts. `name(labels...)` writes each label with its length and the final zero. `bytes(...)` writes raw octets; `out.write(int)` keeps only the low eight bits. `shorts(...)` writes 16-bit numbers high byte first (`value >> Byte.SIZE` shifts the high byte down; DNS writes numbers in **network order**, big end first). `ints(value)` writes a 32-bit number as two shorts (`>>>` is a shift that treats the number as unsigned). Each method returns `this`, so calls can be chained: `packet.bytes(...).shorts(...).ints(60)`. `toBytes()` returns the finished array.

### `WeightsTest` (3 tests)

```java
package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightsTest {

  private static final DomainName BOOK = TestNames.name("book.seatwave.example");
  private static final InetAddress SITE_ONE = TestNames.address("192.0.2.10");
  private static final InetAddress SITE_TWO = TestNames.address("198.51.100.20");
  private static final InetAddress NEW_SITE = TestNames.address("203.0.113.30");
  private static final int NINETY = 90;
  private static final int TEN = 10;
  private static final int TOO_HEAVY = 256;
```

`Weights` reads the same weights file as CoreDNS's `loadbalance weighted` plugin (`coredns.md`). The constants name two sites, a new site, the weights 90 and 10 and an overweight 256.

```java
  @Test
  void readsTheCoreDnsWeightFileFormat() {
    Weights weights =
        Weights.parse("# comment\nbook.seatwave.example\n192.0.2.10 90\n198.51.100.20 10\n")
            .discardError()
            .orElseThrow();

    assertThat(weights.weightOf(BOOK, SITE_ONE)).isEqualTo(NINETY);
    assertThat(weights.weightOf(BOOK, SITE_TWO)).isEqualTo(TEN);
    assertThat(weights.weightOf(BOOK, NEW_SITE)).isEqualTo(Weights.MIN_WEIGHT);
  }
```

**Proves:** the file format: comment lines start with `#`, a name stands alone on a line, and each address with its weight follows. `weightOf(BOOK, NEW_SITE)` for an address not in the file is `Weights.MIN_WEIGHT` (1), as the plugin documentation says.

```java
  @Test
  void reportsWeightsOutOfRangeAndLinesInTheWrongPlace() {
    String text = "192.0.2.10 5\nbook..example\nbook.seatwave.example\n192.0.2.10 0\nx 1\na b c\n";

    List<String> problems = ((Result.Error<Weights, List<String>>) Weights.parse(text)).error();

    assertThat(problems)
        .containsExactly(
            "weights line 1: expected a name, or an address and a weight after a name",
            "weights line 2: a name must not have an empty label: book..example",
            "weights line 4: a weight must be from 1 to 255, but was 0",
            "weights line 5: not an address and a whole-number weight: x 1",
            "weights line 6: expected a name, or an address and a weight after a name");
    assertThatThrownBy(() -> new Weights(Map.of(BOOK, Map.of(SITE_ONE, TOO_HEAVY))))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

**Proves:** every kind of mistake is reported with its line number, all in one run: an address line before any name (line 1), a name with an empty label (2), a weight of 0 (4), text that is not an address and a number (5), and a line with three words (6). The checks come in a fixed order, and `containsExactly` checks both the messages and their order. Then a direct build with a weight of 256 throws, because the valid range is 1 to 255. **Why it matters:** a person who edits the file sees all the problems at once.

```java
  @Test
  void movesAWeightToTheNewAddressOfOneNameOnly() {
    DomainName apex = TestNames.name("seatwave.example");
    Weights weights =
        new Weights(Map.of(BOOK, Map.of(SITE_ONE, NINETY), apex, Map.of(SITE_ONE, TEN)));

    Weights moved = weights.withAddressReplaced(apex, SITE_ONE, NEW_SITE);

    assertThat(moved.weightOf(apex, NEW_SITE)).isEqualTo(TEN);
    assertThat(moved.weightOf(BOOK, SITE_ONE)).isEqualTo(NINETY);
    assertThat(weights.withAddressReplaced(apex, SITE_TWO, NEW_SITE)).isEqualTo(weights);
  }
```

**Proves:** `withAddressReplaced` gives the weight of the old address to the new one, for one name only. The apex has the weight 10, and after the move the new address has 10 at the apex while `book` still has 90 for the old address. Replacing an address that has no weight returns an equal object. This is what the migration does to the weights when it switches an address.

### `ZoneFileTest` (3 tests)

```java
package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneFileTest {

  private static final DomainName ORIGIN = TestNames.name("seatwave.example");
```

```java
  @Test
  void readsOwnersTtlsClassesAndEveryType() {
    String text =
        """
        $TTL 300
        @      IN 3600 SOA ns1 hostmaster 1 7200 900 1209600 300 ; class before TTL
        @      3600 IN NS  ns1.seatwave.example.
        ns1    A   127.0.0.1
               AAAA 2001:db8::53
        www    60 CNAME @
        @      TXT "two words" "and; more"
        """;

    List<ResourceRecord> records = ZoneFile.parse(text, ORIGIN).discardError().orElseThrow();

    assertThat(records)
        .extracting(ResourceRecord::toString)
        .containsExactly(
            "seatwave.example. 3600 IN SOA ns1.seatwave.example. hostmaster.seatwave.example."
                + " 1 7200 900 1209600 300",
            "seatwave.example. 3600 IN NS ns1.seatwave.example.",
            "ns1.seatwave.example. 300 IN A 127.0.0.1",
            "ns1.seatwave.example. 300 IN AAAA 2001:db8:0:0:0:0:0:53",
            "www.seatwave.example. 60 IN CNAME seatwave.example.",
            "seatwave.example. 300 IN TXT \"two words\" \"and; more\"");
  }
```

**Proves:** the master-file format (RFC 1035 section 5) in one block. The text block (`"""`) is a Java multi-line string. `$TTL 300` sets the default TTL. `@` means the zone's origin. The SOA line puts the class (`IN`) before the TTL (`3600`) and a trailing `; class before TTL` is a comment. The NS line puts the TTL before the class: either order is legal. A line that starts with spaces repeats the previous owner (`ns1`, then the AAAA record under it). `www 60 CNAME @` is an alias of the apex. A TXT with two quoted strings keeps the semicolon inside the quotes (`"and; more"`) and does not treat it as a comment. Relative names such as `ns1` and `hostmaster` get the origin added. The expected list shows each record in dig's form, including `2001:db8:0:0:0:0:0:53` for the compressed address `2001:db8::53`.

```java
  @Test
  void readsRelativeNamesUnderTheRoot() {
    List<ResourceRecord> records =
        ZoneFile.parse("$ORIGIN .\nexample 60 IN NS a.tld-lab.example.\n", DomainName.ROOT)
            .discardError()
            .orElseThrow();

    assertThat(records.getFirst().owner()).isEqualTo(TestNames.name("example"));
  }
```

**Proves:** under the root zone (`$ORIGIN .`) a relative owner such as `example` becomes `example.`.

```java
  @Test
  void reportsEveryProblemWithItsLineNumber() {
    String text =
        """
          A 192.0.2.10
        @ 60 IN MX 10 mail
        @ 60 IN SOA ns1 hostmaster (
        @ A 192.0.2.10
        @ 60 A 2001:db8::1
        @ 60 TXT "open
        $INCLUDE other.zone
        @ 60 SOA ns1 hostmaster 1 2 3
        @ 60 A 192.0.2.10 192.0.2.11
        @ 99999999999 A 192.0.2.10
        bad..name 60 A 192.0.2.10
        @ 60 IN
        @ 60 TXT
        @ 60 SOA ns1 hostmaster 1 2 3 4 five
        """;

    List<String> problems =
        ((Result.Error<List<ResourceRecord>, List<String>>) ZoneFile.parse(text, ORIGIN)).error();

    assertThat(problems)
        .containsExactly(
            "line 1: a blank owner needs a record above it",
            "line 2: unsupported type or class MX",
            "line 3: parentheses are not supported; write the record on one line",
            "line 4: no TTL on the record and no $TTL line above it",
            "line 5: not an A address: 2001:db8::1",
            "line 6: a quote is not closed",
            "line 7: unsupported directive $INCLUDE",
            "line 8: an SOA needs 7 fields on one line; parentheses are not supported",
            "line 9: expected exactly one value, but found 2",
            "line 10: a number must be from 0 to 2147483647: 99999999999",
            "line 11: a name must not have an empty label: bad..name.seatwave.example.",
            "line 12: a record needs a type",
            "line 13: a TXT record needs a string",
            "line 14: not a whole number: five");
  }
```

**Proves:** fourteen different mistakes, each reported with its line number in one run. The input is deliberately a pile of errors: a record with a blank owner and nothing above it (line 1), an unsupported type (MX), parentheses for continuation lines, no TTL and no `$TTL` default, an IPv6 address in an A record, an unclosed quote, the unsupported directive `$INCLUDE`, an SOA with too few fields, two values in an A record, a number too large for 31 bits, an empty label in an owner, a record with no type, a TXT with no string, and an SOA field that is not a number. **Why it matters:** the error list is the user interface of the zone file; a reader learns what the parser supports from what it refuses.

### `ZoneLoaderTest` (4 tests)

```java
package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ZoneLoaderTest {

  @Test
  void loadsTheThreeLabZones() {
    assertThat(TestZones.root().records()).hasSize(5);
    assertThat(TestZones.example().records()).hasSize(5);
    assertThat(TestZones.seatwave().records()).hasSize(11);
  }
```

**Proves:** the three lab zones load with 5, 5 and 11 records. The counts are the "records" numbers the `serve` task prints (`root ... 5 records`, `tld ... 5 records`, `seatwave ... 11 records`).

```java
  @Test
  void reportsAMissingFileABadOriginAndBrokenWeights() {
    Result<Zone, List<String>> missing =
        ZoneLoader.load("seatwave.example.", Path.of("zones/absent.zone"), ZoneLoader.NO_WEIGHTS);
    Result<Zone, List<String>> badOrigin =
        ZoneLoader.load(
            "bad..origin", Path.of("zones/seatwave.example.zone"), ZoneLoader.NO_WEIGHTS);
    Result<Zone, List<String>> broken =
        ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/broken/seatwave.example.zone"),
            "zones/broken/seatwave.example.weights");

    assertThat(((Result.Error<Zone, List<String>>) missing).error())
        .singleElement()
        .asString()
        .startsWith("cannot read zones/absent.zone");
    assertThat(((Result.Error<Zone, List<String>>) badOrigin).error())
        .contains("origin: a name must not have an empty label: bad..origin");
    assertThat(((Result.Error<Zone, List<String>>) broken).error()).hasSize(4);
  }
```

**Proves:** three failures of loading. A file that does not exist gives one message that starts with `cannot read zones/absent.zone`. An origin with an empty label gives `origin: a name must not have an empty label: bad..origin`. The broken zone of `zones/broken` gives exactly four problems, the four mistakes shown in the README under "A zone with four mistakes". `singleElement().asString().startsWith(...)` picks the one message and checks its beginning.

```java
  @Test
  void leavesMissingKeysToTheReader() {
    SettingsReader reader = new SettingsReader(new Properties());

    Result<Zone, List<String>> loaded = ZoneLoader.load(reader, "zone");

    assertThat(((Result.Error<Zone, List<String>>) loaded).error()).isEmpty();
    assertThat(reader.problems())
        .containsExactly(
            "zone.origin is missing", "zone.file is missing", "zone.weights is missing");
  }
```

**Proves:** the loader that takes a `SettingsReader` does not invent defaults. With an empty `Properties`, the loader returns an empty error list and the reader holds three problems: `zone.origin is missing`, `zone.file is missing` and `zone.weights is missing`. The reader collects the problems and the caller reports them all (the "no defaults in code" rule of the README).

```java
  @Test
  void reportsZoneFileProblemsWithTheFileName() {
    Result<Zone, List<String>> loaded =
        ZoneLoader.load(
            "seatwave.example.", Path.of("config/serve.properties"), ZoneLoader.NO_WEIGHTS);

    assertThat(((Result.Error<Zone, List<String>>) loaded).error())
        .allMatch(problem -> problem.startsWith("serve.properties line "));
  }
```

**Proves:** when a file has syntax problems, each message starts with the file name and line, such as `serve.properties line 1`. The test deliberately loads a settings file as a zone file, so every line is a syntax error. `allMatch` requires every message to carry the file name.

### `TestZones`: the helper

```java
package io.plainround.seatwave.dns.zone;

import java.nio.file.Path;

/** Loads the project's own zone files, so tests and the lab read the same records. */
public final class TestZones {

  private TestZones() {}

  /**
   * Loads seatwave.example with its 50 to 50 weights.
   *
   * @return the zone
   */
  public static Zone seatwave() {
    return ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/seatwave.example.zone"),
            "zones/seatwave.example.weights")
        .discardError()
        .orElseThrow();
  }
```

```java
  /**
   * Loads seatwave.example with weights of 90 and 10.
   *
   * @return the zone
   */
  public static Zone seatwaveNinetyTen() {
    return ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/seatwave.example.zone"),
            "zones/seatwave.example.weights-90-10")
        .discardError()
        .orElseThrow();
  }

  /**
   * Loads the lab's root zone.
   *
   * @return the zone
   */
  public static Zone root() {
    return ZoneLoader.load(".", Path.of("zones/root.zone"), ZoneLoader.NO_WEIGHTS)
        .discardError()
        .orElseThrow();
  }

  /**
   * Loads the lab's example. zone.
   *
   * @return the zone
   */
  public static Zone example() {
    return ZoneLoader.load("example.", Path.of("zones/example.zone"), ZoneLoader.NO_WEIGHTS)
        .discardError()
        .orElseThrow();
  }
```

Four static methods load the project's own files: `seatwave()` with the 50-50 weights, `seatwaveNinetyTen()` with 90 and 10, `root()` and `example()` with `ZoneLoader.NO_WEIGHTS`. They use relative paths (`zones/...`), which work because Gradle runs the tests from the project folder. A failed load throws, so a test never starts from a half-loaded zone.

### `ZoneTest` (8 tests)

```java
package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ZoneTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final int MX_TYPE = 15;
  private static final int PICKS = 10_000;
  private static final long SEED = 20_261_110;
  // Three standard errors of a 90 percent share over 10,000 picks: 0.9 percentage points.
  private static final double TOLERANCE = 0.009;
  private static final double NINETY_PERCENT = 0.90;
```

The test names things clearly: `SEED` is the fixed seed (the date of the lab, 2026-11-10, written with underscores); `PICKS` is 10,000; `TOLERANCE` has a comment that derives it: three standard errors of a 90 percent share over 10,000 picks are 3 × sqrt(0.9 × 0.1 / 10,000) = 0.009, that is 0.9 percentage points.

```java
  @Test
  void answersAnAddressFromTheZone() {
    Lookup lookup =
        TestZones.seatwave().lookup(question("seatwave.example", RecordType.A), random());

    assertThat(lookup)
        .isEqualTo(
            new Lookup.Answer(
                List.of(TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"))));
  }
```

**Proves:** a question for the A record of the apex gives a `Lookup.Answer` holding exactly one record, `192.0.2.10` with TTL 3600. `TestZones.seatwave().lookup(question, random())` takes the seeded generator because weighted names need one; for this name it is not used.

```java
  @Test
  void followsACnameInsideTheZone() {
    Lookup lookup =
        TestZones.seatwave().lookup(question("www.seatwave.example", RecordType.A), random());

    assertThat(((Lookup.Answer) lookup).records())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.CNAME, RecordType.A);
  }
```

**Proves:** asking for `www.seatwave.example` (an alias) returns the CNAME and then the A record it points to, in that order, from the same zone. A resolver would otherwise need a second question.

```java
  @Test
  void refersToTheChildZoneWithGlue() {
    Lookup lookup =
        TestZones.root().lookup(question("book.seatwave.example", RecordType.A), random());

    Lookup.Referral referral = (Lookup.Referral) lookup;
    assertThat(referral.nameServers())
        .extracting(ResourceRecord::toString)
        .containsExactly("example. 172800 IN NS a.tld-lab.example.");
    assertThat(referral.glue())
        .extracting(ResourceRecord::toString)
        .containsExactly("a.tld-lab.example. 172800 IN A 127.0.0.1");
  }
```

**Proves:** a **referral**. The root zone does not own `book.seatwave.example`, so its lookup returns the NS record of the child zone (`example. 172800 IN NS a.tld-lab.example.`) and the **glue** (the address of that name server, `a.tld-lab.example. 172800 IN A 127.0.0.1`). This is the first step of the resolution path in the README.

```java
  @Test
  void saysNoDataForAMissingTypeAndForANameWithOnlyChildren() {
    Zone seatwave = TestZones.seatwave();

    assertThat(seatwave.lookup(question("book.seatwave.example", RecordType.AAAA), random()))
        .isInstanceOf(Lookup.NoData.class);
    assertThat(seatwave.lookup(new Question(APEX, MX_TYPE, Question.CLASS_INTERNET), random()))
        .isInstanceOf(Lookup.NoData.class);
    assertThat(TestZones.example().lookup(question("tld-lab.example", RecordType.A), random()))
        .isInstanceOf(Lookup.NoData.class);
  }
```

**Proves:** "no data" is different from "no such name". `book.seatwave.example` exists but has no AAAA record; the apex has no MX; and `tld-lab.example` has a child (`a.tld-lab`) but no A record of its own. All three are `Lookup.NoData`: the name exists in the tree, but there is no record of that type.

```java
  @Test
  void saysNameErrorWithTheSoaAndOutOfZoneForOtherNames() {
    Zone seatwave = TestZones.seatwave();

    Lookup missing = seatwave.lookup(question("nowhere.seatwave.example", RecordType.A), random());

    assertThat(missing).isEqualTo(new Lookup.NameError(seatwave.soa()));
    assertThat(seatwave.lookup(question("example.com", RecordType.A), random()))
        .isInstanceOf(Lookup.OutOfZone.class);
  }
```

**Proves:** a name that is inside the zone but absent gives `Lookup.NameError` carrying the zone's SOA (so the caller can set the negative TTL), and a name outside the zone (`example.com`) is `Lookup.OutOfZone`, which the responder turns into REFUSED.

```java
  @Test
  void putsTheWeightedAddressFirstInProportionToItsWeight() {
    Zone zone = TestZones.seatwaveNinetyTen();
    SplittableRandom random = random();
    InetAddress siteOne = TestNames.address("192.0.2.10");
    int firstIsSiteOne = 0;

    for (int pick = 0; pick < PICKS; pick++) {
      Lookup.Answer answer =
          (Lookup.Answer) zone.lookup(question("book.seatwave.example", RecordType.A), random);
      assertThat(answer.records()).hasSize(2);
      if (((RecordData.Address) answer.records().getFirst().data()).address().equals(siteOne)) {
        firstIsSiteOne++;
      }
    }

    assertThat((double) firstIsSiteOne / PICKS).isCloseTo(NINETY_PERCENT, offset(TOLERANCE));
  }
```

**Proves:** the weighted lookup is statistically right and repeatable. With the 90-10 weights and the seeded generator, 10,000 lookups of `book.seatwave.example` each return both addresses, and the share of answers where `192.0.2.10` is first is within 0.9 percentage points of 0.90. **Determinism:** the generator is created from `SEED` in the same state on every run, so this test gives the same count on every run; the tolerance is there so that a change of seed (or of the generator) does not make it fail by chance. **Why it matters:** this is the check behind the README's sampler table.

```java
  @Test
  void refusesEveryMisconfigurationWithOneLineEach() {
    List<ResourceRecord> records = new ArrayList<>(TestZones.seatwave().records());
    records.removeIf(record -> record.owner().equals(TestNames.name("ns1.seatwave.example")));
    records.add(
        TestNames.hostRecord("seatwave.example", RecordType.CNAME, 3600, "hosting.example"));
    records.add(
        TestNames.hostRecord(
            "site1.seatwave.example", RecordType.CNAME, 3600, "gone.seatwave.example"));
    records.add(
        TestNames.hostRecord(
            "loop1.seatwave.example", RecordType.CNAME, 60, "loop2.seatwave.example"));
    records.add(
        TestNames.hostRecord(
            "loop2.seatwave.example", RecordType.CNAME, 60, "loop1.seatwave.example"));
    records.add(TestNames.addressRecord("book.seatwave.example", 300, "203.0.113.30"));
    records.add(TestNames.addressRecord("book.seatwave.example", 60, "192.0.2.10"));
    records.add(TestNames.addressRecord("elsewhere.example", 60, "192.0.2.99"));
    records.add(TestNames.soa("seatwave.example", 3600, 300));
    Weights weights =
        new Weights(
            Map.of(
                TestNames.name("www.seatwave.example"), Map.of(TestNames.address("192.0.2.10"), 1),
                TestNames.name("site2.seatwave.example"),
                    Map.of(TestNames.address("192.0.2.77"), 1)));

    List<String> problems =
        ((Result.Error<Zone, List<String>>) Zone.of(APEX, records, weights)).error();

    assertThat(problems)
        .anyMatch(problem -> problem.startsWith("elsewhere.example. is outside the zone"))
        .anyMatch(problem -> problem.startsWith("the zone needs exactly one SOA record"))
        .anyMatch(problem -> problem.startsWith("a CNAME at the zone apex"))
        .anyMatch(problem ->
            problem.equals(
                "a CNAME at site1.seatwave.example. must be the only record of that name"))
        .anyMatch(problem ->
            problem.contains("points at gone.seatwave.example., which the zone does not have"))
        .anyMatch(problem -> problem.equals("the CNAME chain from loop1.seatwave.example. loops"))
        .anyMatch(problem ->
            problem.startsWith("the records of book.seatwave.example. A have different TTLs"))
        .anyMatch(problem ->
            problem.startsWith(
                "the record book.seatwave.example. 60 IN A 192.0.2.10 appears twice"))
        .anyMatch(problem ->
            problem.startsWith("broken delegation: the name server ns1.seatwave.example."))
        .anyMatch(problem -> problem.startsWith("weights are given for www.seatwave.example."))
        .anyMatch(problem -> problem.startsWith("a weight is given for 192.0.2.77"));
  }
```

**Proves:** `Zone.of` refuses a zone with many mistakes and reports one line for each. The test starts from the real records and changes them: it removes the `ns1` address (so the NS has no glue), adds a CNAME at the apex, a CNAME that is not alone at its name, a CNAME to a missing name, two CNAMEs that point at each other (a loop), two A records of one name with different TTLs (300 and 60), a duplicate record, a record outside the zone (`elsewhere.example`), a second SOA, and weights for a name that has none and for an address that the name does not have. `anyMatch` then checks that each mistake has its message. **Why it matters:** these are the failure modes of the README ("A zone with four mistakes" is the shortest version).

```java
  @Test
  void refusesAZoneWithoutNameServers() {
    List<String> problems =
        ((Result.Error<Zone, List<String>>)
                Zone.of(APEX, List.of(TestNames.soa("seatwave.example", 3600, 300)), Weights.NONE))
            .error();

    assertThat(problems)
        .containsExactly("the zone needs at least one NS record at its apex seatwave.example.");
  }
```

**Proves:** a zone needs at least one NS record at its apex; with only an SOA the single problem is `the zone needs at least one NS record at its apex seatwave.example.`.

```java
  private static Question question(String name, RecordType type) {
    return Question.of(TestNames.name(name), type);
  }

  private static SplittableRandom random() {
    return new SplittableRandom(SEED);
  }
```

Two helpers: `question(name, type)` builds a question, and `random()` creates a new seeded generator, so every test starts from the same sequence.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Run the two packages:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.dns.message.*' --tests 'io.plainround.seatwave.dns.zone.*' | grep -c PASSED
```

   Output: `43` (6 + 7 + 12 for `message`, 3 + 3 + 4 + 8 for `zone`: 43).

2. Run one hostile-packet test:

```
./gradlew --offline cleanTest test --tests '*MessageCodecTest.refusesAPointerToItself'
```

   Output: `MessageCodecTest > refusesAPointerToItself() PASSED`.

3. Make the pointer rule fail on purpose (scratch copy only): in `MessageCodecTest`, change `packet.bytes(POINTER, HEADER_END)` on line 67 (in `refusesAPointerToItself`) to `packet.bytes(0, 0)`, then run the command of step 2.

   Output: `MessageCodecTest > refusesAPointerToItself() FAILED`, `org.opentest4j.AssertionFailedError at MessageCodecTest.java:70` and `1 test completed, 1 failed`. The result file says `expected: BadName[reason=a compression pointer must point backward]` and ` but was: TrailingBytes[count=1]`: the packet no longer holds a pointer, so the decoder finds a different problem. Undo the edit afterwards.

4. Look at the zone counts the loader test expects:

```
grep -c '^[^;$ ]' zones/root.zone zones/example.zone zones/seatwave.example.zone
```

   Output: `zones/root.zone:5`, `zones/example.zone:5` and `zones/seatwave.example.zone:11` (the order of the lines may differ). They match the 5, 5 and 11 of `loadsTheThreeLabZones`.

## Mistakes people make

- **Building a bad packet with the encoder.** The encoder cannot write one. The hand-made `Packet` can.
- **Trusting a length field.** Every length (label, record data, counts) is checked against what is really left.
- **Testing a random thing with an exact count.** `ZoneTest` uses a fixed seed and a derived tolerance.
- **Forgetting the final dot of a name in a zone file.** `ns1` becomes `ns1.seatwave.example.`, but `ns1.seatwave.example` (no dot) would become `ns1.seatwave.example.seatwave.example.`. The zone file in the test writes the dot.
- **Casting a result to its error case without making sure it fails.** The helpers do it only on inputs that must fail.

## Check yourself

1. *How many octets has a DNS header, and what is in it?* 12: the id, the flags and four counts.
2. *Why must a compression pointer point strictly backward?* Otherwise a pointer to itself, or two pointers in a circle, would make the reader loop for ever.
3. *What is the difference between `NoData` and `NameError`?* NoData: the name exists but has no record of that type. NameError: the name does not exist in the zone (NXDOMAIN).
4. *How does `ZoneTest` stay repeatable although it uses chance?* The generator starts from a fixed seed, and the tolerance of 0.009 is three standard errors.
5. *What does the referral from the root zone carry besides the NS record?* The glue: the address of the name server, because that server's name is below the zone it serves.

## Further reading

- RFC 1035, domain names, implementation and specification (message format, name compression, master files): <https://www.rfc-editor.org/rfc/rfc1035>
- RFC 2181, clarifications (TTL range, record sets): <https://www.rfc-editor.org/rfc/rfc2181>
- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
- AssertJ 3.27.7: <https://assertj.github.io/doc/>
