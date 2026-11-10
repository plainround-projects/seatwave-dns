# seatwave-dns

A small authoritative DNS server for SeatWave's zone, written in Java with the standard library only, with a weighted-routing test, a TTL-change experiment (simulated and live), a migration guard that refuses unsafe record changes, and the JDK's own name lookup pointed at the lab server. Everything listens on 127.0.0.1 only. A pinned Docker Compose file runs CoreDNS with the same zone files for readers who have Docker.

## Which module this belongs to

This is the hands-on project of Module 6, "Naming the world: DNS", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service with four operations: list events, view seats, hold a seat and confirm a booking.

Module 6 asks what happens, step by step, when a browser resolves `seatwave.example`, and how to move SeatWave to a new address without users noticing. This repository holds:

- Three lab name servers in one process (`serve` task): a stand-in root zone on UDP port 5301, a stand-in `example.` zone on 5302 and `seatwave.example` on 5303, so `dig` can walk the resolution path one referral at a time.
- The message codec: DNS messages built and parsed by hand against RFC 1035, with every incoming packet checked at the boundary (lengths, counts, compression pointers, labels).
- The weighted-routing test (`sample` task): 10,000 lookups of a weighted name through a chosen server, with the share per address against the configured weights.
- The TTL-change experiment: a seeded simulation of 1,000 caching resolvers (`ttlExperiment` task) and a live run on the wall clock (`ttlLive` task) with `scripts/ttl-change.sh` recording what `dig` sees once per second.
- The migration guard: no record change is applied unless the old and new addresses passed their health checks for the whole old TTL window, and the old address is not approved for retirement before the old TTL has passed twice after the switch.
- The JDK's `InetAddress` pointed at the lab server through the resolver service-provider interface (`jdkLookup` task), which shows the JVM's own cache.

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS on 10 November 2026. The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine.
- `dig` for the shell commands. The commands below ran with the macOS system `dig`, DiG 9.10.6.
- Nothing else for the Java layer. The Gradle wrapper (9.9.1) downloads Gradle and the libraries below on the first build.
- Optional: Docker with Compose, for the CoreDNS layer. It was not run on the verification machine (see "Docker Compose (optional)").

## Dependencies and build plugins

Every version is exact. Each was checked on Maven Central on 10 November 2026 and against the OSV database on the same day, with no known vulnerability at the pinned version. The versions live in `gradle/libs.versions.toml`, and `gradle.lockfile` pins the transitive ones. There is no runtime library besides result4j: the server, the client, the codec and the sockets are the JDK's own.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | 10 Nov 2026 | Apache-2.0 | The JDK has no success-or-typed-error type, so a bad zone file, a malformed packet or a refused migration step would have to be an exception. |
| `org.junit:junit-bom` and `org.junit.jupiter:junit-jupiter` | 6.1.3 | 10 Nov 2026 | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | 10 Nov 2026 | Apache-2.0 | Fluent assertions with clear failure messages. |
| `com.diffplug.spotless` (Gradle plugin) | 8.11.0 | 10 Nov 2026 | Apache-2.0 | Runs the formatter in the build. |
| `com.google.googlejavaformat:google-java-format` (used by Spotless) | 2.0.0 | 10 Nov 2026 | Apache-2.0 | One enforced code style. |
| `org.jacoco:org.jacoco.agent` and the JaCoCo Gradle plugin | 0.8.16 | 10 Nov 2026 | EPL-2.0 | Measures line coverage and enforces the floor. |

`org.junit.platform:junit-platform-launcher` is also on the test runtime path; its version comes from the JUnit BOM. Awaitility is not needed: every test that crosses a socket waits on a real reply with a timeout, and every test of time runs on a simulated clock.

## Build and test

```
./gradlew clean check
```

This checks formatting with Spotless, compiles with `-Xlint:all -Werror`, runs the tests and fails if line coverage drops below 0.80. Run `./gradlew spotlessApply` first to fix formatting. Every test talks only to servers it starts on the loopback address, on ports the system picks.

Result on 10 November 2026 (Temurin 25.0.2, macOS): `BUILD SUCCESSFUL` in 4 seconds, 107 tests, 0 failures, line coverage 86.5 percent (1,872 of 2,164 lines). The untested lines are mostly the `main` methods of the command-line classes, which print and call `System.exit`.

## The three lab name servers

```
./gradlew -q serve --args='config/serve.properties'
```

```text
serving on 127.0.0.1 (UDP) for 300 s:
  root      port 5301  zone .                  5 records
  tld       port 5302  zone example.           5 records
  seatwave  port 5303  zone seatwave.example.  11 records
  root      answered 1, dropped 0
  tld       answered 1, dropped 0
  seatwave  answered 29, dropped 0
```

In a second terminal, walk the path the way a recursive resolver does, one referral at a time:

```text
$ dig @127.0.0.1 -p 5301 seatwave.example A +norecurse

; <<>> DiG 9.10.6 <<>> @127.0.0.1 -p 5301 seatwave.example A +norecurse
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 16713
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 1

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; AUTHORITY SECTION:
example.		172800	IN	NS	a.tld-lab.example.

;; ADDITIONAL SECTION:
a.tld-lab.example.	172800	IN	A	127.0.0.1

;; Query time: 0 msec
;; SERVER: 127.0.0.1#5301(127.0.0.1)
;; WHEN: Tue Nov 10 23:49:26 GMT 2026
;; MSG SIZE  rcvd: 105

$ dig @127.0.0.1 -p 5302 seatwave.example A +norecurse

; <<>> DiG 9.10.6 <<>> @127.0.0.1 -p 5302 seatwave.example A +norecurse
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 62675
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 1

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; AUTHORITY SECTION:
seatwave.example.	172800	IN	NS	ns1.seatwave.example.

;; ADDITIONAL SECTION:
ns1.seatwave.example.	172800	IN	A	127.0.0.1

;; Query time: 0 msec
;; SERVER: 127.0.0.1#5302(127.0.0.1)
;; WHEN: Tue Nov 10 23:49:26 GMT 2026
;; MSG SIZE  rcvd: 120

$ dig @127.0.0.1 -p 5303 seatwave.example A +norecurse

; <<>> DiG 9.10.6 <<>> @127.0.0.1 -p 5303 seatwave.example A +norecurse
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 19986
;; flags: qr aa; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; ANSWER SECTION:
seatwave.example.	3600	IN	A	192.0.2.10

;; Query time: 0 msec
;; SERVER: 127.0.0.1#5303(127.0.0.1)
;; WHEN: Tue Nov 10 23:49:26 GMT 2026
;; MSG SIZE  rcvd: 66
```

Each zone has its own port because the verification Mac has only 127.0.0.1 on its loopback interface, and adding addresses would change the machine's network settings. `dig +trace` sends every hop to the glue address on one port, so it cannot walk three zones that differ only by port; the three commands above are what it does, made visible. The other record types:

```text
$ dig @127.0.0.1 -p 5303 seatwave.example AAAA +short
2001:db8::10
$ dig @127.0.0.1 -p 5303 seatwave.example TXT +short
"seatwave-lab: documentation addresses only"
$ dig @127.0.0.1 -p 5303 seatwave.example NS +short
ns1.seatwave.example.
$ dig @127.0.0.1 -p 5303 seatwave.example SOA +short
ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300
$ dig @127.0.0.1 -p 5303 www.seatwave.example A +short
seatwave.example.
192.0.2.10
$ for run in 1 2 3 4 5 6 7 8; do dig @127.0.0.1 -p 5303 book.seatwave.example A +short | head -1; done
192.0.2.10
198.51.100.20
192.0.2.10
198.51.100.20
198.51.100.20
192.0.2.10
192.0.2.10
192.0.2.10
```

## The weighted-routing test

```
./gradlew -q sample --args='config/sample.properties'
./gradlew -q sample --args='config/sample-90-10.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; the lab server on 127.0.0.1:59996 (UDP); A book.seatwave.example.; 1000 warm-up lookups first
book.seatwave.example. 10000 lookups, 0 failed
address          weight configured % measured % difference  allowed  verdict
192.0.2.10           50        50.00      50.48      +0.48     1.50  within
198.51.100.20        50        50.00      49.52      -0.48     1.50  within
lookup latency over UDP on 127.0.0.1: p50 0.033 ms, p99 0.065 ms, max 1.105 ms
Java 25.0.2+10-LTS on Mac OS X aarch64; the lab server on 127.0.0.1:50982 (UDP); A book.seatwave.example.; 1000 warm-up lookups first
book.seatwave.example. 10000 lookups, 0 failed
address          weight configured % measured % difference  allowed  verdict
192.0.2.10           90        90.00      90.29      +0.29     0.90  within
198.51.100.20        10        10.00       9.71      -0.29     0.90  within
lookup latency over UDP on 127.0.0.1: p50 0.030 ms, p99 0.103 ms, max 4.361 ms
```

`server.target=external` with `server.external.port` points the same test at a server already running, for example CoreDNS from `compose.yaml` (`config/sample-coredns.properties`).

## The TTL-change experiment

The simulation (a model, and it says so) runs five arms in about ten seconds:

```
./gradlew -q ttlExperiment --args='config/ttl-experiment.properties'
```

```text
MODEL, not a measurement. 1000 caching resolvers, each asked for seatwave.example. every 10 s at a random phase (seed 20261110);
the switch 192.0.2.10 -> 203.0.113.30 is at 2 old TTLs, a lowering at 1 old TTL; both sites pass every health check (every 10 s).
Server rate and hit ratio are measured over the 3600 s before the switch. Times are seconds after the switch; -1 means never within the run.

arm                   TTL s  lowered  pinning  server/s hit ratio   50% s   95% s   99% s   100% s  retire s old at ret
one-hour               3600       no       0%      0.28    0.9972    1853    3417    3571     3598      7200       0.0%
one-hour-lowered       3600       60       0%      8.24    0.9176      30      57      59       59       120       0.0%
one-day               86400       no       0%      0.01    0.9999   42090   82083   85966    86377    172800       0.0%
one-day-lowered       86400       60       0%     16.32    0.8368      30      57      59       59       120       0.0%
lowered-pinning        3600       60       5%      7.89    0.9211      32      59    1550     1736       120       4.8%

guard decisions:
  one-hour: t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-hour: t=7201s retire 192.0.2.10: refused, too early; allowed from t=14400s
  one-hour-lowered: t=3600s applied: seatwave.example. TTL 3600 -> 60 seconds
  one-hour-lowered: t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-hour-lowered: t=7201s retire 192.0.2.10: refused, too early; allowed from t=7320s
  one-day: t=172800s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-day: t=172801s retire 192.0.2.10: refused, too early; allowed from t=345600s
  one-day-lowered: t=86400s applied: seatwave.example. TTL 86400 -> 60 seconds
  one-day-lowered: t=172800s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  one-day-lowered: t=172801s retire 192.0.2.10: refused, too early; allowed from t=172920s
  lowered-pinning: t=3600s applied: seatwave.example. TTL 3600 -> 60 seconds
  lowered-pinning: t=7200s applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
  lowered-pinning: t=7201s retire 192.0.2.10: refused, too early; allowed from t=7320s
```

The live run takes about half a minute. In terminal 1:

```
./gradlew -q ttlLive --args='config/ttl-live.properties'
```

In terminal 2, while it runs:

```
scripts/ttl-change.sh 5355 seatwave.example 30 build/ttl/dig.txt --run --yes
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; lab server on 127.0.0.1:5355 (UDP); seatwave.example. A 192.0.2.10, TTL 10 s; site stand-ins on 127.0.0.1:18081 and :18082 (TCP)
clients: 3 honest, 1 pinning (keeps answers 30 s at least), 1 JVM InetAddress (networkaddress.cache.ttl=30); each looks up every 250 ms
t=  0.010 s  honest-1   now gets 192.0.2.10
t=  0.011 s  honest-2   now gets 192.0.2.10
t=  0.011 s  honest-3   now gets 192.0.2.10
t=  0.012 s  pinning    now gets 192.0.2.10
t=  0.016 s  jvm        now gets 192.0.2.10
t= 10.212 s  guard approved, applied: seatwave.example. TTL 10 -> 2 seconds
t= 20.166 s  guard approved, applied: seatwave.example. 192.0.2.10 -> 203.0.113.30
t= 20.182 s  retire 192.0.2.10: refused: caches may still send fans to 192.0.2.10; keep it serving; allowed from t=24.166 s
t= 20.437 s  honest-1   now gets 203.0.113.30
t= 20.439 s  honest-2   now gets 203.0.113.30
t= 20.439 s  honest-3   now gets 203.0.113.30
t= 24.266 s  guard approved: retire 192.0.2.10
t= 30.113 s  pinning    now gets 203.0.113.30
t= 30.116 s  jvm        now gets 203.0.113.30

first answer with 203.0.113.30, seconds after the switch:
  honest-1   0.276
  honest-2   0.277
  honest-3   0.278
  pinning    9.951
  jvm        9.954
```

```text
$ scripts/ttl-change.sh 5355 seatwave.example 30 build/ttl/dig.txt --run --yes
plan: 30 queries, one per second, to 127.0.0.1:5355 for seatwave.example A
wrote 30 lines to build/ttl/dig.txt; audit line added to build/ttl/audit.log
$ cat build/ttl/dig.txt
  0 seatwave.example. 10 IN A 192.0.2.10
  1 seatwave.example. 10 IN A 192.0.2.10
  2 seatwave.example. 10 IN A 192.0.2.10
  3 seatwave.example. 10 IN A 192.0.2.10
  4 seatwave.example. 10 IN A 192.0.2.10
  5 seatwave.example. 10 IN A 192.0.2.10
  6 seatwave.example. 10 IN A 192.0.2.10
  7 seatwave.example. 10 IN A 192.0.2.10
  8 seatwave.example. 2 IN A 192.0.2.10
  9 seatwave.example. 2 IN A 192.0.2.10
 10 seatwave.example. 2 IN A 192.0.2.10
 11 seatwave.example. 2 IN A 192.0.2.10
 12 seatwave.example. 2 IN A 192.0.2.10
 13 seatwave.example. 2 IN A 192.0.2.10
 14 seatwave.example. 2 IN A 192.0.2.10
 15 seatwave.example. 2 IN A 192.0.2.10
 16 seatwave.example. 2 IN A 192.0.2.10
 17 seatwave.example. 2 IN A 192.0.2.10
 18 seatwave.example. 2 IN A 203.0.113.30
 19 seatwave.example. 2 IN A 203.0.113.30
 20 seatwave.example. 2 IN A 203.0.113.30
 21 seatwave.example. 2 IN A 203.0.113.30
 22 seatwave.example. 2 IN A 203.0.113.30
 23 seatwave.example. 2 IN A 203.0.113.30
 24 seatwave.example. 2 IN A 203.0.113.30
 25 seatwave.example. 2 IN A 203.0.113.30
 26 seatwave.example. 2 IN A 203.0.113.30
 28 no answer
 30 no answer
 32 no answer
```

The script's clock starts about three seconds after the run's, so its second 8 is the run's 10.2 s (the TTL drops from 10 to 2) and its second 18 is the run's 20.2 s (the new address). Its last lines say "no answer" because the run ended and closed its server. Without `--run` the script only prints its plan; it refuses a port below 1024 and any name outside `seatwave.example`.

## The JDK's own lookup

```
./gradlew -q jdkLookup --args='config/jdk-lookup.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; InetAddress.getByName through the lab resolver to 127.0.0.1:5356; networkaddress.cache.ttl=30, networkaddress.cache.negative.ttl=10
lookup                       result          lookups     first us    median us  server queries
seatwave.example             192.0.2.10         1000      14450.0          1.1               2
nowhere.seatwave.example     UnknownHost        1000       1276.5          3.4               2
```

The JVM asked the server twice per name (A and AAAA) for 1,000 lookups: everything else came from its own cache, whose time is `networkaddress.cache.ttl`, not the record's TTL.

## A zone with four mistakes

```
./gradlew -q serve --args='config/serve-broken.properties'
```

```text
invalid: seatwave: a CNAME at the zone apex seatwave.example. is not allowed, because the apex must also hold the SOA and NS records (RFC 1034 section 3.6.2); use A and AAAA records or a provider's alias record
invalid: seatwave: the records of book.seatwave.example. A have different TTLs; one set has one TTL (RFC 2181 section 5.2)
invalid: seatwave: broken delegation: the name server ns1.seatwave.example. of seatwave.example. has no A or AAAA record (glue) in the zone
invalid: seatwave: a weight is given for 203.0.113.99, which book.seatwave.example. does not have
```

## The settings

Every program reads one settings file in `config/`, and every key is required: there are no defaults in code. A missing or invalid key is reported with its name, all of them in one run. The zone files and the weights files are in `zones/`; the weights use the format of CoreDNS's `loadbalance weighted` policy, so CoreDNS reads the same files.

## Project structure

```text
config/        settings for serve, sample, ttlExperiment, ttlLive and jdkLookup
zones/         the lab zones (root, example., seatwave.example, the live zone) and their weights
zones/broken/  a zone with four mistakes, for failure mode 3
coredns/       the Corefile for the optional CoreDNS layer
scripts/       ttl-change.sh: dig once per second, with a dry run, a confirmation and an audit line
src/main/java/io/plainround/seatwave/dns/
  config/      SettingsReader
  message/     DomainName, RecordType, ResponseCode, RecordData, ResourceRecord, Question, Message,
               MessageProblem and MessageCodec (RFC 1035, checked at the boundary)
  zone/        ZoneFile (the master-file format), Weights, Zone (validation and lookup), Lookup, ZoneLoader
  migration/   ZoneStore (the only way to change a served zone), ApprovedChange, MigrationGuard,
               HealthHistory, Publication, MigrationRefusal
  server/      Responder (one packet in, one packet out), DnsServer (UDP on 127.0.0.1), ServeSettings, ServeCli
  resolver/    Upstream, DnsClient, InMemoryUpstream, CachingResolver, CachePolicy, Resolution, LookupProblem
  sampling/    Sampler, SampleResult, SampleReport, SampleSettings, SampleCli
  experiment/  SimulatedClock, Arm, ArmResult, ExperimentSettings, TtlSimulation, TtlExperimentCli,
               LiveSettings, LiveMigration, SiteEndpoints, TtlLiveCli
  jdk/         LabResolverProvider, LabResolver, JdkLookupCli
src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider
src/test/java/ the tests, one folder per package
```

## What is simplified for teaching

- UDP only, no TCP: a response longer than 512 octets is cut and flagged as truncated, and a real server would then answer the same question over TCP (RFC 7766).
- No EDNS: the server reads and skips the option record dig sends and answers without one.
- Names are stored in lower case, so the server does not echo a question's mixed case back; a resolver that randomizes case as a defense against forged answers would need that.
- Labels are limited to letters, digits, hyphen and underscore, an allow-list stricter than the protocol.
- No zone transfers, no dynamic updates, no DNSSEC.
- The simulation models each recursive resolver as asked at a steady pace; real traffic is burstier.

## Continuous integration

There is no CI configuration on purpose: the course builds and checks each project locally, with the commands above.

## Docker Compose (optional)

`compose.yaml` runs CoreDNS 1.15.0, pinned by tag and digest (checked on Docker Hub on 10 November 2026), with `coredns/Corefile` and the same zone files, on 127.0.0.1 ports 5301 to 5303, read-only and without Linux capabilities. Stop the Java server first, then `docker compose up`, and repeat the `dig` commands and the sampler with `config/sample-coredns.properties`.

[not run] Docker was not running on the verification machine, and the course brief for this module says not to start it:

```text
$ docker info
Server:
ERROR: Cannot connect to the Docker daemon at unix://~/.docker/run/docker.sock. Is the docker daemon running?
errors pretty printing info
```

## License

MIT; see `LICENSE`.
