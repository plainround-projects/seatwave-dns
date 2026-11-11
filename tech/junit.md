# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and all 107 run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks. This module is about DNS, so many tests build a packet byte by byte, start a real server on the loopback address and talk to it over UDP, or run a cache on a simulated clock; JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform()`. |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 20 test classes with 107 `@Test` methods, and 2 helper classes that hold no tests (`TestNames` and `TestZones`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 20 here), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The two helpers are the exception on purpose: `TestNames` and `TestZones` are `public` because tests in several packages use them.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. Each test that needs a server starts its own on a port the system picks, so the tests would not share a socket even if they did overlap (`tests-server-and-resolver-walkthrough.md`).

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

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

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "reads absolute and relative spellings as one lower case name". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). In this test the first two statements arrange and act (they turn two spellings of one name into `DomainName` values) and the three `assertThat` lines assert.

Tests that talk to a socket or load a file declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `IOException` or `InterruptedException` that would be noise in a test.

### A fresh instance for every test

```java
  private final SimulatedClock clock = new SimulatedClock(START);
  private final InMemoryUpstream upstream =
      new InMemoryUpstream(new ZoneStore(TestZones.seatwave(), clock), new SplittableRandom(1));
```

These two fields belong to the object, not to the class (there is no `static`). Because JUnit creates a new instance of the class for every test method, each test gets its own `SimulatedClock` that starts at the same instant, and its own upstream. A test that moves the clock forward cannot disturb the next test. This is the main way the repository keeps time under control: no test sleeps, it advances a clock (`tests-server-and-resolver-walkthrough.md`).

### `@TempDir`: a folder for one test

```java
  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. `SettingsReaderTest` uses it to write a small properties file and read it back. I searched the test sources and found `@Test` 107 times and `@TempDir` once.

### Closing what a test opens

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

There is no `@AfterEach` here. Instead the test opens the server and the client in the round brackets of a `try`. A resource opened there is closed automatically when the block ends, even when an assertion throws. That is why no server is left holding its port after a failing test (`java.md` explains the construct).

### A class inside a test class

```java
  /** Builds a raw packet byte by byte, so a test can write what no encoder would. */
  static final class Packet {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
```

A test class may hold helper classes. `Packet` is a small builder of raw bytes that only `MessageCodecTest` uses. It holds no `@Test`, so JUnit ignores it. Plain helper methods (the private `problem(...)`, `respond(...)`, `settings(...)`, `healthy(...)`) do the same job for the other classes; the walkthrough files show each one.

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@BeforeAll`, `@AfterAll`, `@BeforeEach`, `@AfterEach`, `@Nested`, `@DisplayName`, `@Timeout` or `@ExtendWith`. Where several inputs need one check, a test lists the checks one after another or uses an ordinary `for` loop (`ZoneTest.putsTheWeightedAddressFirstInProportionToItsWeight` loops 10,000 times). Each test builds what it needs with ordinary helper methods. That keeps each test readable from top to bottom.

There is also no `@Timeout`. A test that talks to a socket never relies on JUnit to cut it off. Each wait carries its own bound instead: `DnsClientTest` gives its client a timeout of 200 milliseconds when it expects silence and 2 seconds when it expects an answer. A bound inside the call gives a clear error, where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why `TestZones` can read `zones/seatwave.example.zone` and `ServeSettingsTest` can read `config/serve.properties` by those relative paths.

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform()` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test | grep -c PASSED
```

   Output: `107`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*DomainNameTest'
```

   Output: six lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `6 actionable tasks: 3 executed, 3 up-to-date`.

3. Run one method:

```
./gradlew --offline cleanTest test --tests '*ResponderTest.refusesNamesOutsideTheZoneAndOtherClasses'
```

   Output: one `PASSED` line, `ResponderTest > refusesNamesOutsideTheZoneAndOtherClasses() PASSED`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Output: `107 @Test`. (`@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test` finds the one use, in `SettingsReaderTest.java` line 64.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 20 files has a `tests="..."` count after a full run: 4 (`SettingsReaderTest`), 2 (`LiveMigrationTest`), 2 (`SiteEndpointsTest`), 6 (`TtlSimulationTest`), 4 (`LabResolverTest`), 6 (`DomainNameTest`), 12 (`MessageCodecTest`), 7 (`ValuesTest`), 4 (`HealthHistoryTest`), 10 (`MigrationGuardTest`), 8 (`CachingResolverTest`), 5 (`DnsClientTest`), 5 (`SamplerTest`), 3 (`DnsServerTest`), 8 (`ResponderTest`), 3 (`ServeSettingsTest`), 3 (`WeightsTest`), 3 (`ZoneFileTest`), 4 (`ZoneLoaderTest`) and 8 (`ZoneTest`). They add up to 107.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input and starts its own server where it needs one, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*zone*'` would also match the package name `zone`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a test from a different folder.** The zone and settings files are found by relative paths, so the test must start in the project folder, as Gradle does.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every server and link in a `try` block that closes it automatically.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another. It is why each test here gets a new `SimulatedClock`.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter, and deletes it afterwards.
4. *How does a test here make sure a server is closed even when a check fails?* It opens the server in a `try ( ... )` header, which closes it when the block ends.
5. *How do 107 `@Test` methods give 107 results?* Each method runs once; this repository has no parameterized tests.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>
