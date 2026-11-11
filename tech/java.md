# Java 25 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows PC and a Linux server, so the same bytecode runs on all of them.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. A mistake such as passing text where a number is expected stops the build instead of crashing later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration` and `DatagramSocket`. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2, and `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`.

## Why this repository uses it

The repository is the hands-on project of Module 6 of a system design course. It builds an authoritative DNS server, a DNS client with a cache, a weighted-answer test, a TTL-change experiment and a guard for migrations, for a ticket-booking case called SeatWave. Java 25 gives what that work needs with no framework:

- **Networking in the JDK** (`java.net.DatagramSocket` for UDP, `ServerSocket` for the health checks, the resolver interface `java.net.spi`). `java-udp-and-resolver-api.md` explains them.
- **Records, enums and sealed interfaces**: small immutable data types in a few lines, and `switch` statements that the compiler proves complete. A DNS message is data, and these fit it.
- **Pattern matching**: a `switch` that takes a value apart, used on every result.
- **Virtual threads** (Java 21): one cheap thread per health-check listener.
- **A standard library that covers the rest** (time, collections, streams, regular expressions, files), so the project has exactly one runtime library (`result4j.md`).

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/dns/message/` | 9 files: the DNS codec. See `java-message-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/dns/zone/` and `server/` | 5 and 4 files: zones, weights, the server. See `java-zone-and-server-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/dns/resolver/` and `jdk/` | 7 and 3 files: the client, the cache, the JDK plug-in. See `java-resolver-and-jdk-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/dns/migration/` | 6 files: the guard. See `java-migration-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/dns/experiment/`, `sampling/` and `config/` | 10, 5 and 1 files: the experiments and the settings reader. See `java-experiment-sampling-config-walkthrough.md`. |
| `src/test/java/io/plainround/seatwave/dns/` | The tests, also Java. |
| `build.gradle.kts` | Tells Gradle to compile with Java 25 and to treat every warning as an error. |

In all there are 50 source files under `src/main/java`. I ran `./gradlew -q compileJava` in a copy (it printed nothing, which means success) and found 89 class files under `build/classes/java/main/`. That is more than 50 because a nested class, a record or an enum inside a file gets its own class file.

## How it works underneath

**From text to a running program.** You write `DomainName.java`. The compiler reads it and writes `DomainName.class`. Gradle does this for you. To start a program, a JVM process starts, loads the class you name, and calls its `main` method. Each command of this repository has its own `main` (`ServeCli`, `SampleCli`, `TtlExperimentCli`, `TtlLiveCli`, `JdkLookupCli`), and each has a Gradle task of the same short name (`serve`, `sample`, `ttlExperiment`, `ttlLive`, `jdkLookup`).

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns and no other thread is running, the process ends with **exit status** 0, which means success. A program may call `System.exit(n)` to end at once with another number. By convention a non-zero status means failure. This repository uses 1 for "the run failed or found something wrong" and 2 for "the command was used incorrectly".

**Memory.** Two areas matter. The **stack** holds the method calls in progress with their local variables: each call adds a frame and each return removes one. Every thread has its own stack. The **heap** holds objects: `new Question(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. The JVM frees objects that no variable can reach any more. This is **garbage collection**. You never free memory by hand.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often each method runs, and the **JIT compiler** (just-in-time compiler) turns the busy methods into native machine code while the program runs. A method is slow in its first runs and fast after. That is why the sampler makes 1,000 warm-up lookups and throws them away.

**Threads.** A **thread** is one line of execution inside a process. A program may have several, and they run at the same time on different processor cores. A **blocking** call, such as reading from a socket, makes its thread wait until the data arrives. The DNS server has one **platform thread** (a thread of the operating system) that blocks in `receive`. A **virtual thread** is a thread that the JDK manages itself, cheap enough to start thousands.

**The class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task.

**Compile flags.** `build.gradle.kts` sets `options.release = 25` (compile for Java 25 and use only its library) and `-Xlint:all -Werror` (turn on every warning, and make any warning fail the build). So a warning cannot stay.

## Walkthrough of this repository's use

### The basic words

**Class.** A class is a blueprint for objects. It has **fields** (the data) and **methods** (the code). Everything in Java lives in a class. Records, enums and interfaces are special kinds of class.

**Package and import.** A **package** is a named group of classes, written with dots: `io.plainround.seatwave.dns.message`. The name matches the folder path under `src/main/java`. The first line of a file is `package ...;`. After it come the **imports**: each `import java.util.List;` lets the file write `List` instead of the full name `java.util.List`. Classes of the same package need no import.

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

**Visibility, `static` and `final`.** `public` means any class may use it. `private` means only this class. No keyword (**package-private**) means only classes in the same package: `ApprovedChange`'s constructor uses it (see `java-migration-walkthrough.md`). `static` means it belongs to the class, not to an object: you call it as `MessageCodec.decode(bytes)`, with no object. `final` on a field or variable means it can be assigned once. `final` on a class means no one can extend it.

```java
public final class MessageCodec {

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

- `public static final int HEADER_OCTETS = 12;` is a constant.
- `private MessageCodec() {}` is a **constructor**: code that runs when an object is made. It is private and empty, so nobody can write `new MessageCodec()`. The class is a bag of static methods.

**Method.** A method is a named piece of code with parameters and a return type. `void` means it returns nothing.

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

**The `main` method.** A program starts at `public static void main(String[] arguments)`. The array holds what the user typed after the command name.

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
```

**Control flow.** `if`, `for`, `while` and `switch` choose and repeat. `&&` is "and", `||` is "or", `!` is "not". A `for` loop has three parts: start, test, step.

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

**Operators.** `+=` adds and assigns. `x ? a : b` is the **conditional expression**: `a` if `x` is true, otherwise `b`. `<<` and `>>` shift bits; `&` and `|` combine bits (see `java-udp-and-resolver-api.md`).

```java
  public int opcode() {
    return (flags >> OPCODE_SHIFT) & OPCODE_MASK;
  }
```

### The features this code uses

Each feature below shows a real example from this repository. The **minimum Java version** of each feature is in the table at the end of this section.

#### 1. Records

A **record** declares a class that only holds data. The header lists its **components**; Java makes a private final field for each, an accessor method with the same name, a constructor, and `equals`, `hashCode` and `toString`. Two records are equal when all their components are equal.

```java
public record Question(DomainName name, int typeCode, int classCode) {

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

- `Question(DomainName name, int typeCode, int classCode)` is the whole declaration. `question.name()` reads the first component.
- The block `public Question { ... }` is the **compact constructor**. It has no parameter list. It runs before the fields are set and checks them, so a record that breaks a rule cannot exist. This is how the repository enforces "an invalid name, record or zone cannot exist".
- A record can have `static` fields and methods, such as `CLASS_INTERNET` and `of`.

Records nested in other types: `RecordData.Address`, `ServeSettings.Listener`, `LiveMigration.Client`. A record is implicitly `final`, so it cannot be extended.

#### 2. Enums

An **enum** is a type with a fixed set of named values. Each value can carry data and the enum can have methods.

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

- `A(1)` creates the value `A` by calling the constructor with 1. `RecordType.A.code()` is 1. `values()` returns all values in order; `name()` returns the text `"A"`.
- Enums are used for the record types, the response codes, `Resolution.Source` and `SampleSettings.Target`.

#### 3. Sealed interfaces

A **sealed** interface lists exactly which types may implement it. The compiler then knows every possibility.

```java
public sealed interface RecordData
    permits RecordData.Address, RecordData.Host, RecordData.Authority, RecordData.Text {
```

- `permits` lists the four implementing records. No other class can implement `RecordData`. The other sealed interfaces are `MessageProblem`, `Lookup`, `LookupProblem` and `MigrationRefusal` (the types listed after `permits` can be omitted when they are all in the same file, as in those four).

#### 4. `switch` expressions, statements and patterns

A `switch` chooses one branch by the value of an expression. The newer form uses arrows and, as an **expression**, produces a value.

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
```

- `case NS, CNAME ->` has two labels for one branch.
- There is no `default`. The compiler checks that every enum constant is covered, and fails if one is missing (see "Try it yourself").

**Pattern matching** lets a `switch` or `instanceof` test the type of a value and take it apart in one step. A **record pattern** such as `RecordData.Address(var address)` tests that the value is an `Address` and puts its component in `address`.

```java
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
```

- `var` lets the compiler work out the type of `address` and `strings`.
- `case RecordData.Authority authority ->` binds the whole object to a name.
- The branches give the text part of a record, and `return` joins it to the owner, TTL and type.

A `switch` over the `Result` type (see `result4j.md`) uses **nested** patterns and `yield`:

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

- `Result.Error<Instant, MigrationRefusal>(MigrationRefusal.TooEarly(var earliest, var why))` is a pattern inside a pattern. The branch runs only for an error whose reason is a `TooEarly`.
- Branches are tried in order; the more general error case is last.

`yield` gives the value of a block in a `switch` expression:

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

`instanceof` with a pattern tests and unpacks (Java 16, with record patterns in Java 21):

```java
  private static Optional<InetAddress> firstAddress(
      Result<Message, LookupProblem> response, RecordType type) {
    if (!(response instanceof Result.Success<Message, LookupProblem>(Message message))) {
      return Optional.empty();
    }
```

- With the `!` in front, the `if` returns early unless the result is a success. Below the `if`, the variable `message` is usable: Java calls this **flow scoping**.

#### 5. Generics

**Generics** let one class or method work with many types, with the compiler checking them. `List<String>` is a list of text. `Result<Message, MessageProblem>` has two type parameters: the type of the good value and the type of the error.

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

- `<E extends Enum<E>> List<E> enumList(String key, Class<E> type)` is a **generic method**. `E extends Enum<E>` means "any enum type". The caller passes the enum class (`RecordType.class`), and gets a list of that enum back.
- `Map<DomainName, Map<InetAddress, Integer>>` in `Weights` is a map of maps.
- `new ArrayList<>()` uses the **diamond** `<>`: the compiler fills in the type from the left side.

#### 6. Lambdas and method references

A **lambda** is a small function written inline: `record -> record.type() == type`. A **method reference** is a shorter name for a lambda that only calls one method: `records::add` means `record -> records.add(record)`. A lambda can be used wherever a **functional interface** (an interface with one abstract method) is expected.

```java
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

- `@FunctionalInterface` asks the compiler to check that `Upstream` has exactly one abstract method. `Consumer<String>`, `Supplier<String>`, `Predicate<InetAddress>` and `Runnable` are JDK functional interfaces used in `LiveMigration` and `DnsServer`.

```java
      LiveMigration.Parts parts =
          new LiveMigration.Parts(
              store, health, guard, site -> sites.isHealthy(site, settings.healthTimeout()));
      List<LiveMigration.Client> clients = clients(settings, server.port(), clock, sockets);
      System.out.print(header(settings, guard.oldTtlWindowSeconds(settings.name())));
      Map<String, Double> sawNew =
          new LiveMigration(settings, parts, clock, Thread::sleep)
              .run(clients, System.out::println);
```

- `site -> sites.isHealthy(site, settings.healthTimeout())` is a lambda; `Thread::sleep` and `System.out::println` are method references.

#### 7. Streams and `Optional`

A **stream** is a pipeline over a sequence of values: a source, some steps (`filter`, `map`, `sorted`), and an end (`toList`, `findFirst`, `anyMatch`, `count`). It does not change the source.

```java
  public List<ResourceRecord> recordsAt(DomainName owner, RecordType type) {
    return records.stream()
        .filter(record -> record.owner().equals(owner) && record.type() == type)
        .toList();
  }

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
```

- `records.stream().filter(...).toList()` keeps the records of one owner and type. `flatMap` turns each element into zero or more elements; here an address record gives one address and anything else gives none.

An **`Optional<T>`** is a box that holds one value or nothing. It makes "there may be no result" visible in the type. The repository uses it for results that can be absent (`RecordType.fromCode`, `Question.type()`), and not as a parameter or a field.

```java
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
```

- `findFirst()` returns an `Optional`. `ifPresent`, `map`, `flatMap`, `or`, `orElse` and `isEmpty` are used throughout `ZoneFile` and `MigrationGuard`.

#### 8. Immutable collections

An **immutable** (unmodifiable) collection cannot be changed after it is made. `List.of(...)`, `Map.of()`, `List.copyOf(list)` and `Map.copyOf(map)` make them, and refuse `null`. A record that holds a list copies it in its constructor, so nobody can change the record through a list they still hold.

```java
public record Publication(Zone zone, Instant from, String description) {

  /** Checks the fields. */
  public Publication {
    Objects.requireNonNull(zone, "zone");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(description, "description");
  }
}
```

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
```

- `List.copyOf` is called on all four lists of a message.
- Java 21 added **sequenced** collections: `list.getFirst()`, `list.addFirst(x)` and `getLast()`. `Zone` uses `getFirst()` and `addFirst(...)`.

#### 9. Exceptions

An **exception** is an object thrown when something goes wrong. It stops the current method and travels up the call stack until a `catch` handles it. There are two kinds. A **checked** exception (a subclass of `Exception` but not of `RuntimeException`, such as `IOException`) must be caught or declared with `throws`. An **unchecked** exception (a subclass of `RuntimeException`, such as `IllegalArgumentException` and `NullPointerException`) needs no declaration and usually means a bug.

This repository follows one rule: **an expected failure is a value, a bug is an exception**. A packet from the network, a line of a zone file and a key of a settings file can be wrong, so they return a `Result`. A null argument or a constructor given a forbidden value is a bug and throws.

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

- `Malformed` is a private **checked** exception used inside the decoder to stop quickly. `decode` catches it and returns a `Result`. `super(message, null, false, false)` turns off the stack trace and suppression: an expected failure should be cheap.
- `serialVersionUID` and `transient` are required by the build's warnings, because exceptions can be serialized.

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

- When a thread is interrupted while waiting, the code restores the **interrupt flag** with `Thread.currentThread().interrupt()` so callers can see it.

#### 10. try-with-resources and `AutoCloseable`

A **resource** is something that must be closed: a socket, a file, a thread. A class that implements `AutoCloseable` has a `close()` method, and a `try (...)` block closes the resource when the block ends, whether it ends normally or with an exception. With several resources, they are closed in reverse order.

```java
    try (SiteEndpoints sites = SiteEndpoints.start(sitePorts);
        DnsServer server =
            DnsServer.start(
                settings.serverPort(), store, new SplittableRandom(settings.randomSeed()))) {
```

- `SiteEndpoints` and `DnsServer` both implement `AutoCloseable`. The block ends, and the server closes first, then the sites.

```java
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

- A file reader in a `try (...)`. Without it, a failure while reading could leave the file open.

#### 11. Strings and formatting

A `String` is text and cannot be changed. `+` joins texts. `strip()` removes spaces at both ends, `split(regex, -1)` cuts a text, `lines()` cuts it into lines, `String.join(" ", list)` glues a list. `StringBuilder` builds a long text piece by piece.

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

- `String.format(Locale.ROOT, "%-16s %6d %12.2f", ...)` fills markers with values. `%s` is text, `%d` a whole number, `%f` a decimal number, `%n` a line break, `%%` a percent sign. A number after `%` is a width; `-` aligns left; `.2` is two digits after the point; `+` always shows a sign.
- `Locale.ROOT` makes number formatting identical on every computer. Without it, a computer set to German would print a comma for the decimal point.

#### 12. Numbers

`int` is a 32-bit whole number (about plus or minus two billion). `long` is 64-bit; a literal needs `L`: `0xFFFF_FFFFL`. `double` is a decimal number. Underscores in a literal are for reading. `0x` starts a hexadecimal number (base 16). `byte` is signed.

```java
  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write((value >> BITS_PER_BYTE) & BYTE_MASK);
    out.write(value & BYTE_MASK);
  }

  private static void writeInt(ByteArrayOutputStream out, long value) {
    writeShort(out, (int) ((value >> (2 * BITS_PER_BYTE)) & 0xFFFF));
    writeShort(out, (int) (value & 0xFFFF));
  }
```

- `value >> 8` shifts the bits right by 8, `& 0xFF` keeps the low eight bits. Casts like `(int)` narrow a number; the code checks the range first.
- Dividing two `int`s drops the fraction (`7 / 2` is 3), so statistics convert first: `(double) hits / lookups`.
- `Math.toIntExact(long)` converts and throws if the number does not fit; `Math.min`, `Math.max` and `Math.ceil` do what they say.

#### 13. Time: `Duration`, `Instant`, `Clock` and `System.nanoTime`

`Duration` is a length of time. `Instant` is a point on the time line. `Clock` says what "now" is. `System.nanoTime()` is a clock for measuring elapsed time: it never jumps backward when someone changes the time of day.

```java
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

- `Clock` is an abstract class. `SimulatedClock` extends it and moves only when `advanceTo` is called, so a simulation of two days runs in a second and gives the same numbers every time. The caching resolver, the guard and the store take a `Clock` and never ask the system for the time themselves.

#### 14. Regular expressions

A **regular expression** is a pattern for text. `java.util.regex.Pattern` compiles it once; `matcher(text).matches()` tests the whole text.

```java
  // An allow-list, not a deny-list: anything else is refused at the boundary. The underscore is
  // allowed because service labels such as _dmarc use it (RFC 8552).
  private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?");
```

- `[a-z0-9_]` is one character from a set; `*` means any number of the one before; `?` means optional; `( ... )` groups. In a Java string a backslash is written twice: `"\\."` is the regular expression `\.`, a literal dot.

#### 15. Files and paths

`Path` names a file; `Files.readString(path, charset)` reads a whole file as text; `Files.newBufferedReader` reads it in pieces. `java.util.Properties` reads a `.properties` file (see `java-properties.md`).

```java
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
```

- Reading can fail with `IOException`, a checked exception. The code catches it and turns it into a problem line, because a missing file is a mistake in the settings.

#### 16. `@Override`, annotations and nested classes

An **annotation** is a tag starting with `@`. `@Override` asks the compiler to check that the method really replaces one from a parent type. `@FunctionalInterface` was shown above. A class may be declared inside another (**nested**), with `private static final class State { ... }`, to keep a helper out of sight: `ZoneFile.State`, `TtlSimulation.Run`, `LiveMigration.State`, `MessageCodec.Decoder`.

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
```

#### 17. Threads and the small concurrency tools

Threads share memory, so two threads touching the same data can break it. This repository keeps that rare and uses four tools.

- **`Thread.ofPlatform()` and `Thread.ofVirtual()`** (Java 21) build threads: `DnsServer` has one platform thread named `dns-<port>`; `SiteEndpoints` starts one virtual thread per site.
- **`synchronized`** on a method lets one thread at a time run it for a given object: `HealthHistory` and `ZoneStore.apply`.
- **`volatile`** on a field makes every thread see the latest write: `ZoneStore.current` is read on every query without a lock.
- **`AtomicLong`** is a number that many threads can increase safely: the server's `answered` and `dropped` counters.

```java
public final class DnsServer implements AutoCloseable {

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

- A random generator (`SplittableRandom`, `SecureRandom`) is **not** shared between threads. `RandomGenerator` is the interface they implement; `SplittableRandom.split()` gives each server its own generator from one seed.

#### 18. Javadoc

A **Javadoc** comment starts with `/**` and ends with `*/`. It documents the class or method that follows. `@param name` describes a parameter, `@return` the result, `@throws` an exception, `{@code x}` prints code, `{@link Class#method}` makes a link, and `<p>` starts a paragraph. A line starting `//` is an ordinary comment. Every public class, record and record component, and nearly every public method, in this repository has a Javadoc comment (a method that only implements an interface method, such as `describe()`, has just `@Override`), and the comments quote the RFC sections they follow.

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

#### 19. The static helpers used everywhere

- `Objects.requireNonNull(x, "x")` throws `NullPointerException` named `x` if `x` is null, otherwise returns `x`.
- `Arrays.sort`, `Arrays.fill`, `Arrays.copyOf`, `Arrays.copyOfRange` and `Arrays.stream` work on arrays.
- `System.out.printf(...)` prints formatted text; `System.err.println(...)` prints to the error stream; `System.exit(n)` ends the program; `System.getProperty("os.name")` reads a property of the JVM; `System.setProperty(...)` sets one; `Runtime.version()` is the Java version that runs the code.
- `InetAddress.ofLiteral(text)` turns text like `192.0.2.10` into an address and never asks DNS; `InetAddress.getByName(name)` may.

### The minimum Java version of each feature

| Feature | Example in this repository | First final in |
|---|---|---|
| Underscores in numbers; try-with-resources; the diamond `<>` | `0xFFFF_FFFFL`, `DnsServer`, `new ArrayList<>()` | Java 7 |
| Lambdas, method references, streams, `Optional`, `Duration`, `Instant`, `Clock`, `SplittableRandom`, `Map.merge`, `Math.toIntExact` | `CachingResolver`, `Sampler` | Java 8 |
| `List.of`, `Map.of`, `Optional.or`, `Duration.toSeconds`, `Runtime.version()` | many | Java 9 |
| `List.copyOf`, `Map.copyOf` | `Message`, `Weights` | Java 10 |
| `String.strip`, `isBlank`, `lines`, `Files.readString`, `Path.of`, `Optional.isEmpty`, `ByteArrayOutputStream.writeBytes` | `ZoneLoader`, `MessageCodec` | Java 11 |
| Switch expressions (`case X ->`), several labels in one arm, `yield` | `MessageCodec.readData`, `ZoneFile.absoluteName` | Java 14 |
| Records, `instanceof` patterns, `Stream.toList()` | `Question`, `Sampler`, `Zone` | Java 16 |
| Sealed interfaces, `RandomGenerator` | `RecordData`, `Zone.lookup` | Java 17 |
| The resolver interface `java.net.spi.InetAddressResolverProvider` | `LabResolverProvider` | Java 18 |
| `Thread.sleep(Duration)`, `Thread.join(Duration)` | `ServeCli`, `DnsServer.close` | Java 19 |
| Virtual threads, `Thread.ofPlatform()`, `Thread.ofVirtual()` | `SiteEndpoints`, `DnsServer` | Java 21 |
| Pattern matching for `switch`, record patterns | every `Result` `switch` | Java 21 |
| Sequenced collections: `getFirst()`, `addFirst()` | `Zone` | Java 21 |
| `InetAddress.ofLiteral(String)` | `ZoneFile`, `Weights`, `ExperimentSettings` | Java 22 |

I checked the last row in the JDK source: the Javadoc of `ofLiteral` says `@since 22`, and the Javadoc of `Thread.sleep(Duration)` and `Thread.join(Duration)` say `@since 19`. So the code needs **Java 22 at least**, because of `ofLiteral`. It is built with `--release 25`.

**Java 25 features this code does not use.** Java 25 made instance main methods and compact source files (JEP 512), flexible constructor bodies (JEP 513) and scoped values (JEP 506) final. This code uses the classic `public static void main(String[] arguments)`, has no code before `super(...)`, and passes values as arguments. It also uses no text blocks, no unnamed variables (`_`), and no `assert`.

## Try it yourself

All commands run in the repository folder.

**1. Check the Java version.**

```text
java -version
```

```text
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.2+10 (build 25.0.2+10-LTS, mixed mode, sharing)
```

**2. Compile, and count the class files** (in a copy of the repository, because `build/` is shared with other runs):

```text
./gradlew -q compileJava
find build/classes/java/main -name '*.class' | wc -l
find src/main/java -name '*.java' | wc -l
```

The first command printed nothing (success). The next two printed `89` and `50`: nested records and enums have their own class files.

**3. Look inside a record.** `javap` prints the members of a compiled class:

```text
javap -p -cp build/classes/java/main io.plainround.seatwave.dns.message.Question
```

```text
Compiled from "Question.java"
public final class io.plainround.seatwave.dns.message.Question extends java.lang.Record {
  private final io.plainround.seatwave.dns.message.DomainName name;
  private final int typeCode;
  private final int classCode;
  public static final int CLASS_INTERNET;
  private static final int MAX_CODE;
  public io.plainround.seatwave.dns.message.Question(io.plainround.seatwave.dns.message.DomainName, int, int);
  public static io.plainround.seatwave.dns.message.Question of(io.plainround.seatwave.dns.message.DomainName, io.plainround.seatwave.dns.message.RecordType);
  public java.util.Optional<io.plainround.seatwave.dns.message.RecordType> type();
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public io.plainround.seatwave.dns.message.DomainName name();
  public int typeCode();
  public int classCode();
}
```

A record is `final`, extends `java.lang.Record`, has private final fields, and has the generated `toString`, `hashCode`, `equals` and accessors.

**4. See a `switch` that is not complete.** Save this as `Exh.java` in a scratch folder and run `java Exh.java`:

```text
public class Exh {
  enum Color { RED, GREEN, BLUE }
  static int code(Color c) {
    return switch (c) {
      case RED -> 1;
      case GREEN -> 2;
    };
  }
  public static void main(String[] a) { System.out.println(code(Color.RED)); }
}
```

```text
Exh.java:4: error: the switch expression does not cover all possible input values
    return switch (c) {
           ^
1 error
error: compilation failed
```

Add `case BLUE -> 3;` and it compiles. That is why none of the `switch`es over record types, results or sealed interfaces in this repository has a `default`.

**5. Run a command with a wrong call and read the exit status.**

```text
./gradlew -q jdkLookup
```

It prints `usage: jdkLookup <jdk-lookup.properties>` and Gradle then reports that the process finished with a non-zero exit value. The program ended with `System.exit(2)`: used incorrectly.

## Mistakes people make

- **Comparing text with `==`.** `==` compares references. Use `equals`.
- **Catching an exception and doing nothing.** Every `catch` here records, returns or rethrows. An `InterruptedException` restores the interrupt flag.
- **Forgetting that a `long` literal needs `L`.** `0xFFFF_FFFFL`.
- **Integer overflow.** An `int` wraps silently. The code computes in `long` where a sum can be large (the header counts in `MessageCodec`), and uses `Math.toIntExact` where it must fit.
- **Dividing whole numbers and expecting a fraction.** `7 / 2` is `3`.
- **Reading a `byte` as 0 to 255.** It is -128 to 127. Mask with `& 0xFF`.
- **A `switch` with a `default` that hides a new case.** None here has one.
- **Leaving a resource open.** Servers, sockets, clients and files are all in `try (...)` blocks or closed in a `finally`.
- **A default locale in a format.** `String.format` without `Locale.ROOT` can print a comma for a decimal point on some computers.
- **Sharing a random generator between threads.** Each server and each simulation has its own.
- **Using a method newer than the build's release.** `InetAddress.ofLiteral` needs Java 22; `options.release = 25` makes the compiler check every library call against Java 25.

## Check yourself

1. What is the difference between a checked and an unchecked exception, with one example of each from this repository? (A checked one must be caught or declared: `IOException` in `ZoneLoader.load`. An unchecked one needs no declaration and means a bug: `IllegalArgumentException` in `DomainName`.)
2. What does a record's compact constructor do, and why is it used so much here? (It runs before the fields are set and checks them, so a value that breaks a rule cannot exist.)
3. Why does a `switch` over `RecordType` need no `default`? (The enum has exactly six constants and all are covered. If a constant is added, the build stops.)
4. What does `try (SiteEndpoints sites = ...; DnsServer server = ...)` do? (It closes both at the end of the block, even on an error, in reverse order.)
5. What is the lowest Java version this code could run on, and which feature sets it? (Java 22: `InetAddress.ofLiteral`.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- The Java Language Specification, Java SE 25: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- The Java SE 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/index.html
- JDK 25 release notes and the list of JEPs: https://openjdk.org/projects/jdk/25/
- Records (JEP 395): https://openjdk.org/jeps/395
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440
- Virtual threads (JEP 444): https://openjdk.org/jeps/444
- Javadoc: https://docs.oracle.com/en/java/javase/25/javadoc/javadoc.html
