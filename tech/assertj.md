# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(response.responseCodeValue()).isEqualTo(ResponseCode.NAME_ERROR.code())` is easier to read than a hand-written `if` with a `throw`. The README gives the reason: "Fluent assertions with clear failure messages." In a test of a DNS program the message matters, because a failure often shows a record in dig's text form, a list of problems or a response code, and that value is the clue.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is used in all 20 test classes and `assertThatThrownBy` in 10 of them. There are 208 `assertThat(` calls and 26 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Duration`, one for a `boolean`, one for a `Path`, one for an `Optional`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type and message. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server (`client.ask(...)`) blocks until the answer comes or its timeout ends, and only then does the assertion look at it.

## Walkthrough of this repository's use

The numbers in this section come from `grep -rhoE` over `src/test` and count raw text matches of each method call, so a call of the same name on a plain `String` (for example inside a lambda) is counted too.

### `isEqualTo` and `hasToString`

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

`isEqualTo` compares with `equals`. It is the most used method here (76 calls). `TestNames.name("SeatWave.Example.")` and `TestNames.name("seatwave.example")` are two spellings of one name, and the test expects the two `DomainName` values to be equal. `DomainName` is a Java **record**, a class whose `equals` compares its parts automatically. `hasToString("seatwave.example.")` compares the text that `toString()` returns, which here is dig's spelling with the final dot (7 calls). `containsExactly("seatwave", "example")` on `labels()` checks a list (see below).

### Text: `startsWith`, `isEqualTo` on a message

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

The helper `problem(text)` (line 73) returns the error text of a failed parse. `isEqualTo` requires the whole text; `startsWith("...")` requires only the beginning, which keeps the test from breaking when a message later gets a longer tail. `.contains("a", "b")` on text requires every listed piece to be somewhere in it, and `doesNotContain("OUTSIDE")` requires a piece to be absent; both appear in `SamplerTest.measuresTheConfiguredSplitWithinThreeStandardErrors`:

```java
    assertThat(result.failures()).isZero();
    assertThat(report).contains("192.0.2.10").contains("198.51.100.20").doesNotContain("OUTSIDE");
    assertThat(report).containsPattern("192\\.0\\.2\\.10 +90 +90\\.00 ");
```

`containsPattern("192\\.0\\.2\\.10 +90 +90\\.00 ")` checks that a **regular expression** (a pattern that describes text) matches somewhere in the report. In a Java string `\\.` is the two characters `\.`, which mean "a real dot", and ` +` means "one or more spaces". It is the only pattern check in the project.

### Lists: `containsExactly` and `extracting`

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

- `containsExactly("book.seatwave.example.", ...)` checks that the list holds exactly these elements, in this order, and no others (29 calls).
- `extracting(DomainName::toString)` first turns each element into one of its fields, then checks the resulting list. `DomainName::toString` is a **method reference**, a short way to write "call `toString()` on each element".
- The comment line explains the number 23 in the last check: the length of the name on the wire (`1 + 4` for the label `book`, `1 + 8` for `seatwave`, `1 + 7` for `example`, plus the final zero octet).
- `isTrue()` and `isFalse()` check a `boolean` (7 and 4 calls).

### Optionals: `isEmpty` and `contains`

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

`mail.type()` returns an `Optional<RecordType>`: a box that holds a value or nothing. The type code 15 (MX) is not one this lab knows, so the box is empty, and `isEmpty()` checks that (15 calls in all, on boxes, lists and texts). `contains(RecordType.AAAA)` on an `Optional` requires the box to hold exactly that value. `assertThatThrownBy` is explained below.

### Counting and one element: `hasSize`, `singleElement`, `satisfies`

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

`singleElement()` requires the list to hold exactly one element and then lets the next call look at that element. `satisfies(soa -> ...)` runs another assertion on it; here the SOA record's TTL must be 300 seconds. `hasSize(2)` checks the number of elements (9 calls). `isZero()` and `isNotZero()` check a number against 0 (6 and 2 calls). `NOTIFY_OPCODE_FLAGS` and the `& Message.RECURSION_DESIRED_FLAG` test in the same file use bit arithmetic, which `tests-server-and-resolver-walkthrough.md` explains.

### Every or any element: `allMatch` and `anyMatch`

```java
  @Test
  void refusesTheBrokenZoneWithItsFourMistakes() throws Exception {
    List<String> problems =
        ((Result.Error<ServeSettings, List<String>>) read("config/serve-broken.properties"))
            .error();

    assertThat(problems).hasSize(4).allMatch(problem -> problem.startsWith("seatwave: "));
  }
```

`hasSize(4)` and `allMatch(problem -> problem.startsWith("seatwave: "))` are chained: there are four problems, and every one starts with the listener name. `anyMatch(problem -> ...)` (12 calls, all in `ZoneTest`) requires at least one element to satisfy the test. A lambda such as `problem -> problem.startsWith("...")` is "given a problem, answer yes or no".

### Decimals: `isCloseTo`

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

A share measured over 10,000 random picks is never exactly 0.9. `isCloseTo(NINETY_PERCENT, offset(TOLERANCE))` accepts any value from 0.891 to 0.909. `offset(0.009)` is a helper from `org.assertj.core.data.Offset`, imported on line 4 of that file. The comment on line 26 of `ZoneTest` gives the reason for 0.009: three standard errors of a 90 percent share over 10,000 picks. The generator is seeded, so the same value is picked on every run; the tolerance is there for a reader who changes the seed (`tests-message-and-zone-walkthrough.md`). `CachingResolverTest` uses the same helper to compare a hit ratio with one third.

### Exceptions: `assertThatThrownBy`

```java
  @Test
  void refusesAPrivilegedPort() {
    assertThatThrownBy(() -> DnsServer.start(DNS_PORT, store(), new SplittableRandom(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the port must be from 1024 to 65535, but was 53");
  }
```

`assertThatThrownBy(() -> ...)` runs the lambda and expects it to throw. `.isInstanceOf(IllegalArgumentException.class)` checks the type of the exception (42 calls in all, some on values rather than exceptions). `.hasMessage("...")` checks the message exactly (2 calls) and `hasMessageStartingWith("...")` checks the beginning (1 call, in `LabResolverTest`). Most calls here check the type only: the value types all refuse bad input with the same exception.

### Results that are errors: a cast, and `isEqualTo(Result.error(...))`

```java
  private static String problem(String text) {
    return ((Result.Error<DomainName, String>) DomainName.parse(text)).error();
  }
```

The project's result type (`result4j.md`) is either `Result.Success` or `Result.Error`. The helper casts the value to `Result.Error<DomainName, String>` and calls `.error()` to get the text of the problem. The cast is safe because each test that uses it has just arranged an input that must fail; if it succeeded instead, the cast would throw a `ClassCastException` and the test would error, which also shows a problem. Another way, in `MessageCodecTest`, is to compare the whole result with an expected one:

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

`Result.error(new MessageProblem.Truncated("header"))` builds the error value that the decoder should return for a three-byte packet, and `isEqualTo` compares it with the real result. `MessageProblem.Truncated` is a record, so its `equals` compares the word "header". `isInstanceOf(MessageProblem.BadCounts.class)` checks only the kind of problem.

### Casting inside an assertion chain: `asString`

```java
    assertThat(((Result.Error<Zone, List<String>>) missing).error())
        .singleElement()
        .asString()
        .startsWith("cannot read zones/absent.zone");
```

`singleElement()` picks the one problem, `.asString()` tells AssertJ to treat it as text, and `startsWith` then checks the beginning. Without `asString()` AssertJ would treat the element as a plain object and offer no text methods.

## Try it yourself

All ran in a scratch copy on 11 November 2026.

1. Count the AssertJ entry points:

```
grep -rho "assertThat(\|assertThatThrownBy(" src/test | sort | uniq -c
```

   Output: `208 assertThat(` and `26 assertThatThrownBy(`.

2. Count how often each check is used:

```
grep -rhoE "\.(isEqualTo|isInstanceOf|contains|containsExactly|hasSize|isEmpty|isZero|isTrue|isFalse)\(" src/test | sort | uniq -c | sort -rn
```

   Output: `76 .isEqualTo(`, `42 .isInstanceOf(`, `29 .containsExactly(`, `23 .contains(`, `15 .isEmpty(`, `9 .hasSize(`, `7 .isTrue(`, `6 .isZero(` and `4 .isFalse(`.

3. Make a test fail on purpose, in a scratch copy only. In `DomainNameTest` change `BOOK_WIRE_OCTETS = 23` to `BOOK_WIRE_OCTETS = 24`, then run:

```
./gradlew --offline cleanTest test --tests '*DomainNameTest'
```

   Gradle prints `DomainNameTest > knowsItsPlaceInTheTree() FAILED`, `org.opentest4j.AssertionFailedError at DomainNameTest.java:70` and `6 tests completed, 1 failed`. The full message is in `build/test-results/test/TEST-io.plainround.seatwave.dns.message.DomainNameTest.xml`: `expected: 24` and ` but was: 23`, each on its own line. Undo the edit afterwards.

4. Find the `isCloseTo` checks:

```
grep -rn "isCloseTo" src/test
```

   Output: two lines, in `ZoneTest.java` (line 104) and `CachingResolverTest.java` (line 52).

## Mistakes people make

- **Forgetting the last call.** `assertThat(x);` with nothing after it checks nothing and the test passes. Every `assertThat` here ends with a method such as `isEqualTo`.
- **Comparing a `double` with `isEqualTo`.** Use `isCloseTo(x, offset(tolerance))` for decimal results of calculations, as `ZoneTest` does for a measured share. Exact whole numbers and text are fine with `isEqualTo`.
- **Expecting an assertion to wait.** It looks at the value once. A call such as `client.send` waits; the assertion does not.
- **A time check against the real clock.** A loaded machine makes any such check flaky. The tests here never assert how long something took; they advance a simulated clock and assert what the cache or the guard then decides.
- **Casting a result without arranging the failure.** If the code succeeds, the cast throws a `ClassCastException`.
- **A lambda that does not throw.** `assertThatThrownBy` fails with "Expecting code to raise a throwable" if nothing is thrown.

## Check yourself

1. *What does `assertThat(x).isEqualTo(y)` do when the values differ?* It throws an `AssertionError` with both values, and JUnit marks the test as failed.
2. *Why does AssertJ take a lambda for exceptions?* It has to run the code itself so that it can catch what is thrown.
3. *What does `containsExactly` check?* That a list holds exactly the given elements, in that order, and nothing else.
4. *What does `.asString()` do after `singleElement()`?* It tells AssertJ to treat the one element as text, so `startsWith` and similar methods are available.
5. *Why does `ZoneTest` use `isCloseTo` and not `isEqualTo` for the 90 percent share?* A share measured over 10,000 random picks is a decimal that is not exactly 0.9, so the test accepts a small band around it.

## Further reading

- AssertJ core 3.27.7 documentation: <https://assertj.github.io/doc/>
- `Assertions` API (3.27.7): <https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.7/org/assertj/core/api/Assertions.html>
- `assertThatThrownBy`: <https://assertj.github.io/doc/#assertj-core-exception-assertions>
