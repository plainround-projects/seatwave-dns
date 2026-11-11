# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers one question: when the tests ran, which lines of the program were executed at least once? A line that no test ever ran is a line whose behavior nobody has checked. **JaCoCo** (Java Code Coverage) is the tool that measures it for Java. It does not tell you whether the tests are good, only which code they touched.

JaCoCo works with a **Java agent**: a small jar that the Java Virtual Machine (JVM) loads before the program starts. The agent watches classes as they are loaded and records which parts run.

## Why this repository uses it

The build has a measured floor: line coverage must be at least 0.80, and the build fails below it. That keeps the tests honest as the code grows. The README reports "line coverage 86.5 percent (1,872 of 2,164 lines)".

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `build.gradle.kts` | Switches the plugin on (`jacoco`), pins its version, wires the report after the tests, sets the 0.80 rule and adds it to `check`. It also switches on the XML report. |
| `gradle/libs.versions.toml` | `jacoco = "0.8.16"`. |
| `gradle.lockfile` | Locks `jacocoAgent` and `jacocoAnt` (`dependency-locking.md`). |
| `build/jacoco/test.exec` | The raw recording of one test run (a binary file). |
| `build/reports/jacoco/test/html/index.html` | The readable report. |
| `build/reports/jacoco/test/jacocoTestReport.xml` | The same numbers as XML, written because of `xml.required = true`. |

## How it works underneath

1. `./gradlew test` starts a new JVM for the tests and passes it the option `-javaagent:` with the JaCoCo agent jar (the `jacocoAgent` configuration). Gradle does this by itself once the `jacoco` plugin is on.
2. As each class loads, the agent inserts small recording instructions into its bytecode, in memory only. The files on disk do not change.
3. When the JVM ends, the agent writes everything it recorded to `build/jacoco/test.exec`.
4. The task `jacocoTestReport` (`finalizedBy` on `test`, so it always runs afterwards) reads `test.exec` plus the compiled classes in `build/classes/java/main` and writes the HTML report and, here, the XML report. It uses the `jacocoAnt` configuration.
5. The task `jacocoTestCoverageVerification` reads the same data and applies the rules. If a rule is violated, the task fails and so does `check`.

JaCoCo counts several things. The four that matter here:
- **Instructions**: single bytecode instructions. The most fine-grained number.
- **Branches**: each outcome of an `if`, a `switch` or a `&&`. A branch is covered when both outcomes were taken.
- **Lines**: a source line counts as covered if at least one of its instructions ran. This is the counter the build's rule uses.
- **Methods** and **classes**: counted as covered if any part of them ran.

Only code under `src/main/java` is measured. The test classes themselves are not.

## Walkthrough of this repository's use

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

`toolVersion = libs.versions.jacoco.get()` fixes JaCoCo at 0.8.16. Without it, Gradle would use its own default.

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}
```

`finalizedBy(tasks.jacocoTestReport)` is what writes the report after every test run.

```kotlin
// The XML report gives the exact covered and missed line counts that the README quotes.
tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}
```

The task `jacocoTestReport` writes the HTML report by default. `xml.required = true` adds an XML file, `build/reports/jacoco/test/jacocoTestReport.xml`. The comment above the block in the build file says why: the XML gives the exact covered and missed line counts that the README quotes. Near its end the XML holds one element per counter: `<counter type="LINE" missed="292" covered="1872"/>` (the last such element is the total of the whole project; the ones before it belong to single packages, classes and methods).

```kotlin
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                // The floor for the whole project; the command-line entry points are the untested remainder.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
```

`counter = "LINE"` picks the line counter. `minimum = "0.80".toBigDecimal()` is the lowest accepted ratio of covered lines to all lines. The comment above it names the untested remainder: the command-line entry points. `tasks.check { dependsOn(...) }` makes the rule part of `check`. When I raised the minimum to 0.95 in a scratch copy, the build failed with `Rule violated for bundle seatwave-dns: lines covered ratio is 0.86, but expected minimum is 0.95`.

### The real numbers (Temurin 25.0.2, 107 tests)

I ran `./gradlew clean check` in a scratch copy and read `build/reports/jacoco/test/jacocoTestReport.xml` and the HTML report. The last row of the HTML table, "Total", reads:

| Counter | Missed | Total | Covered |
| --- | --- | --- | --- |
| Instructions | 1,626 | 11,931 | 86 percent (10,305 covered) |
| Branches | 160 | 913 | 82 percent (753 covered) |
| Lines | 292 | 2,164 | 1,872 lines, 86.5 percent |
| Methods | 28 | 337 | 309 |
| Classes | 4 | 78 | 74 |
| Complexity | 159 | 802 | 643 |

The rule needs 0.80 of 2,164 lines, which is 1,731.2, so at least 1,732 covered lines. The build has 1,872. **The margin is 140 lines.**

Per package, the missed lines (from the XML report, class by class):

| Package | Missed lines | Total lines | Where |
| --- | --- | --- | --- |
| `config` | 1 | 64 | One line of `SettingsReader`. |
| `message` | 9 | 351 | Nine lines of `MessageCodec$Decoder`, the packet reader. |
| `zone` | 7 | 430 | Six in `ZoneFile` and one in `Zone`. |
| `migration` | 4 | 175 | Three in `MigrationGuard` and one in `HealthHistory`. |
| `resolver` | 10 | 153 | Five in `DnsClient`, three in `InMemoryUpstream`, one in `CachingResolver` and one in `LookupProblem$TimedOut`. |
| `server` | 47 | 190 | All 38 lines of `ServeCli` (the five-minute server), seven in `DnsServer` and two in `ServeSettings`. |
| `sampling` | 23 | 154 | 21 in `SampleCli` (19 of them are its `main`; `SampleCli.run` is tested directly) and two in `SampleSettings`. |
| `experiment` | 121 | 541 | 79 in `TtlLiveCli` (all of it), 16 in `TtlExperimentCli` (its `main`; `TtlExperimentCli.render` is tested), and 26 spread over `TtlSimulation`, `ExperimentSettings`, `SiteEndpoints`, `LiveMigration`, `LiveSettings`, `Arm` and `SimulatedClock`. |
| `jdk` | 70 | 106 | 61 in `JdkLookupCli` (all of it) and 9 in `LabResolverProvider`. |

The command-line entry points explain most of the gap: `ServeCli` 38, `TtlLiveCli` 79, `JdkLookupCli` 61, the 16 `main` lines of `TtlExperimentCli` and the 19 `main` lines of `SampleCli` make 213 of the 292 missed lines. `Classes` shows 4 missed of 78: `ServeCli`, `TtlLiveCli`, `JdkLookupCli` and `LabResolverProvider`. The last one is a surprise: `LabResolverTest.namesItselfForTheServiceLoader` constructs it and calls `name()`, yet the report shows its constructor, its `get` method and `name()` (9 lines in all) as never run. The file `META-INF/services/java.net.spi.InetAddressResolverProvider` registers that class with the JDK, and I did not find out why the agent's recording misses it. I report the numbers as the tool printed them. The other 70 or so missed lines are spread over the classes in the table, mostly one to ten lines each; I did not read each one.

### Reading the report

Open `index.html`. Each row is a package; click it for its classes, click a class for its methods, click a method for the source with colors: green lines ran, red lines never ran, yellow lines ran only partly (a branch outcome was never taken).

## Try it yourself

1. Run the gate and the report:

```
./gradlew clean check
```

   Expected: 107 `PASSED` lines, `> Task :jacocoTestCoverageVerification`, `BUILD SUCCESSFUL in 3s` and `9 actionable tasks: 9 executed`.

2. Print the totals from the HTML report:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  1,626 of 11,931  86%  160 of 913  82%  159  802  292  2,164  28  337  4  78`.

3. Print the total line counter from the XML report:

```
grep -o '<counter type="LINE"[^>]*>' build/reports/jacoco/test/jacocoTestReport.xml | tail -1
```

   Expected: `<counter type="LINE" missed="292" covered="1872"/>`.

4. Print the totals of the `server` package:

```
grep -o '<tfoot>.*</tfoot>' build/reports/jacoco/test/html/io.plainround.seatwave.dns.server/index.html | sed 's/<[^>]*>/ /g'
```

   Expected: `Total  242 of 934  74%  15 of 53  71%  12  52  47  190  3  24  1  5`. The `server` package has 190 lines, 47 of them missed, and one missed class (`ServeCli`).

5. See the recording and the report folders:

```
ls build/jacoco build/reports/jacoco/test build/reports/jacoco/test/html
```

   Expected: `test.exec`; `html` and `jacocoTestReport.xml`; and `index.html`, `jacoco-resources`, `jacoco-sessions.html` and one folder per package (`io.plainround.seatwave.dns.config`, `.experiment`, `.jdk`, `.message`, `.migration`, `.resolver`, `.sampling`, `.server` and `.zone`).

6. Open the report in your browser (on a Mac): `open build/reports/jacoco/test/html/index.html`.

## Mistakes people make

- **Treating 80 percent as a goal.** Coverage shows which lines never ran. A line that ran can still be unchecked. The tests here also assert results.
- **Chasing 100 percent for `main` methods.** The build documents them as the untested remainder instead (here `ServeCli`, `TtlLiveCli`, `JdkLookupCli` and the `main` methods of `TtlExperimentCli` and `SampleCli`).
- **Running only `./gradlew test` and expecting the rule to be checked.** The rule runs in `jacocoTestCoverageVerification`, which `check` runs.
- **Reading an old report.** The report is rewritten after each `test` run; run the tests first.
- **Forgetting the margin.** There are 140 lines of headroom now, but every untested line uses some of it, so new code still needs tests in the same change.
- **Deleting a test to make a number look right.** The check then fails for the right reason.

## Check yourself

1. *What does the line counter measure?* The share of source lines in which at least one instruction ran during the tests.
2. *Which task checks the rule and which writes the reports?* `jacocoTestCoverageVerification` checks; `jacocoTestReport` writes the HTML and the XML.
3. *Why is coverage 86.5 percent and not 100?* The `main` methods of the command-line classes are never called by a test (213 of the 292 missed lines), and the rest are lines in other classes that no test reaches.
4. *What makes the rule part of `check`?* `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }`.
5. *How many lines of headroom are there?* 140: 1,872 covered against 1,732 required.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/>
- JaCoCo counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
- Gradle JaCoCo plugin (9.9.1): <https://docs.gradle.org/9.9.1/userguide/jacoco_plugin.html>
