# The `message` package, line by line

## What it is

This file reads the Java code of the `message` package, one line at a time. The package has nine files: `DomainName.java`, `RecordType.java`, `ResponseCode.java`, `RecordData.java`, `ResourceRecord.java`, `Question.java`, `Message.java`, `MessageProblem.java` and `MessageCodec.java`. Together they are the **DNS message codec**: the code that turns a DNS message (a question or an answer) into bytes to send, and turns received bytes back into a message.

A **codec** (short for coder and decoder) converts between two forms of the same thing. Here the two forms are a Java object (`Message`) and a row of bytes (`byte[]`) in the layout that RFC 1035 defines. A **byte** is a number from 0 to 255; eight bits. A **packet** is one chunk of bytes sent over the network in one piece.

## Why this repository uses it

Module 6 asks what happens, step by step, when a browser looks up `seatwave.example`. The answer is a message in a fixed binary layout. The course teaches that layout, so the codec is written by hand against the standard (RFC 1035) and not taken from a library. The most important part is the **trust boundary**: every packet that arrives from the network is checked before anything else reads it. A packet is data from outside; it can be short, wrong or hostile. `MessageCodec.decode` is the one door, and it either returns a valid `Message` or says what was wrong.

`message` depends on nothing else in this project. Every other package uses it.

## Where it appears

| File | What it does |
|---|---|
| `message/DomainName.java` | A name such as `seatwave.example.` as a list of lower-case labels, with every length and character rule. |
| `message/RecordType.java` | The six record types this lab serves (A, NS, CNAME, SOA, TXT, AAAA) with their numeric codes. |
| `message/ResponseCode.java` | The six response codes this lab sends (NOERROR, FORMERR, SERVFAIL, NXDOMAIN, NOTIMP, REFUSED). |
| `message/RecordData.java` | The data part of a record: an address, a host name, an SOA, or text. |
| `message/ResourceRecord.java` | One record: owner name, type, time to live, data. |
| `message/Question.java` | The question of a message: name, type code, class code. |
| `message/Message.java` | A whole message: identifier, flags, and the four lists. |
| `message/MessageProblem.java` | The ways a packet can be malformed, as values. |
| `message/MessageCodec.java` | `encode` (object to bytes) and `decode` (bytes to object, or a problem). |

The `server`, `zone`, `resolver`, `sampling`, `jdk` and `experiment` packages all use these types. `java.md` explains the Java features used here (records, sealed interfaces, `switch` patterns); `dns-protocol.md` explains the DNS ideas; `java-udp-and-resolver-api.md` explains bytes and byte order.

## How it works underneath

**The layout of a message** (RFC 1035 section 4.1). A message is one row of bytes: a 12-byte header, then the question section, then three sections of records (answer, authority, additional).

```text
bytes 0-1    identifier (16 bits)
bytes 2-3    flags (16 bits)
bytes 4-5    number of questions
bytes 6-7    number of answer records
bytes 8-9    number of authority records
bytes 10-11  number of additional records
then         the questions, then the answers, then the authority records, then the additional records
```

Every number is **big-endian**: the most significant byte comes first. The number 0x1234 is sent as the byte 0x12 then the byte 0x34. This is also called **network byte order**.

**A name on the wire** is a series of labels. Each label is one length byte followed by that many bytes of text. The name ends with a zero byte, the empty label of the root. `seatwave.example.` becomes `08 73 65 61 74 77 61 76 65 07 65 78 61 6d 70 6c 65 00`: a length of 8, the eight letters of `seatwave`, a length of 7, the seven letters of `example`, and a zero.

**A real query and a real answer**, produced by this codec and run on this machine (the bytes are in hexadecimal; `dns-protocol.md` explains each field):

```text
QUERY 34 bytes
12 34 00 00 00 01 00 00 00 00 00 00 08 73 65 61
74 77 61 76 65 07 65 78 61 6d 70 6c 65 00 00 01
00 01

RESPONSE 66 bytes
12 34 84 00 00 01 00 01 00 00 00 00 08 73 65 61
74 77 61 76 65 07 65 78 61 6d 70 6c 65 00 00 01
00 01 08 73 65 61 74 77 61 76 65 07 65 78 61 6d
70 6c 65 00 00 01 00 01 00 00 0e 10 00 04 c0 00
02 0a
```

The query is 34 bytes: a 12-byte header, the 18-byte name, 2 bytes of type (`00 01` is A) and 2 bytes of class (`00 01` is IN). The response repeats the question, then adds the answer record: the name again (18 bytes), type `00 01`, class `00 01`, the time to live `00 00 0e 10` (3600), the data length `00 04` and the four address bytes `c0 00 02 0a`, which are 192.0.2.10. The response is 66 bytes; the README's `dig` output shows `MSG SIZE rcvd: 66`.

**Name compression.** On the wire, a name may be replaced by a **pointer**: two bytes whose top two bits are `11`, and whose other 14 bits are the offset (counted from the start of the message) where the rest of the name can be found. A pointer lets a long name appear once. This codec **reads** pointers but never **writes** them. The reading is the dangerous part: a packet that holds a pointer to itself would make a naive reader loop forever. The decoder refuses every pointer that does not point strictly backward.

**Expected failures are values.** A bad packet is not a bug in this program; the network can send anything. So `decode` returns a `Result<Message, MessageProblem>` (see `result4j.md`): either the message or the first problem found. Inside the decoder, a private exception stops the work quickly, and `decode` turns it into a `Result` before returning. No caller ever sees that exception.

## Walkthrough of this repository's use

### `message/DomainName.java`

```java
package io.plainround.seatwave.dns.message;

import com.github.sviperll.result4j.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
```

- `package` says which group of classes this file belongs to. The imports bring in `Result` (the success-or-error type from the result4j library), `StandardCharsets` (names for text encodings), `ArrayList` and `List` (lists), `Locale` (language rules for text), `Objects` (null checks) and `Pattern` (regular expressions).

```java
/**
 * A domain name in its canonical form: lower-case labels, from the leftmost to the top-level
 * domain, without the empty root label. The root itself has no labels.
 *
 * <p>Every label matches an allow-list (letters, digits, hyphen, underscore; no hyphen at either
 * end), is 1 to 63 octets long, and the whole name is at most 255 octets on the wire (RFC 1035
 * section 2.3.4). Names are compared case-insensitively by storing them in lower case (RFC 4343).
 *
 * @param labels the labels, leftmost first
 */
public record DomainName(List<String> labels) {
```

- The Javadoc comment (between `/**` and `*/`) describes the type. A **domain name** is written as labels separated by dots. Here it is stored as a list of labels, leftmost first, in lower case. The root of the whole name space has no labels.
- A **label** is one part between dots, such as `seatwave`. RFC 1035 section 2.3.4 limits a label to 63 bytes and a whole name to 255 bytes on the wire. RFC 4343 says names are compared without regard to case; the way this code gets that is to store everything in lower case.
- `public record DomainName(List<String> labels)` declares a **record**: a short way to write a class that only holds data. The one component `labels` becomes a private final field, an accessor method `labels()`, and an automatic `equals`, `hashCode` and `toString`. Two names are equal when their label lists are equal, so a `DomainName` works as a key in a map.

```java
  /** The root of the name space, written "." */
  public static final DomainName ROOT = new DomainName(List.of());

  /** The longest label, in octets (RFC 1035 section 2.3.4). */
  public static final int MAX_LABEL_OCTETS = 63;

  /** The longest name on the wire, length octets and the final zero included (RFC 1035). */
  public static final int MAX_NAME_OCTETS = 255;

  // An allow-list, not a deny-list: anything else is refused at the boundary. The underscore is
  // allowed because service labels such as _dmarc use it (RFC 8552).
  private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?");
```

- `ROOT` is the name with no labels, written `.`. It is a constant: `static final`, one copy for the whole program.
- `MAX_LABEL_OCTETS` and `MAX_NAME_OCTETS` hold the two RFC limits. An **octet** is the standard word for an 8-bit byte.
- The two `//` lines are ordinary comments. They say why the check is an **allow-list**: it lists what is permitted, and refuses everything else. A deny-list would try to list bad characters and would miss some.
- `LABEL` is a regular expression, compiled once. `[a-z0-9_]` is one character from lower-case letters, digits or underscore. `([a-z0-9_-]*[a-z0-9_])?` is an optional rest: any number of those characters or a hyphen, ending in a character that is not a hyphen. So a label may not start or end with a hyphen. The underscore is allowed because service labels such as `_dmarc` use it (RFC 8552).

```java
  /**
   * Creates a name from labels that are already valid.
   *
   * @throws IllegalArgumentException if a label or the whole name is invalid
   */
  public DomainName {
    labels = List.copyOf(labels);
    String problem = problemWith(labels);
    if (!problem.isEmpty()) {
      throw new IllegalArgumentException(problem);
    }
  }
```

- This is the **compact constructor** of the record. It has no parameter list: it runs before the fields are assigned, and the parameters are the record's components. Whatever it assigns to `labels` is what is stored.
- `List.copyOf(labels)` (Java 10) makes an unmodifiable copy and throws `NullPointerException` if the list or any element is null. After this line nobody can change the name by changing the list they passed in.
- `problemWith` returns a text, or an empty text if the labels are fine. A problem becomes an `IllegalArgumentException`: this constructor is for labels that are **already valid**, so a bad one is a bug in the caller. For text that comes from outside, use `parse`.

```java
  /**
   * Parses a name written as text, such as {@code seatwave.example.} or {@code seatwave.example}.
   * Both forms mean the same absolute name.
   *
   * @param text the name
   * @return the name, or the reason it is invalid
   */
  public static Result<DomainName, String> parse(String text) {
    Objects.requireNonNull(text, "text");
    String trimmed = text.strip().toLowerCase(Locale.ROOT);
    if (trimmed.equals(".")) {
      return Result.success(ROOT);
    }
    String withoutDot =
        trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    if (withoutDot.isEmpty()) {
      return Result.error("a name must not be empty");
    }
    List<String> labels = List.of(withoutDot.split("\\.", -1));
    String problem = problemWith(labels);
    return problem.isEmpty()
        ? Result.success(new DomainName(labels))
        : Result.error(problem + ": " + text.strip());
  }
```

- `parse` turns written text into a name. It returns `Result<DomainName, String>`: the name, or the reason as a text. A person or a file can write a bad name, so this is an expected failure and not an exception.
- `strip()` removes spaces at both ends (Java 11). `toLowerCase(Locale.ROOT)` makes it lower case using fixed rules; without `Locale.ROOT`, a computer set to Turkish would turn `I` into a dotless `ı`.
- A single dot means the root.
- `withoutDot` removes one trailing dot, so `seatwave.example.` and `seatwave.example` mean the same absolute name. The `? :` is the conditional expression: the left value if the test is true, otherwise the right one.
- An empty text is refused.
- `split("\\.", -1)` cuts the text at each dot. In a Java string, `"\\."` is the two characters `\.`, a regular expression that matches a real dot (a bare `.` would match any character). The `-1` means: keep empty pieces at the end. Without it, `a.b.` would silently lose its last empty label.
- The final `return` checks the labels. The `?` and `:` choose between a success holding the new name and an error that holds the reason plus the text as typed.

```java
  /**
   * Says whether this name equals the other name or lies below it in the tree.
   *
   * @param ancestor the possible ancestor
   * @return true for {@code book.seatwave.example} against {@code seatwave.example}
   */
  public boolean isAtOrBelow(DomainName ancestor) {
    Objects.requireNonNull(ancestor, "ancestor");
    int extra = labels.size() - ancestor.labels.size();
    return extra >= 0 && labels.subList(extra, labels.size()).equals(ancestor.labels);
  }
```

- `isAtOrBelow` answers: is this name the same as `ancestor`, or inside it? `book.seatwave.example` is below `seatwave.example`. `extra` is how many more labels this name has. If that is negative, this name is shorter and cannot be below. Otherwise the last labels of this name (`subList(extra, size)`) must equal the ancestor's labels. The root, with no labels, is an ancestor of every name.

```java
  /**
   * Returns the names from this one up to the root, this one first and the root last.
   *
   * @return the chain of names, never empty
   */
  public List<DomainName> selfAndAncestors() {
    List<DomainName> chain = new ArrayList<>();
    for (int first = 0; first <= labels.size(); first++) {
      chain.add(new DomainName(labels.subList(first, labels.size())));
    }
    return List.copyOf(chain);
  }
```

- `selfAndAncestors` lists this name and every name above it, ending with the root. For `book.seatwave.example` it gives `book.seatwave.example.`, `seatwave.example.`, `example.` and `.`. The `for` loop moves the start index from 0 up to and including the label count; the last step makes an empty sublist, the root. `Zone` uses it to look for delegation points.

```java
  /**
   * Returns the length of this name on the wire, without compression.
   *
   * @return one length octet per label plus its octets, plus the final zero octet
   */
  public int wireLength() {
    int length = 1;
    for (String label : labels) {
      length += 1 + label.getBytes(StandardCharsets.US_ASCII).length;
    }
    return length;
  }
```

- `wireLength` counts the bytes the name takes on the wire without compression: one length byte for every label plus the label's bytes, plus the final zero byte (which is the starting `1`). `getBytes(StandardCharsets.US_ASCII)` turns the text into bytes; labels are ASCII only, so one character is one byte.

```java
  /**
   * Writes the name as text, always absolute, with the trailing dot.
   *
   * @return for example {@code seatwave.example.}, or {@code .} for the root
   */
  @Override
  public String toString() {
    return labels.isEmpty() ? "." : String.join(".", labels) + ".";
  }
```

- `toString` writes the name as people write it, always with the final dot. `@Override` tells the compiler that this method replaces one from a parent class (`Record`), and fails the build if the name or signature is wrong. `String.join(".", labels)` glues the labels with dots.

```java
  // Package-private so the wire decoder checks labels with exactly the same rules.
  static String problemWith(List<String> labels) {
    int length = 1;
    for (String label : labels) {
      if (label.isEmpty()) {
        return "a name must not have an empty label";
      }
      if (label.length() > MAX_LABEL_OCTETS) {
        return "a label is longer than " + MAX_LABEL_OCTETS + " octets";
      }
      if (!LABEL.matcher(label).matches()) {
        return "a label may hold only lower-case letters, digits, hyphens and underscores";
      }
      length += 1 + label.length();
    }
    if (length > MAX_NAME_OCTETS) {
      return "a name is longer than " + MAX_NAME_OCTETS + " octets";
    }
    return "";
  }
}
```

- `problemWith` has package-private visibility (no keyword): only classes in this package can call it. The comment says why: the wire decoder in `MessageCodec` checks the labels it reads with exactly the same rules.
- `length` starts at 1 for the final zero byte. For each label: an empty label is refused; a label over 63 is refused; a label that does not match the regular expression is refused (`matches()` demands the whole text match, not a part). Then `1 + label.length()` is added.
- After the loop, a total over 255 is refused. The method returns `""` for no problem, so the caller tests `isEmpty()`.

### `message/RecordType.java`

```java
package io.plainround.seatwave.dns.message;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The record types this lab serves, with their type codes from RFC 1035 section 3.2.2 (A, NS,
 * CNAME, SOA, TXT) and RFC 3596 (AAAA). A query for any other type is answered, but never with
 * data.
 */
```

- The imports bring in `Arrays` (to make a stream from an array), `Locale` and `Optional` (a box that holds one value or nothing).
- RFC 1035 section 3.2.2 gives the numeric codes for A, NS, CNAME, SOA and TXT; RFC 3596 gives AAAA. A query for any other type is still answered, but never with data.

```java
public enum RecordType {
  /** An IPv4 address. */
  A(1),
  /** A name server for the zone that starts at the owner name. */
  NS(2),
  /** An alias: the owner name is another name for the target. */
  CNAME(5),
  /** The start of a zone's authority, with the timers that include the negative-caching TTL. */
  SOA(6),
  /** Free text, used for ownership proofs and notes. */
  TXT(16),
  /** An IPv6 address. */
  AAAA(28);

  private final int code;

  RecordType(int code) {
    this.code = code;
  }

```

- `public enum RecordType` declares an **enum**: a type with a fixed set of named values. Each value here carries a number: `A(1)` calls the constructor with 1.
- The Javadoc line above each value explains it. A is an IPv4 address; NS names a name server for the zone that starts at the owner name; CNAME says the owner name is another name for the target; SOA is the start of authority of a zone; TXT is free text; AAAA is an IPv6 address.
- The constructor `RecordType(int code)` is called once per value when the class loads. `code()` returns the number.

```java
  /**
   * Returns the type code on the wire.
   *
   * @return for example 1 for A
   */
  public int code() {
    return code;
  }

  /**
   * Finds a type by its wire code.
   *
   * @param code the code
   * @return the type, or empty if this lab does not serve it
   */
  public static Optional<RecordType> fromCode(int code) {
    return Arrays.stream(values()).filter(type -> type.code == code).findFirst();
  }

  /**
   * Finds a type by its name in a zone file, in any case.
   *
   * @param name for example {@code aaaa}
   * @return the type, or empty if this lab does not serve it
   */
  public static Optional<RecordType> fromName(String name) {
    return Arrays.stream(values())
        .filter(type -> type.name().equals(name.toUpperCase(Locale.ROOT)))
        .findFirst();
  }
}
```

- `fromCode` finds the type for a number from the wire. `values()` returns all enum values; `Arrays.stream(...)` makes a stream (a pipeline of steps) of them; `filter` keeps the one whose code matches; `findFirst()` returns an `Optional`: the type, or empty if none matched. The lambda `type -> type.code == code` is a small function written inline.
- `fromName` does the same for the text in a zone file (`A`, `aaaa`). It upper-cases the text with `Locale.ROOT` and compares it with the enum constant's name. Returning `Optional` forces the caller to handle "this lab does not serve that type".

### `message/ResponseCode.java`

```java
package io.plainround.seatwave.dns.message;

import java.util.Arrays;
import java.util.Optional;

/** The response codes this lab sends, from RFC 1035 section 4.1.1, with dig's names in comments. */
public enum ResponseCode {
  /** NOERROR: the name exists; the answer may still be empty (no data of that type). */
  NO_ERROR(0),
  /** FORMERR: the query could not be read. */
  FORMAT_ERROR(1),
  /** SERVFAIL: the server could not answer. */
  SERVER_FAILURE(2),
  /** NXDOMAIN: the name does not exist in the zone. */
  NAME_ERROR(3),
  /** NOTIMP: the kind of query is not supported. */
  NOT_IMPLEMENTED(4),
  /** REFUSED: the server will not answer, here because the name is outside its zones. */
  REFUSED(5);
```

- RFC 1035 section 4.1.1 defines the response codes: the four-bit number in the last bits of the flags. `NO_ERROR` (0, dig prints NOERROR) means the name exists. `FORMAT_ERROR` (1, FORMERR) means the query could not be read. `SERVER_FAILURE` (2, SERVFAIL). `NAME_ERROR` (3, NXDOMAIN) means the name does not exist. `NOT_IMPLEMENTED` (4, NOTIMP) means the kind of query is not supported. `REFUSED` (5) means the server will not answer; here, because the name is outside its zones.

```java
  private final int code;

  ResponseCode(int code) {
    this.code = code;
  }

  /**
   * Returns the code on the wire.
   *
   * @return 0 to 5
   */
  public int code() {
    return code;
  }

  /**
   * Finds a response code by its wire value.
   *
   * @param code the four-bit value
   * @return the response code, or empty for a code this lab never sends
   */
  public static Optional<ResponseCode> fromCode(int code) {
    return Arrays.stream(values()).filter(value -> value.code == code).findFirst();
  }
}
```

- The same pattern as `RecordType`: a private `code` field, the constructor, `code()`, and `fromCode`, which returns an empty `Optional` for a code this lab never sends (a real server can send others, so a resolver that reads one must cope).

### `message/RecordData.java`

```java
package io.plainround.seatwave.dns.message;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** The data part of a record; one variant per family of record types this lab serves. */
public sealed interface RecordData
    permits RecordData.Address, RecordData.Host, RecordData.Authority, RecordData.Text {
```

- `public sealed interface RecordData permits ...` declares a **sealed interface**: an interface whose implementing types are all listed in the `permits` clause. Here the data of a record is one of exactly four kinds: `Address`, `Host`, `Authority` or `Text`. Because the list is closed, a `switch` over a `RecordData` can be checked by the compiler for completeness: if a fifth kind is added, every `switch` that forgot it stops compiling. Sealed interfaces need Java 17.
- The four types are written inside the interface. A type nested in an interface is implicitly `public static`.

```java
  /**
   * The data of an A record (an IPv4 address) or an AAAA record (an IPv6 address).
   *
   * @param address the address
   */
  record Address(InetAddress address) implements RecordData {

    /** Checks the field. */
    public Address {
      Objects.requireNonNull(address, "address");
    }
  }
```

- `record Address(InetAddress address)` holds the data of an A or AAAA record. `InetAddress` is the JDK class for an IP address; its subclasses `Inet4Address` and `Inet6Address` are the two families.
- `implements RecordData` joins the sealed family.
- The compact constructor `public Address { ... }` only checks that the address is not null. `Objects.requireNonNull(x, "name")` throws `NullPointerException` with the name if `x` is null.

```java
  /**
   * The data of an NS record (a name server) or a CNAME record (the name this one aliases).
   *
   * @param target the name pointed at
   */
  record Host(DomainName target) implements RecordData {

    /** Checks the field. */
    public Host {
      Objects.requireNonNull(target, "target");
    }
  }
```

- `Host` holds a name: the name server of an NS record or the target of a CNAME record.

```java
  /**
   * The data of an SOA record (RFC 1035 section 3.3.13). The last field is the TTL of negative
   * answers (RFC 2308 section 4).
   *
   * @param primaryServer the zone's primary name server
   * @param responsibleMailbox the mailbox of the person responsible, written as a name
   * @param serial the version of the zone, raised on every change
   * @param refreshSeconds how often secondary servers check the serial
   * @param retrySeconds how soon a secondary retries a failed check
   * @param expireSeconds how long a secondary keeps answering without reaching the primary
   * @param minimumSeconds the TTL for negative answers
   */
  record Authority(
      DomainName primaryServer,
      DomainName responsibleMailbox,
      long serial,
      long refreshSeconds,
      long retrySeconds,
      long expireSeconds,
      long minimumSeconds)
      implements RecordData {

    // Every SOA number is an unsigned 32-bit value on the wire.
    private static final long MAX_UNSIGNED_32 = 0xFFFF_FFFFL;
```

- `Authority` holds the seven fields of an SOA record (RFC 1035 section 3.3.13): the primary server, the mailbox of the person responsible (written as a name, with a dot where the `@` would be), the serial number (a version that rises on every change), and four timers: refresh, retry, expire and the last one, which is the TTL for negative answers (RFC 2308 section 4). TTL means time to live, the number of seconds a cache may keep an answer.
- The record header lists the components on separate lines and ends with `implements RecordData`.
- The comment says that every SOA number is an unsigned 32-bit value on the wire. Java has no unsigned 32-bit type, so the numbers are `long`s that must stay between 0 and 4,294,967,295 (`0xFFFF_FFFFL`). The underscore inside a number literal is only for reading. The `L` makes it a `long`.

```java
    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException if a number does not fit in 32 unsigned bits
     */
    public Authority {
      Objects.requireNonNull(primaryServer, "primaryServer");
      Objects.requireNonNull(responsibleMailbox, "responsibleMailbox");
      for (long value :
          List.of(serial, refreshSeconds, retrySeconds, expireSeconds, minimumSeconds)) {
        if (value < 0 || value > MAX_UNSIGNED_32) {
          throw new IllegalArgumentException("an SOA number must fit in 32 unsigned bits");
        }
      }
    }
  }
```

- The compact constructor checks the two names for null, then loops over the five numbers. `List.of(a, b, c, d, e)` is a quick list to loop over. `for (long value : list)` is the **enhanced for loop**: it takes each element in turn. A number out of range stops with `IllegalArgumentException`.

```java
  /**
   * The data of a TXT record: one or more strings of at most 255 octets each.
   *
   * @param strings the strings, in order
   */
  record Text(List<String> strings) implements RecordData {

    // Each string is sent after a one-octet length (RFC 1035 section 3.3.14).
    private static final int MAX_STRING_OCTETS = 255;

    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException if there is no string or one is too long
     */
    public Text {
      strings = List.copyOf(strings);
      if (strings.isEmpty()) {
        throw new IllegalArgumentException("a TXT record needs at least one string");
      }
      for (String text : strings) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_OCTETS) {
          throw new IllegalArgumentException("a TXT string is longer than 255 octets");
        }
      }
    }
  }
}
```

- `Text` holds the strings of a TXT record. Each string is sent after a one-byte length (RFC 1035 section 3.3.14), so it can be at most 255 bytes.
- The constructor copies the list, refuses an empty list (a TXT record needs at least one string), and refuses any string whose UTF-8 encoding is over 255 bytes. UTF-8 is the common way to write text as bytes; a letter with an accent takes two bytes, so the byte length is what counts, not the character count.

### `message/ResourceRecord.java`

```java
package io.plainround.seatwave.dns.message;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Objects;

/**
 * One resource record: an owner name, a type, a time to live and the data. The class is always IN
 * (the internet), the only class this lab serves.
 *
 * @param owner the name the record belongs to
 * @param type the record type
 * @param ttlSeconds how long a cache may keep the record, 0 to 2,147,483,647 seconds (RFC 2181
 *     section 8)
 * @param data the data, whose variant must match the type
 */
public record ResourceRecord(DomainName owner, RecordType type, long ttlSeconds, RecordData data) {
```

- A **resource record** is one fact in DNS: an owner name, a type, a time to live and the data. The class is always IN (the internet), the only class this lab serves, so it is not stored.
- The imports `Inet4Address` and `Inet6Address` are the two kinds of IP address.
- `ttlSeconds` is a `long`, not an `int`, because the largest TTL allowed (2,147,483,647, which is 2 to the power 31 minus 1, from RFC 2181 section 8) just fits in an `int`, but the wire form is unsigned 32-bit and the decoder reads it as a `long` first.

```java
  /** The largest TTL a record may carry (RFC 2181 section 8): 2^31 - 1 seconds. */
  public static final long MAX_TTL_SECONDS = Integer.MAX_VALUE;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the TTL is out of range or the data does not fit the type
   */
  public ResourceRecord {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(data, "data");
    if (ttlSeconds < 0 || ttlSeconds > MAX_TTL_SECONDS) {
      throw new IllegalArgumentException("a TTL must be from 0 to 2147483647 seconds");
    }
    boolean isMatching =
        switch (type) {
          case A ->
              data instanceof RecordData.Address(var address) && address instanceof Inet4Address;
          case AAAA ->
              data instanceof RecordData.Address(var address) && address instanceof Inet6Address;
          case NS, CNAME -> data instanceof RecordData.Host;
          case SOA -> data instanceof RecordData.Authority;
          case TXT -> data instanceof RecordData.Text;
        };
    if (!isMatching) {
      throw new IllegalArgumentException("the data does not match the record type " + type);
    }
  }
```

- `MAX_TTL_SECONDS` is `Integer.MAX_VALUE`, 2,147,483,647.
- The compact constructor checks: no nulls; the TTL between 0 and the maximum; and the **data matches the type**.
- The match is a **`switch` expression** over the type. Each `case X -> value` gives a value, and the whole switch is assigned to `isMatching`. An `A` record needs `RecordData.Address` holding an `Inet4Address`; AAAA needs an `Inet6Address`; NS and CNAME need `Host`; SOA needs `Authority`; TXT needs `Text`. Java checks that every enum value has a case, so no `default` is needed.
- `data instanceof RecordData.Address(var address) && address instanceof Inet4Address` uses a **record pattern**: it tests that `data` is an `Address` and, if so, takes the component into the new variable `address` (`var` lets the compiler work out the type). The `&&` then tests the family. Record patterns in `instanceof` need Java 21.
- If the check fails, the constructor throws `IllegalArgumentException`. So a record with the wrong kind of data can never exist.

```java
  /**
   * Returns the same record with another TTL.
   *
   * @param newTtlSeconds the new TTL
   * @return the changed copy
   */
  public ResourceRecord withTtl(long newTtlSeconds) {
    return new ResourceRecord(owner, type, newTtlSeconds, data);
  }
```

- `withTtl` returns a copy with another TTL. Records are immutable, so a change always means making a new one. The migration guard uses it.

```java
  /**
   * Writes the record the way a zone file and dig write it.
   *
   * @return for example {@code seatwave.example. 3600 IN A 192.0.2.10}
   */
  @Override
  public String toString() {
    String text =
        switch (data) {
          case RecordData.Address(var address) -> address.getHostAddress();
          case RecordData.Host(var target) -> target.toString();
          case RecordData.Authority authority ->
              String.join(
                  " ",
                  authority.primaryServer().toString(),
                  authority.responsibleMailbox().toString(),
                  Long.toString(authority.serial()),
                  Long.toString(authority.refreshSeconds()),
                  Long.toString(authority.retrySeconds()),
                  Long.toString(authority.expireSeconds()),
                  Long.toString(authority.minimumSeconds()));
          case RecordData.Text(var strings) ->
              String.join(" ", strings.stream().map(value -> "\"" + value + "\"").toList());
        };
    return owner + " " + ttlSeconds + " IN " + type + " " + text;
  }
}
```

- `toString` writes the record the way a zone file and `dig` write it: `seatwave.example. 3600 IN A 192.0.2.10`.
- The `switch (data)` here is a **pattern-matching switch** (Java 21) over the sealed `RecordData`. `case RecordData.Address(var address)` takes the component out. `case RecordData.Authority authority` binds the whole object to a name instead. The cases give the text part: the address as text, a name as text, the seven SOA fields joined by spaces, or each TXT string wrapped in quotation marks. There is no `default`, because the interface is sealed and all four kinds are listed.
- `Long.toString(authority.serial())` turns a number into text. `strings.stream().map(value -> "\"" + value + "\"").toList()` wraps each string in quotes and collects the result into a list (`Stream.toList()` is Java 16).

### `message/Question.java`

```java
package io.plainround.seatwave.dns.message;

import java.util.Objects;
import java.util.Optional;

/**
 * The question of a DNS message: a name, a type code and a class code. The codes are kept as
 * numbers, because a query may ask for a type this lab does not serve, and the answer must echo it.
 *
 * @param name the name asked about
 * @param typeCode the record type code, 0 to 65,535
 * @param classCode the class code, 0 to 65,535; 1 is IN, the internet
 */
public record Question(DomainName name, int typeCode, int classCode) {
```

- The question of a DNS message is a name, a type code and a class code. The comment gives the reason the codes are plain numbers and not `RecordType`: a query may ask for a type this lab does not serve (for example MX, code 15), and the answer must repeat that question exactly.

```java
  /** The class code of the internet (RFC 1035 section 3.2.4). */
  public static final int CLASS_INTERNET = 1;

  // Type and class are 16-bit fields on the wire.
  private static final int MAX_CODE = 0xFFFF;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if a code does not fit in 16 bits
   */
  public Question {
    Objects.requireNonNull(name, "name");
    if (typeCode < 0 || typeCode > MAX_CODE || classCode < 0 || classCode > MAX_CODE) {
      throw new IllegalArgumentException("type and class codes must fit in 16 bits");
    }
  }
```

- `CLASS_INTERNET` is 1 (RFC 1035 section 3.2.4), the IN of `dig` output.
- The codes are 16 bits on the wire, so the maximum is 65,535 (`0xFFFF`). The compact constructor refuses a name that is null and a code outside that range.

```java
  /**
   * Builds an internet-class question for a type this lab serves.
   *
   * @param name the name
   * @param type the type
   * @return the question
   */
  public static Question of(DomainName name, RecordType type) {
    return new Question(name, type.code(), CLASS_INTERNET);
  }

  /**
   * Returns the record type asked for, if this lab serves it.
   *
   * @return the type, or empty for any other type code
   */
  public Optional<RecordType> type() {
    return RecordType.fromCode(typeCode);
  }
}
```

- `of` builds a question for a type this lab serves, using the enum's code. `type()` goes the other way and returns an `Optional<RecordType>`: empty for any other code.

### `message/Message.java`

```java
package io.plainround.seatwave.dns.message;

import java.util.List;

/**
 * A DNS message (RFC 1035 section 4.1): a 16-bit identifier, 16 bits of flags, and four lists. A
 * query carries one question; a response repeats it and adds answers, authority records (name
 * servers or the SOA) and additional records (addresses of those name servers, called glue).
 *
 * @param id the identifier that pairs a response with its query
 * @param flags the flags word: query or response, opcode, authoritative answer, truncated,
 *     recursion desired and available, and the response code
 * @param questions the questions, usually exactly one (RFC 9619)
 * @param answers the answer records
 * @param authority the authority records
 * @param additional the additional records
 */
public record Message(
    int id,
    int flags,
    List<Question> questions,
    List<ResourceRecord> answers,
    List<ResourceRecord> authority,
    List<ResourceRecord> additional) {
```

- A **message** is a 16-bit identifier, 16 bits of flags, and four lists: the questions, the answers, the authority records (name servers, or the SOA) and the additional records (the addresses of those name servers, called **glue**).
- A query has one question. RFC 9619 says that in practice a message carries exactly one question, so the lab treats exactly one as the rule.

```java
  /** Set in every response, clear in every query. */
  public static final int RESPONSE_FLAG = 0x8000;

  /** Set when the answer comes from a server authoritative for the name. */
  public static final int AUTHORITATIVE_FLAG = 0x0400;

  /** Set when the response did not fit and was cut short. */
  public static final int TRUNCATED_FLAG = 0x0200;

  /** Set by a client that wants the server to resolve the name fully on its behalf. */
  public static final int RECURSION_DESIRED_FLAG = 0x0100;

  // The opcode is bits 11 to 14 of the flags word; the response code is the lowest four bits.
  private static final int OPCODE_SHIFT = 11;
  private static final int OPCODE_MASK = 0xF;
  private static final int RESPONSE_CODE_MASK = 0xF;
  private static final int MAX_ID = 0xFFFF;
```

- The flags word is 16 bits. The constants are bit masks: `0x8000` is the top bit, QR (query or response); `0x0400` is AA (authoritative answer); `0x0200` is TC (truncated); `0x0100` is RD (recursion desired). The prefix `0x` means hexadecimal, base 16.
- The comment says where the other two fields sit: the **opcode** is bits 11 to 14 (a four-bit number, 0 for a standard query), and the **response code** is the lowest four bits.
- `OPCODE_SHIFT` is 11 (how far to move the bits right), and the masks `0xF` keep four bits. `MAX_ID` is 65,535.

```java
  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the identifier or the flags do not fit in 16 bits
   */
  public Message {
    if (id < 0 || id > MAX_ID || flags < 0 || flags > MAX_ID) {
      throw new IllegalArgumentException("the identifier and the flags must fit in 16 bits");
    }
    questions = List.copyOf(questions);
    answers = List.copyOf(answers);
    authority = List.copyOf(authority);
    additional = List.copyOf(additional);
  }

  /**
   * Builds a standard query with one question and recursion not desired, which is what a client
   * sends to an authoritative server.
   *
   * @param id the identifier, which should be random (RFC 5452)
   * @param question the question
   * @return the query
   */
  public static Message query(int id, Question question) {
    return new Message(id, 0, List.of(question), List.of(), List.of(), List.of());
  }
```

- The compact constructor refuses an identifier or flags that do not fit in 16 bits, and replaces each list with an unmodifiable copy.
- `query` builds a standard query: flags `0` means QR clear (a query), opcode 0, and recursion **not** desired, which is what a client sends to an authoritative server. `List.of()` is an empty list.

```java
  /**
   * Says whether this message is a response.
   *
   * @return true if the response flag is set
   */
  public boolean isResponse() {
    return (flags & RESPONSE_FLAG) != 0;
  }

  /**
   * Says whether the answer came from a server authoritative for the name.
   *
   * @return true if the authoritative-answer flag is set
   */
  public boolean isAuthoritative() {
    return (flags & AUTHORITATIVE_FLAG) != 0;
  }

  /**
   * Returns the opcode; 0 is a standard query.
   *
   * @return the four-bit opcode
   */
  public int opcode() {
    return (flags >> OPCODE_SHIFT) & OPCODE_MASK;
  }

  /**
   * Returns the four-bit response code as a number.
   *
   * @return 0 for no error, 3 for a name that does not exist
   */
  public int responseCodeValue() {
    return flags & RESPONSE_CODE_MASK;
  }
}
```

- `isResponse` tests one bit: `flags & RESPONSE_FLAG` keeps only that bit (`&` is bitwise and); the result is not zero when the bit is set.
- `isAuthoritative` does the same for AA.
- `opcode` shifts the flags right by 11 places so the opcode is at the bottom, then keeps four bits. `>>` is the right shift.
- `responseCodeValue` keeps the lowest four bits.

### `message/MessageProblem.java`

```java
package io.plainround.seatwave.dns.message;

/** Why a packet is not a well-formed DNS message. Every variant is an expected failure of input. */
public sealed interface MessageProblem {

  /**
   * Says what is wrong, in one line.
   *
   * @return the reason
   */
  String describe();

  /**
   * The packet ended before a part that must be there.
   *
   * @param part the part that was cut off
   */
  record Truncated(String part) implements MessageProblem {
    @Override
    public String describe() {
      return "the packet ends inside the " + part;
    }
  }
```

- A sealed interface again, for the ways a packet can be bad. Each variant is a record with a `describe()` method that gives one line of text.
- `Truncated` means the packet ended before a part that must be there; `part` names the part.
- A **nested record** inside an interface is implicitly `public` and `static`, so no keyword is needed.

```java
  /**
   * A name on the wire breaks a rule: a label or the name too long, a compression pointer that does
   * not point backward, or a character outside the allow-list.
   *
   * @param reason the rule broken
   */
  record BadName(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad name: " + reason;
    }
  }

  /**
   * The header's counts cannot be right for a packet of this size, or for a query.
   *
   * @param reason the count that is wrong
   */
  record BadCounts(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad counts: " + reason;
    }
  }

  /**
   * A record's data does not have the length its type requires.
   *
   * @param reason the record and the length
   */
  record BadRecord(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad record: " + reason;
    }
  }

  /**
   * Bytes are left over after the last record the header announced.
   *
   * @param count how many bytes are left
   */
  record TrailingBytes(int count) implements MessageProblem {
    @Override
    public String describe() {
      return count + " bytes follow the last record";
    }
  }
}
```

- `BadName`: a name on the wire breaks a rule (a label or the name is too long, a compression pointer does not point backward, or a character is outside the allow-list).
- `BadCounts`: the header's counts cannot be right for a packet of this size.
- `BadRecord`: a record's data does not have the length its type requires.
- `TrailingBytes`: bytes are left over after the last record the header announced.
- Each `describe()` builds a text from the fields.

### `message/MessageCodec.java`

```java
package io.plainround.seatwave.dns.message;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns DNS messages into bytes and bytes into messages, following RFC 1035 section 4.
 *
 * <p>Decoding is the trust boundary of this project: every packet that arrives is checked before
 * anything else reads it. The header must be complete; the four counts must fit in the packet; a
 * name must be at most 255 octets and every label at most 63; a compression pointer must point
 * strictly before the place the name started, and every further pointer strictly before the last
 * one, so a chain of pointers can never loop; each record's data must have the length its type
 * needs; and no byte may follow the last record. Records of types this lab does not serve (for
 * example the EDNS option record that dig adds) are read and skipped.
 *
 * <p>Encoding writes names without compression, which is simpler and always valid. Stateless and
 * thread-safe.
 */
public final class MessageCodec {
```

- Imports: `ByteArrayOutputStream` (a growing buffer of bytes in memory), `InetAddress` and `UnknownHostException` (an address, and the checked exception for a bad one), `StandardCharsets`, the list types, `Locale`, `Objects` and `Optional`.
- The Javadoc sums up the decoding rules; each one is a line of the decoder below. The class is `final` (nobody can extend it) and has only static methods.

```java
  /** The fixed header: identifier, flags and four counts of two octets each. */
  public static final int HEADER_OCTETS = 12;

  // A question is at least a one-octet root name plus type and class; a record at least the
  // root name plus type, class, TTL and data length (RFC 1035 sections 4.1.2 and 4.1.3).
  private static final int MIN_QUESTION_OCTETS = 5;
  private static final int MIN_RECORD_OCTETS = 11;
  private static final int POINTER_BITS = 0xC0;
  private static final int POINTER_OFFSET_MASK = 0x3F;
  private static final int BYTE_MASK = 0xFF;
  private static final int BITS_PER_BYTE = 8;
  private static final int IPV4_OCTETS = 4;
  private static final int IPV6_OCTETS = 16;
  // The five 32-bit numbers at the end of an SOA record.
  private static final int SOA_NUMBER_OCTETS = 20;
  private static final long UNSIGNED_32_MASK = 0xFFFF_FFFFL;

  private MessageCodec() {}
```

- `HEADER_OCTETS` is 12, the size of the fixed header.
- `MIN_QUESTION_OCTETS` is 5: even the smallest question holds a one-byte root name and two 16-bit numbers. `MIN_RECORD_OCTETS` is 11: a root name (1) plus type (2), class (2), TTL (4) and data length (2). These are used to check the header's counts.
- `POINTER_BITS` (`0xC0`, binary `11000000`) marks a compression pointer in the first byte of a name part. `POINTER_OFFSET_MASK` (`0x3F`) keeps the other six bits of that byte. `BYTE_MASK` (`0xFF`) is used to read a Java `byte` (which is signed, -128 to 127) as the number 0 to 255. `BITS_PER_BYTE` is 8.
- `IPV4_OCTETS` is 4 and `IPV6_OCTETS` is 16. An SOA ends in five 32-bit numbers: 20 bytes. `UNSIGNED_32_MASK` keeps the low 32 bits of a `long`.
- `private MessageCodec() {}` is a private constructor: nobody can make an instance.

```java
  /**
   * Reads a packet.
   *
   * @param packet the bytes as they arrived
   * @return the message, or the first problem found
   */
  public static Result<Message, MessageProblem> decode(byte[] packet) {
    Objects.requireNonNull(packet, "packet");
    try {
      return Result.success(new Decoder(packet).message());
    } catch (Malformed malformed) {
      return Result.error(malformed.problem);
    }
  }
```

- `decode` is the trust boundary. It refuses a null packet with `NullPointerException` (a bug in the caller), then runs the decoder. If the decoder throws `Malformed`, the `catch` turns it into `Result.error(malformed.problem)`. Otherwise the message is wrapped in `Result.success`. This is the only place where the private exception is caught, and it never leaves the class.

```java
  /**
   * Writes a message.
   *
   * @param message the message
   * @return its bytes on the wire
   */
  public static byte[] encode(Message message) {
    Objects.requireNonNull(message, "message");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeShort(out, message.id());
    writeShort(out, message.flags());
    writeShort(out, message.questions().size());
    writeShort(out, message.answers().size());
    writeShort(out, message.authority().size());
    writeShort(out, message.additional().size());
    for (Question question : message.questions()) {
      writeName(out, question.name());
      writeShort(out, question.typeCode());
      writeShort(out, question.classCode());
    }
    for (List<ResourceRecord> section :
        List.of(message.answers(), message.authority(), message.additional())) {
      section.forEach(record -> writeRecord(out, record));
    }
    return out.toByteArray();
  }
```

- `encode` writes a message. `ByteArrayOutputStream` collects bytes in memory; `toByteArray()` at the end gives the result.
- The header: the identifier, the flags, and the four counts, each written as a 16-bit number with `writeShort`.
- Each question: the name, the type code, the class code.
- The three record sections are written in order. `for (List<ResourceRecord> section : List.of(answers, authority, additional))` loops over three lists; `section.forEach(record -> writeRecord(out, record))` calls `writeRecord` for each. The sections are written one after the other, in the order the header counted them.

```java
  /**
   * Reads the identifier from the first two octets, so a server can answer even a message it cannot
   * otherwise read.
   *
   * @param packet the bytes, at least two long
   * @return the identifier
   */
  public static int idOf(byte[] packet) {
    return ((packet[0] & BYTE_MASK) << BITS_PER_BYTE) | (packet[1] & BYTE_MASK);
  }
```

- `idOf` reads the identifier from the first two bytes, without decoding the rest. A server needs it to answer even a message it cannot read (a FORMERR reply must carry the same identifier so the client can match it). `(packet[0] & BYTE_MASK) << BITS_PER_BYTE` turns the first byte into the high half, and `| (packet[1] & BYTE_MASK)` puts the second byte in the low half. `&` with `0xFF` is essential: a Java `byte` of `0x84` is the signed number -124, and `& 0xFF` turns it back into 132.

```java
  private static void writeRecord(ByteArrayOutputStream out, ResourceRecord record) {
    writeName(out, record.owner());
    writeShort(out, record.type().code());
    writeShort(out, Question.CLASS_INTERNET);
    writeInt(out, record.ttlSeconds());
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    switch (record.data()) {
      case RecordData.Address(var address) -> data.writeBytes(address.getAddress());
      case RecordData.Host(var target) -> writeName(data, target);
      case RecordData.Authority authority -> {
        writeName(data, authority.primaryServer());
        writeName(data, authority.responsibleMailbox());
        for (long number :
            List.of(
                authority.serial(),
                authority.refreshSeconds(),
                authority.retrySeconds(),
                authority.expireSeconds(),
                authority.minimumSeconds())) {
          writeInt(data, number);
        }
      }
      case RecordData.Text(var strings) -> {
        for (String text : strings) {
          byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
          data.write(bytes.length);
          data.writeBytes(bytes);
        }
      }
    }
    writeShort(out, data.size());
    out.writeBytes(data.toByteArray());
  }
```

- `writeRecord` writes one record: the owner name, the type code, the class (always IN), the TTL as a 32-bit number, then the data.
- The data is written to a second buffer first, so its length is known; the length is written before it (a 16-bit number), then the data bytes.
- The `switch (record.data())` over the sealed `RecordData` has one case per kind and no `default`. An address writes its raw bytes (4 or 16); `address.getAddress()` returns them, and `writeBytes` appends a byte array. A host writes a name. An SOA writes the two names and then the five numbers: the `for` loop over `List.of(...)` writes each as 32 bits. A TXT writes, for each string, a length byte (`data.write(bytes.length)` writes one byte) and the bytes.
- `case RecordData.Address(var address) ->` takes the component out of the record (a record pattern). `case RecordData.Authority authority -> { ... }` uses a block with several statements.

```java
  private static void writeName(ByteArrayOutputStream out, DomainName name) {
    for (String label : name.labels()) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.write(bytes.length);
      out.writeBytes(bytes);
    }
    out.write(0);
  }

  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write((value >> BITS_PER_BYTE) & BYTE_MASK);
    out.write(value & BYTE_MASK);
  }

  private static void writeInt(ByteArrayOutputStream out, long value) {
    writeShort(out, (int) ((value >> (2 * BITS_PER_BYTE)) & 0xFFFF));
    writeShort(out, (int) (value & 0xFFFF));
  }
```

- `writeName` writes each label as a length byte and its bytes, then a zero byte. `out.write(int)` writes one byte (the low eight bits).
- `writeShort` writes a 16-bit number big-endian: first the high byte (`value >> 8`, kept to eight bits with `& 0xFF`), then the low byte.
- `writeInt` writes a 32-bit number as two 16-bit halves. The value is a `long` so it can hold numbers over two billion. `2 * BITS_PER_BYTE` is 16. The casts `(int)` narrow a `long` to an `int`; the mask before it guarantees it fits.

```java
  /**
   * Stops decoding at the first problem. It never leaves this class: {@link #decode} turns it into
   * a {@link Result}, so callers see an expected failure as a value.
   */
  private static final class Malformed extends Exception {
    private static final long serialVersionUID = 1L;

    // Not serialized: the exception never leaves this class.
    private final transient MessageProblem problem;

    Malformed(MessageProblem problem) {
      super(problem.describe(), null, false, false);
      this.problem = problem;
    }
  }
```

- `Malformed` is a private exception class. It is `final` and nested in `MessageCodec`, so it is invisible outside. It extends `Exception`, so it is a **checked exception**: every method that can throw it must say `throws Malformed`, which makes the compiler prove that `decode` catches it.
- `serialVersionUID` is a version number for serialization (an exception can be turned into bytes and back). The compiler warns when a serializable class lacks it, and this build makes every warning an error.
- `transient` on `problem` says this field is not part of serialization; the comment says the exception never leaves the class.
- `super(problem.describe(), null, false, false)` calls the parent constructor with a message, no cause, **no suppression and no stack trace**. Filling in a stack trace is slow, and for an expected failure it is useless, so it is turned off.

```java
  /** Reads one packet from front to back. Confined to one call of {@link #decode}. */
  private static final class Decoder {
    private final byte[] packet;
    private int position;

    Decoder(byte[] packet) {
      this.packet = packet;
    }

    Message message() throws Malformed {
      if (packet.length < HEADER_OCTETS) {
        throw new Malformed(new MessageProblem.Truncated("header"));
      }
      int id = readShort("header");
      int flags = readShort("header");
      int questionCount = readShort("header");
      int answerCount = readShort("header");
      int authorityCount = readShort("header");
      int additionalCount = readShort("header");
      long recordCount = (long) answerCount + authorityCount + additionalCount;
      // Checked before any list is filled, so a header that claims 65,535 records cannot make
      // the decoder do 65,535 rounds of work on a 12-byte packet.
      if (questionCount * (long) MIN_QUESTION_OCTETS + recordCount * MIN_RECORD_OCTETS
          > packet.length - HEADER_OCTETS) {
        throw new Malformed(
            new MessageProblem.BadCounts("the header announces more than the packet holds"));
      }
      List<Question> questions = new ArrayList<>();
      for (int questionIndex = 0; questionIndex < questionCount; questionIndex++) {
        questions.add(new Question(readName(), readShort("question"), readShort("question")));
      }
      List<ResourceRecord> answers = readRecords(answerCount);
      List<ResourceRecord> authority = readRecords(authorityCount);
      List<ResourceRecord> additional = readRecords(additionalCount);
      if (position != packet.length) {
        throw new Malformed(new MessageProblem.TrailingBytes(packet.length - position));
      }
      return new Message(id, flags, questions, answers, authority, additional);
    }
```

- `Decoder` reads one packet from front to back. `position` is the index of the next byte to read.
- `message()` first refuses a packet shorter than 12 bytes. It reads the identifier and the flags and the four counts, each with `readShort`.
- `recordCount` adds the three record counts as a `long`: the cast `(long) answerCount` makes the whole sum use `long`, so there can be no overflow.
- The comment gives the point of the next check: before any list is filled, the decoder checks that the packet is big enough for what the header announces. Questions take at least 5 bytes and records at least 11, so `questionCount * 5 + recordCount * 11` must be at most the number of bytes after the header. Without this, a 12-byte packet that claims 65,535 records could make the decoder do 65,535 rounds of work.
- The questions are read: a name, a type, a class. Java evaluates the arguments of `new Question(...)` left to right, so the three reads happen in the order of the wire.
- The three record sections are read, and then a check that no byte is left over: a packet with extra bytes after the last record is refused.

```java
    private List<ResourceRecord> readRecords(int count) throws Malformed {
      List<ResourceRecord> records = new ArrayList<>();
      for (int recordIndex = 0; recordIndex < count; recordIndex++) {
        readRecord().ifPresent(records::add);
      }
      return records;
    }

    private Optional<ResourceRecord> readRecord() throws Malformed {
      DomainName owner = readName();
      int typeCode = readShort("record");
      int classCode = readShort("record");
      long ttl = readInt("record");
      int dataLength = readShort("record");
      int end = position + dataLength;
      if (end > packet.length) {
        throw new Malformed(new MessageProblem.Truncated("record data"));
      }
      Optional<RecordType> type = RecordType.fromCode(typeCode);
      if (type.isEmpty() || classCode != Question.CLASS_INTERNET) {
        position = end;
        return Optional.empty();
      }
      // A TTL with the top bit set is read as zero (RFC 2181 section 8).
      long ttlSeconds = ttl > ResourceRecord.MAX_TTL_SECONDS ? 0 : ttl;
      RecordData data = readData(type.get(), end);
      if (position != end) {
        throw new Malformed(
            new MessageProblem.BadRecord(type.get() + " data length does not match its content"));
      }
      return Optional.of(new ResourceRecord(owner, type.get(), ttlSeconds, data));
    }
```

- `readRecords` reads `count` records. `readRecord` returns an `Optional`, and `ifPresent(records::add)` adds the record only when it is there. `records::add` is a **method reference**: a short way to write `record -> records.add(record)`.
- `readRecord` reads the owner name, the type, the class, the TTL (32 bits, so `readInt`) and the data length. `end` is where the data must finish. If `end` is beyond the packet, the packet is cut short.
- A record whose type this lab does not serve, or whose class is not IN, is **skipped**: `position = end` jumps past its data and the method returns an empty `Optional`. That is how the EDNS option record that `dig` adds is read and ignored.
- The comment cites RFC 2181 section 8: a TTL with the top bit set must be treated as zero. The test `ttl > MAX_TTL_SECONDS` is true exactly when the top bit of the 32-bit value is set, and then the TTL is 0.
- After `readData`, the position must equal `end`: the data length announced must match what the type consumed. Otherwise: `BadRecord`.

```java
    private RecordData readData(RecordType type, int end) throws Malformed {
      return switch (type) {
        case A -> new RecordData.Address(readAddress(IPV4_OCTETS, end));
        case AAAA -> new RecordData.Address(readAddress(IPV6_OCTETS, end));
        case NS, CNAME -> new RecordData.Host(readName());
        case SOA -> readAuthority(end);
        case TXT -> readText(end);
      };
    }

    private InetAddress readAddress(int octets, int end) throws Malformed {
      if (end - position != octets) {
        throw new Malformed(
            new MessageProblem.BadRecord("an address record must hold " + octets + " octets"));
      }
      byte[] address = new byte[octets];
      System.arraycopy(packet, position, address, 0, octets);
      position = end;
      try {
        // getByAddress never looks anything up; it only checks the length, checked above.
        return InetAddress.getByAddress(address);
      } catch (UnknownHostException wrongLength) {
        throw new Malformed(new MessageProblem.BadRecord(wrongLength.getMessage()));
      }
    }
```

- `readData` is a `switch` expression over the record type: A reads 4 bytes, AAAA reads 16, NS and CNAME read a name (the two labels share one arm: `case NS, CNAME ->`), SOA and TXT have their own methods. No `default`: all six enum values are covered.
- `readAddress` first checks that the data has exactly the right length. It copies the bytes out of the packet with `System.arraycopy(source, from, destination, to, count)`.
- `InetAddress.getByAddress(bytes)` builds an address from raw bytes. The comment says it never looks anything up (unlike `getByName`, which can ask DNS); it only checks the length, which was checked above. It still declares `UnknownHostException`, so the `catch` is required, and it turns the exception into a `BadRecord`.

```java
    private RecordData readAuthority(int end) throws Malformed {
      DomainName primary = readName();
      DomainName mailbox = readName();
      if (end - position != SOA_NUMBER_OCTETS) {
        throw new Malformed(new MessageProblem.BadRecord("an SOA record must end in 20 octets"));
      }
      return new RecordData.Authority(
          primary,
          mailbox,
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"),
          readInt("SOA"));
    }

    private RecordData readText(int end) throws Malformed {
      List<String> strings = new ArrayList<>();
      while (position < end) {
        int length = packet[position] & BYTE_MASK;
        if (position + 1 + length > end) {
          throw new Malformed(new MessageProblem.BadRecord("a TXT string runs past its record"));
        }
        strings.add(new String(packet, position + 1, length, StandardCharsets.UTF_8));
        position += 1 + length;
      }
      if (strings.isEmpty()) {
        throw new Malformed(new MessageProblem.BadRecord("a TXT record needs a string"));
      }
      return new RecordData.Text(strings);
    }
```

- `readAuthority` reads the two names, then insists that exactly 20 bytes are left for the five numbers. Then it builds the `Authority`; the five `readInt("SOA")` calls run in order.
- `readText` reads strings until the end of the record: the first byte is the length; if the string would run past the record, it is a `BadRecord`. `new String(bytes, offset, length, charset)` decodes the bytes as UTF-8 text. A TXT record with no string is refused.

```java
    private DomainName readName() throws Malformed {
      List<String> labels = new ArrayList<>();
      int cursor = position;
      // Every pointer must point strictly before this mark, and the mark moves back with each
      // jump, so a chain of pointers always ends: no packet can make this loop forever.
      int pointerLimit = position;
      boolean hasJumped = false;
      int wireLength = 1;
      while (true) {
        int length = byteAt(cursor);
        if ((length & POINTER_BITS) == POINTER_BITS) {
          int target = ((length & POINTER_OFFSET_MASK) << BITS_PER_BYTE) | byteAt(cursor + 1);
          if (target >= pointerLimit) {
            throw new Malformed(
                new MessageProblem.BadName("a compression pointer must point backward"));
          }
          if (!hasJumped) {
            position = cursor + 2;
            hasJumped = true;
          }
          pointerLimit = target;
          cursor = target;
          continue;
        }
        if ((length & POINTER_BITS) != 0) {
          throw new Malformed(new MessageProblem.BadName("a reserved label type"));
        }
        if (length == 0) {
          if (!hasJumped) {
            position = cursor + 1;
          }
          break;
        }
        wireLength += 1 + length;
        if (wireLength > DomainName.MAX_NAME_OCTETS) {
          throw new Malformed(new MessageProblem.BadName("longer than 255 octets"));
        }
        if (cursor + 1 + length > packet.length) {
          throw new Malformed(new MessageProblem.Truncated("name"));
        }
        labels.add(
            new String(packet, cursor + 1, length, StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT));
        cursor += 1 + length;
      }
      String problem = DomainName.problemWith(labels);
      if (!problem.isEmpty()) {
        throw new Malformed(new MessageProblem.BadName(problem));
      }
      return new DomainName(labels);
    }
```

- `readName` is the most careful method. It builds the label list while `cursor` walks the packet. `position` (where reading continues after the name) is changed only once.
- `pointerLimit` starts at the place the name began. The comment says the rule: every pointer must point strictly before this mark, and the mark moves back with each jump, so a chain of pointers always ends. Each jump goes to a smaller number, and a number cannot get smaller forever, so the loop is finite.
- `hasJumped` remembers that a pointer was followed. After the first jump, `position` was already set to the byte after the two pointer bytes, and later steps must not change it.
- `wireLength` starts at 1 and counts the name's length as it grows (to refuse a name over 255).
- The loop `while (true)` reads the next length byte with `byteAt(cursor)` (which checks that the index is inside the packet). If the top two bits are `11` it is a pointer: the offset is the low six bits of this byte shifted left by 8, plus the next byte (`byteAt(cursor + 1)`). A pointer to the same place or forward (`target >= pointerLimit`) is refused with `BadName`. Otherwise the cursor moves to the target (`continue` goes to the next loop step).
- If only one of the top two bits is set (`01` or `10`), it is a reserved label type: refused.
- A length of 0 ends the name. If no pointer was followed, `position` moves past the zero byte.
- Otherwise it is a label of `length` bytes. The running length is checked against 255 and the label is checked to lie inside the packet. Then the label is decoded as ASCII, converted to lower case and added. `cursor += 1 + length` moves to the next length byte.
- After the loop, the labels are checked with the same rules as `DomainName` (`problemWith`), and a `DomainName` is built. The `DomainName` constructor would check again; the explicit check gives a `BadName` value and not an `IllegalArgumentException`.

```java
    private int byteAt(int index) throws Malformed {
      if (index >= packet.length) {
        throw new Malformed(new MessageProblem.Truncated("name"));
      }
      return packet[index] & BYTE_MASK;
    }

    private int readShort(String part) throws Malformed {
      if (position + 2 > packet.length) {
        throw new Malformed(new MessageProblem.Truncated(part));
      }
      int value =
          ((packet[position] & BYTE_MASK) << BITS_PER_BYTE) | (packet[position + 1] & BYTE_MASK);
      position += 2;
      return value;
    }

    private long readInt(String part) throws Malformed {
      long high = readShort(part);
      long low = readShort(part);
      return ((high << (2 * BITS_PER_BYTE)) | low) & UNSIGNED_32_MASK;
    }
  }
```

- `byteAt` reads one byte as a number 0 to 255, or reports `Truncated("name")` if the index is past the end.
- `readShort` reads two bytes big-endian and advances `position` by 2, or reports a `Truncated` for the named part. `part` is only used in the error text.
- `readInt` reads 32 bits as two 16-bit halves in `long`s, joins them, and masks to 32 bits so the result is never negative.

## Try it yourself

All commands run in the repository folder. Gradle is run through `./gradlew`. Everything stays on the loopback address.

**1. Start the lab servers.** This binds three UDP ports on 127.0.0.1 and stops by itself after 300 seconds.

```text
./gradlew -q serve --args='config/serve.properties'
```

```text
serving on 127.0.0.1 (UDP) for 300 s:
  root      port 5301  zone .                  5 records
  tld       port 5302  zone example.           5 records
  seatwave  port 5303  zone seatwave.example.  11 records
```

**2. Ask for a name that does not exist, in a second terminal.**

```text
dig @127.0.0.1 -p 5303 nothing.seatwave.example A +norecurse
```

```text
;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 32643
;; flags: qr aa; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 0
;; AUTHORITY SECTION:
seatwave.example.	300	IN	SOA	ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300
;; MSG SIZE  rcvd: 141
```

`MSG SIZE rcvd: 141` is the length of the packet the codec wrote. The identifier changes on every run.

**3. Send the raw bytes of a query yourself** and see the answer's bytes. `printf` writes the bytes; `nc -u` sends them as one UDP datagram and waits one second (`-w1`) for a reply; `xxd` prints them in hexadecimal.

```text
printf '\x12\x34\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x08seatwave\x07example\x00\x00\x01\x00\x01' | nc -u -w1 127.0.0.1 5303 | xxd
```

```text
00000000: 1234 8400 0001 0001 0000 0000 0873 6561  .4...........sea
00000010: 7477 6176 6507 6578 616d 706c 6500 0001  twave.example...
00000020: 0001 0873 6561 7477 6176 6507 6578 616d  ...seatwave.exam
00000030: 706c 6500 0001 0001 0000 0e10 0004 c000  ple.............
00000040: 020a                                     ..
```

The first two bytes are your identifier, `84 00` are the flags (response and authoritative), and the last four bytes `c0 00 02 0a` are 192.0.2.10.

**4. Send a packet with a compression pointer that points at itself.** The name starts at byte 12 and its first two bytes are `c0 0c`, a pointer to offset 12, itself.

```text
printf '\x12\x34\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\xc0\x0c\x00\x01\x00\x01' | nc -u -w1 127.0.0.1 5303 | xxd
```

```text
00000000: 1234 8001 0000 0000 0000 0000            .4..........
```

The server did not loop. `MessageCodec.decode` refused the pointer (`target >= pointerLimit`), and the `Responder` answered with the same identifier `1234`, flags `8001` (response, response code 1 = FORMERR) and no sections.

**5. Stop the servers.** They stop by themselves after 300 seconds and print how many packets each answered and dropped. To stop earlier, press Ctrl-C in the first terminal. Nothing else is left running.

## Mistakes people make

- **Reading a Java `byte` as if it were 0 to 255.** A `byte` is signed. Every read here ends with `& BYTE_MASK`.
- **Following a compression pointer without a limit.** A packet can point a name at itself. This decoder only follows pointers that go strictly backward.
- **Trusting the counts in the header.** A tiny packet can claim 65,535 records. The decoder checks the counts against the size of the packet before it reads anything.
- **Using the identifier as the only check on an answer.** A client must also check the question; see `java-resolver-and-jdk-walkthrough.md`.
- **Comparing names with different case.** DNS names are case-insensitive. Here every name is lower-cased once, when it is made.
- **Looking up a name when you only want to build an address.** `InetAddress.getByName` may ask DNS. `getByAddress` and `ofLiteral` never do.

## Check yourself

1. How many bytes does the name `seatwave.example.` take on the wire without compression, and why? (18: a length byte and 8 letters, a length byte and 7 letters, and a zero byte.)
2. What do the top two bits `11` of a name's first byte mean? (A compression pointer: the other 14 bits are an offset in the message where the name goes on.)
3. Why must a pointer point strictly backward? (So that a chain of pointers always ends; a pointer to itself or forward could loop forever.)
4. Why does `decode` return a `Result` and not throw an exception? (A malformed packet is an expected input from the network, not a bug.)
5. Why is the `switch` over `RecordData` without a `default`? (The interface is sealed: all four kinds are listed, and the compiler proves the `switch` covers them.)

## Further reading

- RFC 1035, Domain Names - Implementation and Specification: https://www.rfc-editor.org/rfc/rfc1035
- RFC 2181, Clarifications to the DNS Specification (TTL range, record sets): https://www.rfc-editor.org/rfc/rfc2181
- RFC 2308, Negative Caching of DNS Queries: https://www.rfc-editor.org/rfc/rfc2308
- RFC 3596, DNS Extensions to Support IP Version 6: https://www.rfc-editor.org/rfc/rfc3596
- RFC 4343, Domain Name System (DNS) Case Insensitivity Clarification: https://www.rfc-editor.org/rfc/rfc4343
- RFC 9619, In the DNS, QDCOUNT Is (Usually) One: https://www.rfc-editor.org/rfc/rfc9619
- The Java SE 25 API, `java.net.InetAddress`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
- The Java Language Specification, Java SE 25, records and sealed classes: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
