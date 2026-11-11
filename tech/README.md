# Technology notes for seatwave-dns

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal, DNS or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, try it yourself, mistakes people make, check yourself, and further reading.

The repository is the hands-on project of Module 6, "Naming the world: DNS", of a system design course. It serves a ticket-booking case called SeatWave. It holds an authoritative DNS server on the loopback address written with the Java standard library, weighted answers, a caching resolver that honors TTLs, a TTL-change experiment (simulated and live), a migration guard that refuses unsafe record changes, and the JDK's own name lookup pointed at the lab server. It is Java 25 with Gradle, one Bash script, and one optional CoreDNS layer in Docker Compose.

## The files

**The language, the protocol and the libraries**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, method, exception, records, enums, sealed interfaces, `switch` with patterns, generics, lambdas, streams, `Optional`, immutable collections, try-with-resources, `Duration` and `Clock`, regular expressions, threads and the small concurrency tools, Javadoc, and the minimum Java version of each feature. |
| `dns-protocol.md` | DNS from zero as this repository implements it: names and labels, zones, the record types, a query and a response in real bytes, the header and its flags, the four sections, name compression and why a pointer loop is refused, response codes, referrals and glue, TTL, UDP and truncation. |
| `java-udp-and-resolver-api.md` | UDP against TCP, datagrams and the JDK classes for them, byte order, timeouts on every receive, and the JDK's resolver service-provider interface with its `META-INF/services` file. |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, every place it is used, and why expected failures are values here and exceptions are kept for bugs. |

**The data formats**

| File | Role |
|---|---|
| `java-properties.md` | The `.properties` format and all eight settings files under `config/`, key by key. |
| `dns-zone-files.md` | The zone-file format and the weights-file format, and every file under `zones/`, line by line, including the broken copy with its four mistakes. |

**The code walkthroughs**

| File | Role |
|---|---|
| `java-message-walkthrough.md` | The `message` package, line by line: `DomainName`, record types and data, `Message`, and `MessageCodec`, the trust boundary for every packet. |
| `java-zone-and-server-walkthrough.md` | The `zone` and `server` packages, line by line: the zone-file parser, the weights, `Zone` with its checks and lookup, `Responder`, the UDP `DnsServer` and the `serve` command. |
| `java-resolver-and-jdk-walkthrough.md` | The `resolver` and `jdk` packages, line by line: the UDP client, the in-memory upstream, the TTL-honoring cache, and the plug-in for the JDK's own lookup. |
| `java-migration-walkthrough.md` | The `migration` package, line by line: the guard, the health history, the zone store, and why only the guard can create an approved change. |
| `java-experiment-sampling-config-walkthrough.md` | The `experiment`, `sampling` and `config` packages, line by line: the settings reader, the weighted-routing test, the TTL simulation and the live run. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle: the build tool, `build.gradle.kts` and its tasks (`serve`, `sample`, `ttlExperiment`, `ttlLive`, `jdkLookup`). |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 80 percent floor. |

**The tools around the lab**

| File | Role |
|---|---|
| `dig.md` | `dig`, the command-line DNS client used to ask the lab servers. |
| `bash-ttl-change.md` | `scripts/ttl-change.sh`: the Bash script that asks the lab server once per second during the live run, with its guardrails. |
| `coredns.md` | CoreDNS and `coredns/Corefile`: the optional second implementation that reads the same zone files. |
| `docker-compose.md` | `compose.yaml`, the optional way to run CoreDNS in a container. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit: how the test engine finds and runs the tests. |
| `assertj.md` | AssertJ: the fluent assertions used in the tests. |
| `tests-message-and-zone-walkthrough.md` | The tests of the `message` and `zone` packages, line by line. |
| `tests-server-and-resolver-walkthrough.md` | The tests of the `server` and `resolver` packages, line by line. |
| `tests-other-packages-walkthrough.md` | The tests of the `config`, `experiment`, `jdk`, `migration` and `sampling` packages, line by line. |

**Everything else**

| File | Role |
|---|---|
| `editorconfig.md` | `.editorconfig`, the editor settings. |
| `git.md` | Git: `.gitignore` and `.gitattributes`. |

There is no `awaitility.md`: no test in this repository uses Awaitility (every test that crosses a socket waits on a real reply with a timeout, and every test of time runs on a simulated clock).

## Reading order

1. **The language first.** `java.md`. Do not skip it: every walkthrough relies on it. Then `result4j.md`, because almost every method returns a `Result`.
2. **The protocol.** `dns-protocol.md`, then `java-udp-and-resolver-api.md`.
3. **The data formats.** `dns-zone-files.md` and `java-properties.md`: the zones the servers load and the settings the programs read.
4. **The code walkthroughs, in the order the data travels.** `java-message-walkthrough.md` first (the bytes), then `java-zone-and-server-walkthrough.md` (the server), `java-resolver-and-jdk-walkthrough.md` (the client side), `java-migration-walkthrough.md` (the guard) and `java-experiment-sampling-config-walkthrough.md` (the experiments).
5. **The tools.** `dig.md`, `coredns.md`, `docker-compose.md`, `bash-ttl-change.md`.
6. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
7. **The tests.** `junit.md`, `assertj.md`, then `tests-message-and-zone-walkthrough.md`, `tests-server-and-resolver-walkthrough.md` and `tests-other-packages-walkthrough.md`.
8. **The rest.** `editorconfig.md`, `git.md`.

## If you have ten minutes

1. `dns-protocol.md`: read "How it works underneath", for a query and a response in real bytes, the flags, compression and why a pointer loop is refused, the response codes, a referral with glue, TTL, and UDP truncation.
2. `java-migration-walkthrough.md`: read "How it works underneath", for the steps of a safe migration with their times, and why only the guard can create an approved change.
3. `java-experiment-sampling-config-walkthrough.md`: read "How it works underneath", for how the weighted test judges a count, and how the simulation and the live run measure a TTL change.

## How to run what the notes describe

Every command in these notes was run in this repository, on Java 25.0.2 and Gradle through `./gradlew`, on 10 and 11 November 2026, on a Mac with an ARM processor (`aarch64`). Timings and ports chosen by the system differ on every run and every machine: compare the shape, not the digits. Every command stays on the loopback address, and `dig` is used only against `127.0.0.1` on the lab's ports. The notes never run `scripts/ttl-change.sh` with `--run`, never start Docker, and send no lookup to a public name.

```text
./gradlew -q serve --args='config/serve.properties'
./gradlew -q sample --args='config/sample.properties'
./gradlew -q ttlExperiment --args='config/ttl-experiment.properties'
./gradlew -q ttlLive --args='config/ttl-live.properties'
./gradlew -q jdkLookup --args='config/jdk-lookup.properties'
```

The first starts three name servers on UDP ports 5301 to 5303 and stops by itself after 300 seconds; use a second terminal for `dig`. The second sends 10,000 lookups of the weighted name and prints the share of each address against its weight. The third runs the simulated experiment of five arms. The fourth runs the live migration for about half a minute. The fifth shows the JVM's own cache at work.

## How these notes were checked

Each walkthrough quotes the source line by line, copied by a script and not retyped. The command `python3 tools/techcov.py <report.json> seatwave-dns`, run from the folder that holds all the repositories, counts the non-blank, non-comment lines of every source, script and configuration file and checks that each appears in these notes. The notes of `src/main/`, `config/` and `zones/` quote every counted line. Everything stated about the JDK, the DNS messages, `Properties` and result4j 1.2.0 was checked on this machine, by running a small program, by `javap` on a compiled class or a jar, by reading the JDK source (`src.zip`), or by sending real packets to the lab server with `dig` and `nc`.
