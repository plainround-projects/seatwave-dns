# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (the README of this repository lists the license and the date the version was checked).

## Why this repository uses it

A packet that arrives from the network, a line of a zone file and a key of a settings file are all typed or produced by something outside the program. A person can write `192.0.2.999` as an address. A sender can claim 65,535 records in a 12-byte packet. These are **expected failures**: the program should list them, count them and report them, not die with a stack trace. One run should report every problem, not only the first.

Exceptions are kept for programmer errors: a null argument, a record built with a value its constructor forbids. The line is: if a person, a file or the network can cause it, it is a `Result`; if only a bug can cause it, it is an exception.

The dependency is declared in `gradle/libs.versions.toml`, used in `build.gradle.kts` and pinned in `gradle.lockfile`:

```text
result4j = "1.2.0"
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
implementation(libs.result4j)
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths. It is the only runtime library of the project. The README gives the reason the JDK alone is not enough: the JDK has no success-or-typed-error type, so a bad zone file, a malformed packet or a refused migration step would have to be an exception.

## Where it appears

26 files under `src/main/java` import the library. `SettingsReader` does not: it collects problem lines in a list that callers turn into a `Result`.

| File | What it does with `Result` |
|---|---|
| `message/DomainName.java` | `parse` returns `Result<DomainName, String>`. |
| `message/MessageCodec.java` | `decode` returns `Result<Message, MessageProblem>`: the trust boundary. |
| `zone/Weights.java` | `parse` returns `Result<Weights, List<String>>` and switches over `DomainName.parse`. |
| `zone/ZoneFile.java` | `parse` returns `Result<List<ResourceRecord>, List<String>>`; `absoluteName` switches over `DomainName.parse`. |
| `zone/Zone.java` | `of` and `with` return `Result<Zone, List<String>>`: the only way to make a zone. |
| `zone/ZoneLoader.java` | `load` returns `Result<Zone, List<String>>` and switches over the file parsers. |
| `server/Responder.java` | Switches over `MessageCodec.decode`: a message gets an answer, a problem gets FORMERR. |
| `server/ServeSettings.java` | `from` returns `Result<ServeSettings, List<String>>`. |
| `server/ServeCli.java` | `main` switches over the settings result. |
| `resolver/Upstream.java` | `ask` returns `Result<Message, LookupProblem>`. |
| `resolver/DnsClient.java` | Builds the results of `ask`; switches over `MessageCodec.decode`. |
| `resolver/InMemoryUpstream.java` | Builds the results of `ask`. |
| `resolver/CachingResolver.java` | Switches over the upstream's result. |
| `sampling/Sampler.java` | Tests the result of `ask` with a negated `instanceof` pattern. |
| `sampling/SampleSettings.java` | `from` returns `Result<SampleSettings, List<String>>`. |
| `sampling/SampleCli.java` | `main` switches over the settings result. |
| `migration/MigrationGuard.java` | Returns `Result<ApprovedChange, MigrationRefusal>` and `Result<Instant, MigrationRefusal>`. |
| `migration/ZoneStore.java` | `apply` returns `Result<Publication, MigrationRefusal>`. |
| `experiment/ExperimentSettings.java` | `from` returns `Result<ExperimentSettings, List<String>>`; `parse` switches over `DomainName.parse`. |
| `experiment/LiveSettings.java` | `from` returns `Result<LiveSettings, List<String>>`. |
| `experiment/TtlSimulation.java` | Switches over the zone check and the guard's decisions. |
| `experiment/TtlExperimentCli.java` | `main` switches over the settings result. |
| `experiment/LiveMigration.java` | Switches over the guard's and the store's results, with nested patterns. |
| `experiment/TtlLiveCli.java` | `main` switches over the settings result. |
| `jdk/LabResolver.java` | Switches over `DomainName.parse` and over the result of `DnsClient.ask`. |
| `jdk/JdkLookupCli.java` | Tests the zone result with `instanceof` patterns. |

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the 1.2.0 jar and confirmed it: `Result$Success` and `Result$Error` are `final` classes that extend `java.lang.Record`, and `Result` has a `PermittedSubclasses` entry. A **sealed** interface lists every type allowed to implement it. That is what lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=...]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=...]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<DomainName, String>(DomainName name)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

**The calls this repository uses:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch` over the `Result` with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`, as a statement or as an expression.
- `instanceof` with the same patterns: `result instanceof Result.Error<...>(var reason)`.

The library offers more. I listed its public methods with `javap -public` on the jar: `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, `fromOptional`, `fromOptionalResult`, and the function builders `mapping`, `flatMapping` and `errorMapping`. This repository uses none of them.

I ran a small program with the 1.2.0 jar on the class path, using the repository's own `DomainName.parse`:

```text
Success[result=book.seatwave.example.]
Error[error=a label may hold only lower-case letters, digits, hyphens and underscores: -bad-.example]
true
Success[result=3]
true
name book.seatwave.example.
reason: a label may hold only lower-case letters, digits, hyphens and underscores: -bad-.example
```

Line 3 shows that `equals` compares by content (parsing `Book.SeatWave.example` gives a result equal to the parse of `book.seatwave.example.`, because records compare by their components). Line 4 shows `map` applying a function to a success (the name has 3 labels). Line 5 shows `isError()` on the bad one. The last two lines show a `switch` and an `instanceof` pattern.

**Java version.** The class files in the 1.2.0 jar have major version 65, which is Java 21 (`javap -v` printed `major version: 65`). So the library needs Java 21 or newer, and this repository's `switch` with record patterns also needs Java 21. The project builds with `--release 25`.

## Walkthrough of this repository's use

### Building results: a name

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

- `parse` returns `Result<DomainName, String>`: the error side is one text. Each failure path is `Result.error(...)`; the last line chooses between `Result.success(new DomainName(labels))` and `Result.error(problem + ": " + text.strip())`.
- The constructor of `DomainName` throws for the same rules. `parse` is for text from outside (a zone file, a query typed by a person); the constructor is for code that should never pass a bad value.

### Building results: the trust boundary

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

- `decode` runs the decoder; the private `Malformed` exception stops the work quickly, and the `catch` turns it into `Result.error(malformed.problem)`. The exception is an implementation detail; callers see only a value.

### Building results: lists of problems

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

- A zone can have many problems. The error type is `List<String>`: one line per problem. `of` returns an error holding the list, or a success holding the zone.

```java
    List<String> problems = new ArrayList<>(reader.problems());
    problems.addAll(zoneProblems);
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(new ServeSettings(listeners, Duration.ofSeconds(seconds), seed));
  }
```

- `ServeSettings.from` gathers the reader's problems and the zone problems and returns them all in one `Result.error(List.copyOf(problems))`, or the settings in a success. Every key is read first, so one run reports every mistake.

### Building results: the migration guard

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

- `approve` is the only place that makes an `ApprovedChange`. The error side is the sealed `MigrationRefusal`: `Result<ApprovedChange, MigrationRefusal>`. A refusal is an expected outcome, so it is a value.

```java
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
```

- `apply` returns `Result<Publication, MigrationRefusal>`: a refusal if the zone changed after the approval, the new publication otherwise.

### Examining a result: `switch` as an expression

```java
    Message response =
        switch (MessageCodec.decode(packet)) {
          case Result.Success<Message, MessageProblem>(Message query) ->
              answer(query, zone, random);
          case Result.Error<Message, MessageProblem> unreadable ->
              reply(MessageCodec.idOf(packet), queryFlags, List.of(), ResponseCode.FORMAT_ERROR);
        };
```

- A `switch` **expression** produces a value: here a `Message`. Each case unpacks the record: `Result.Success<Message, MessageProblem>(Message query)` binds the good value to `query`. The error case binds the whole result to `unreadable` without unpacking it, because it needs only to know that it failed.
- The compiler checks that both cases are present. If the library added a third case, this `switch` would stop compiling.

```java
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
```

- The error case needs a block with several statements. `yield` gives the block's value to the `switch` expression (Java 14).

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

- The same with `yield`, in a method whose result is an `Optional`.

### Examining a result: `switch` as a statement

```java
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
```

- In `main` the `switch` is a **statement**: each arm does something. On success it calls `serve(settings)`. On error it prints one line per problem with `problems.forEach(...)` and exits with status 2.

```java
        switch (DomainName.parse(fields[0])) {
          case Result.Success<DomainName, String>(DomainName name) -> {
            current = Optional.of(name);
            byName.putIfAbsent(name, new LinkedHashMap<>());
          }
          case Result.Error<DomainName, String>(String problem) -> problems.add(where + problem);
        }
```

- A statement `switch` that changes a local variable: on success `current` and the map are updated; on error a problem line is added to a list.

```java
    switch (DomainName.parse(originText)) {
      case Result.Success<DomainName, String>(DomainName parsed) -> origin = parsed;
      case Result.Error<DomainName, String>(String problem) -> problems.add("origin: " + problem);
    }
```

- `origin = parsed` assigns the unpacked value to a variable declared before the `switch`.

### Examining a result: `instanceof`

```java
  private static Optional<InetAddress> firstAddress(
      Result<Message, LookupProblem> response, RecordType type) {
    if (!(response instanceof Result.Success<Message, LookupProblem>(Message message))) {
      return Optional.empty();
    }
```

- `response instanceof Result.Success<Message, LookupProblem>(Message message)` tests the type and unpacks in one step. With the `!` in front, the `if` returns early for anything that is not a success; after the `if`, the variable `message` is in scope (Java calls this **flow scoping**).

```java
      Result<Zone, List<String>> zone = ZoneLoader.load(reader, "zone");
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

- Two `instanceof` patterns on the same `Result<Zone, ...>`. The first adds the zone problems to a list. The second, in the condition `!problems.isEmpty() || !(zone instanceof Result.Success<...>(Zone loaded))`, both tests and, when the `if` returns, makes `loaded` available below.

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

- `store.apply(change) instanceof Result.Success<Publication, MigrationRefusal>` is a type test without unpacking: the code needs only to know that it succeeded.

### Examining a result: nested patterns

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

- `apply` has a switch expression inside a switch expression: an approved change is applied to the store, and the store's own `Result` is examined.
- `retire` uses a **nested record pattern**: `Result.Error<Instant, MigrationRefusal>(MigrationRefusal.TooEarly(var earliest, var why))` matches an error whose reason is a `TooEarly` and takes its two components out at once. The next case catches every other refusal.

### Passing results through an interface

```java
  /**
   * Asks one question.
   *
   * @param question the question
   * @return the response, or why there is none
   */
  Result<Message, LookupProblem> ask(Question question);
}
```

- The whole `resolver` package is built on one method that returns a `Result`. A closed port, a timeout and an unreadable answer are all values of `LookupProblem`; nothing is thrown. `DnsClient` and `InMemoryUpstream` build these results; `CachingResolver` and `Sampler` examine them.

## Try it yourself

All commands run in the repository folder.

**1. See that the library is the only runtime dependency.**

```text
./gradlew -q dependencies --configuration runtimeClasspath
```

```text
runtimeClasspath - Runtime classpath of source set 'main'.
+--- com.github.sviperll:result4j:1.2.0
\--- com.github.sviperll:result4j:{strictly 1.2.0} -> 1.2.0 (c)
```

(Gradle also prints a banner and a legend, left out here.) The `strictly` line is the lock file's constraint: the version may not change.

**2. List the methods of the type, from the jar.** Find the jar in Gradle's cache and run `javap`:

```text
javap -public -cp "$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)" com.github.sviperll.result4j.Result | head -12
```

You should see `success`, `error`, `isError`, `map`, `flatMap` and the others listed above.

**3. Watch an expected failure come back as a value, not a crash.** Break a settings file as in `java-properties.md` and run it:

```text
./gradlew -q sample --args='<path of a copy with lookup.count=0>'
```

```text
invalid: lookup.count must be from 1 to 1000000, but was 0
```

No stack trace: the program took the `Error` arm of the `switch` and exited with status 2.

**4. Run the demo program.** Save the program that printed the seven lines above as `R4j.java` in a scratch folder (it imports `Result` and `DomainName`) and run `java -cp build/classes/java/main:<path of the result4j jar> R4j.java`.

## Mistakes people make

- **Using exceptions for input mistakes.** Then one mistake stops the run and the others are never reported. Here every input mistake is a `Result`.
- **Using `Result` for bugs.** A null argument is a bug; it throws `NullPointerException` from `Objects.requireNonNull`.
- **A `default` branch in the `switch`.** It would hide a new case. None of the `switch`es over a `Result`, a sealed interface or an enum has a `default`.
- **Reading the value of a failure.** The settings reader returns a harmless stand-in for a bad key. The code checks `problems()` before it builds anything, so a stand-in is never used.
- **Forgetting to look at the result.** A method that returns a `Result` and whose result is ignored loses the failure. Every call in this repository either switches over the result, passes it on, or tests it with `instanceof`.
- **Wrapping an exception in a `Result` when nobody can handle it.** An `IOException` from a socket in `DnsClient.ask` becomes an `UncheckedIOException`, not a `Result`, because no caller can do anything useful with it; a closed port and a timeout, which callers can handle, are `Result` errors.

## Check yourself

1. What are the two kinds of `Result`, and how does Java know a `switch` over it is complete? (`Success` and `Error`; the interface is sealed, so the compiler knows there are only two.)
2. Why does `Zone.of` return `Result<Zone, List<String>>` and not throw on the first problem? (A zone can have many problems and a person wants to see them all in one run.)
3. Why is a refusal of the migration guard a `Result` and the constructor of `ApprovedChange` a plain constructor? (A refusal is an expected outcome that callers handle; the constructor is package-private and only the guard calls it after its checks.)
4. What does `response instanceof Result.Success<...>(Message message)` do? (It tests that the result is a success and, if so, puts its value in the variable `message`.)
5. Which Java version does the library need, and why? (Java 21: its class files are version 65, and the `switch` with record patterns needs it.)

## Further reading

- result4j on Maven Central (version 1.2.0): https://central.sonatype.com/artifact/com.github.sviperll/result4j/1.2.0
- The Java Language Specification, Java SE 25, patterns and `switch` (chapter 14.11 and chapter 15.20.2): https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- JEP 440, Record Patterns: https://openjdk.org/jeps/440
- JEP 441, Pattern Matching for `switch`: https://openjdk.org/jeps/441
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
