# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line with a **key** (the name) and a **value**, joined by an equals sign: `serve.seconds=300`. The format comes from Java. The JDK class `java.util.Properties` reads it into a table of text keys and text values.

Everything in a properties file is text. The program decides that `300` is a number and converts it. If the text is not a number, the conversion fails, and the program must say so. In this repository the class `SettingsReader` does the conversion and the saying (see `java-experiment-sampling-config-walkthrough.md`).

## Why this repository uses it

Every command reads its numbers and names from a file in `config/`, not from the code. There are three reasons. A person can change a number without compiling anything. Every number has a comment that says why it has that value. And there are **no defaults in code**: a key that is missing is reported as a problem, never replaced silently by a hidden value, so the file is the only place a number lives.

## Where it appears

| File | Read by | What it sets |
|---|---|---|
| `config/serve.properties` | `ServeCli` (`serve` task) | The three lab name servers: ports, zones, weights, run time, seed. |
| `config/serve-broken.properties` | `ServeCli` | One server for the zone with four mistakes; it must be refused. |
| `config/sample.properties` | `SampleCli` (`sample` task) | The weighted-routing test with weights 50 and 50, against the lab server. |
| `config/sample-90-10.properties` | `SampleCli` | The same test with weights 90 and 10. |
| `config/sample-coredns.properties` | `SampleCli` | The same test against an external server (CoreDNS from `compose.yaml`). |
| `config/ttl-experiment.properties` | `TtlExperimentCli` (`ttlExperiment` task) | The simulated TTL-change experiment: five arms. |
| `config/ttl-live.properties` | `TtlLiveCli` (`ttlLive` task) | The live TTL-change run. |
| `config/jdk-lookup.properties` | `JdkLookupCli` (`jdkLookup` task) | The JDK's own lookup through the lab resolver. |
| `src/main/java/io/plainround/seatwave/dns/config/SettingsReader.java` | all of the above | Loads the file as UTF-8 and reads typed values. |

The zone and weights files that these settings name are in `zones/`; see `dns-zone-files.md`.

## How it works underneath

**The format.** The reader goes through the file line by line. I ran a small program on JDK 25 to check each rule.

- A line that starts with `#` or `!` is a **comment** and is ignored. These files use `#`.
- A blank line is ignored.
- A line like `key=value` sets a key. The separator may be `=`, `:` or white space. These files use `=`. I loaded `c: z` and got the key `c` with the value `z`.
- White space around the key and before the value is dropped. White space at the **end** of a value is kept. I loaded `sp = a b ` and got `a b ` with a trailing space. That is why `SettingsReader.text` calls `strip()`.
- A `#` in the middle of a line is **not** a comment: it is part of the value. I loaded `h=x # not` and got the value `x # not`. So a comment must be on a line of its own, as in every file here.
- If a key appears twice, the last one wins. I loaded `d=1` then `d=2` and got `2`.
- A backslash at the end of a line continues the value on the next line; the leading white space of the next line is dropped (I loaded `long=a\` then `   b` and got `ab`). A backslash elsewhere starts an escape (`\t` is a tab). These files have no backslashes.
- Dots in a key mean nothing to the reader. `listener.seatwave.port` is just that name. The dots are a naming habit that groups related keys. The code builds names such as `listener.` + a listener name + `.port` from a prefix, a name from a list and a suffix.
- A lookup of a key that is not there returns `null`. `SettingsReader.text` turns that into the problem line `<key> is missing`.

**What the program does with it.** `SettingsReader.load` opens the file with `Files.newBufferedReader(path, StandardCharsets.UTF_8)` and passes the reader to `properties.load(reader)`. A `Reader` gives characters decoded as UTF-8. Passing a byte stream instead would make the JDK decode it as ISO-8859-1, which would corrupt any non-ASCII character. The path is relative to the project folder because each Gradle run task sets its working directory to the project folder.

**Units are in the key name.** A key that ends in `.millis` is a number of milliseconds, `.seconds` is seconds, `.count` is a number of things, `.percent` is a percentage. A reader never has to guess a unit.

**A value can be a list.** A comma-separated value is read as a list: `listeners=root,tld,seatwave`, `arm.names=one-hour,one-hour-lowered,...`. An enum value is written in lower case: `lookup.type=a`, `server.target=local`.

**A zone is three keys.** Every program that serves or uses a zone reads `<prefix>.origin` (the apex), `<prefix>.file` (the zone file) and `<prefix>.weights` (a weights file or the word `none`). `ZoneLoader.load(reader, prefix)` reads them. The prefix is `zone` for the sample, experiment and lookup commands, and `listener.<name>.zone` for the servers.

**Every key is required.** The first comment says so. Delete a line and the command prints `invalid: <key> is missing` and exits with status 2. One run reports all problems.

## Walkthrough of this repository's use

### `config/serve.properties`

The whole file:

```properties
# The lab's three name servers (Module 6, Section 8). Every key is required.
# Each zone gets its own UDP port on 127.0.0.1, so dig can walk the path root -> example -> seatwave.example.
listeners=root,tld,seatwave
listener.root.port=5301
listener.root.zone.origin=.
listener.root.zone.file=zones/root.zone
listener.root.zone.weights=none
listener.tld.port=5302
listener.tld.zone.origin=example.
listener.tld.zone.file=zones/example.zone
listener.tld.zone.weights=none
listener.seatwave.port=5303
listener.seatwave.zone.origin=seatwave.example.
listener.seatwave.zone.file=zones/seatwave.example.zone
listener.seatwave.zone.weights=zones/seatwave.example.weights
# Long enough to type the dig commands of Section 8 and Lab 1.
serve.seconds=300
random.seed=20261110
```

Key by key. The code that reads them is `ServeSettings.from`.

- The first two comment lines say what the file is for. Each zone gets its own UDP port on 127.0.0.1, so `dig` can walk the path root, then `example.`, then `seatwave.example.` one referral at a time.
- `listeners=root,tld,seatwave` lists the names of the three listeners, separated by commas. Each name must be a lower-case letter followed by up to 19 lower-case letters, digits or hyphens. The names are used to build the keys below.
- `listener.root.port=5301`, `listener.tld.port=5302` and `listener.seatwave.port=5303` are the UDP ports. Each is a whole number from 1,024 to 65,535 (the ports below 1,024 need administrator rights). No two listeners may share a port (the constructor of `ServeSettings` refuses it).
- `listener.root.zone.origin=.` is the apex of the root listener's zone: a single dot, the root of the name space. `listener.tld.zone.origin=example.` and `listener.seatwave.zone.origin=seatwave.example.` are the other two. A name with the final dot is absolute; the code accepts it with or without.
- `listener.root.zone.file=zones/root.zone`, `listener.tld.zone.file=zones/example.zone` and `listener.seatwave.zone.file=zones/seatwave.example.zone` are the zone files, paths relative to the project folder.
- `listener.root.zone.weights=none` and `listener.tld.zone.weights=none` say these zones have no weighted names. `listener.seatwave.zone.weights=zones/seatwave.example.weights` is the weights file for `book.seatwave.example.`.
- The comment `Long enough to type the dig commands of Section 8 and Lab 1.` explains the next line: `serve.seconds=300` is how long the servers stay up, from 1 to 3,600 seconds. They stop by themselves.
- `random.seed=20261110` is the seed of the generator that picks the first address of a weighted name. The same seed gives the same sequence of choices. It is a whole number from 0 to 2,147,483,647 (the largest `int`).

### `config/serve-broken.properties`

```properties
# Failure mode 3 (Module 6, Section 9): a zone with four mistakes; serve must refuse to load it.
listeners=seatwave
listener.seatwave.port=5303
listener.seatwave.zone.origin=seatwave.example.
listener.seatwave.zone.file=zones/broken/seatwave.example.zone
listener.seatwave.zone.weights=zones/broken/seatwave.example.weights
serve.seconds=300
random.seed=20261110
```

- It has the same keys as `serve.properties`, but a single listener, `seatwave`, on port 5303 with the broken zone and the broken weights from `zones/broken/`. `serve` must refuse to load it: it prints four `invalid:` lines and exits with status 2. The first comment names the lesson: failure mode 3 of Module 6, a zone with four mistakes.
- `serve.seconds=300` and `random.seed=20261110` are required even though the server never starts.

### `config/sample.properties`

```properties
# The weighted-routing test (Module 6, Section 8). Every key is required.
lookup.name=book.seatwave.example
lookup.type=a
lookup.count=10000
lookup.warmup.count=1000
lookup.timeout.millis=1000
# local: start the lab server in this process; external: ask server.external.port (CoreDNS in compose.yaml).
server.target=local
server.external.port=5303
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.zone
zone.weights=zones/seatwave.example.weights
random.seed=20261110
```

Read by `SampleSettings.from`.

- `lookup.name=book.seatwave.example` is the name to look up 10,000 times. It is the weighted pair.
- `lookup.type=a` is the record type, in lower case. Only `a` and `aaaa` are accepted, because only those carry addresses to count.
- `lookup.count=10000` is the number of measured lookups (1 to 1,000,000).
- `lookup.warmup.count=1000` is the number of lookups sent first and thrown away (0 to 1,000,000), so class loading and the JIT compiler (the just-in-time compiler that turns busy code into machine code) are not in the latency numbers.
- `lookup.timeout.millis=1000` is the longest wait for each response, from 1 to 10,000 milliseconds.
- The comment explains the next two keys. `server.target=local` starts the lab server in this process. The other value is `external`: ask the server on `server.external.port`, for example CoreDNS from `compose.yaml`. `server.external.port=5303` is the port of that external server (1,024 to 65,535). The key is required even when the target is local, because the reader has no defaults.
- `zone.origin=seatwave.example.`, `zone.file=zones/seatwave.example.zone` and `zone.weights=zones/seatwave.example.weights` are the zone and its weights. Even with an external target the zone is read, because the configured weights are compared with the measured shares.
- `random.seed=20261110` seeds the weighted choice of a local server.

### `config/sample-90-10.properties`

```properties
# Lab 2 (Module 6, Section 11): weights 90 to 10, verified over 10,000 lookups. Every key is required.
lookup.name=book.seatwave.example
lookup.type=a
lookup.count=10000
lookup.warmup.count=1000
lookup.timeout.millis=1000
# local: start the lab server in this process; external: ask server.external.port (CoreDNS in compose.yaml).
server.target=local
server.external.port=5303
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.zone
zone.weights=zones/seatwave.example.weights-90-10
random.seed=20261110
```

- The same keys as `sample.properties` with the same values, except `zone.weights=zones/seatwave.example.weights-90-10`, the weights file that gives 90 to the first site and 10 to the second. The first comment names the lab: Lab 2 of Module 6.

### `config/sample-coredns.properties`

```properties
# The weighted-routing test against CoreDNS from compose.yaml (Module 6, Section 8). Every key is required.
lookup.name=book.seatwave.example
lookup.type=a
lookup.count=10000
lookup.warmup.count=1000
lookup.timeout.millis=1000
# local: start the lab server in this process; external: ask server.external.port (CoreDNS in compose.yaml).
server.target=external
server.external.port=5303
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.zone
zone.weights=zones/seatwave.example.weights
random.seed=20261110
```

- The same keys again, with `server.target=external`: the program starts no server and asks the one on port 5303, which `compose.yaml` runs as CoreDNS. The zone and the weights files are read for the configured weights, which CoreDNS reads too (the format is the same).

### `config/jdk-lookup.properties`

```properties
# The JDK's own lookup against the lab server (Module 6, Section 6). Every key is required.
server.port=5356
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.zone
zone.weights=zones/seatwave.example.weights
# The JDK's documented defaults, written out: 30 s for answers, 10 s for failed lookups.
jvm.cache.ttl.seconds=30
jvm.cache.negative.ttl.seconds=10
lookup.name=seatwave.example
lookup.missing.name=nowhere.seatwave.example
lookup.count=1000
lookup.timeout.millis=1000
```

Read by `JdkLookupCli.main`.

- `server.port=5356` is the UDP port of the lab server that the command starts for itself (1,024 to 65,535).
- `zone.origin`, `zone.file` and `zone.weights` are the three zone keys, here with the `seatwave.example.` zone and its weights.
- The comment says that the next two lines are the JDK's documented defaults, written out. `jvm.cache.ttl.seconds=30` is how long the JVM caches a successful lookup (`networkaddress.cache.ttl`), and `jvm.cache.negative.ttl.seconds=10` how long it caches a failed one (`networkaddress.cache.negative.ttl`). Each is 0 to 600 seconds.
- `lookup.name=seatwave.example` is a name that exists; `lookup.missing.name=nowhere.seatwave.example` is a name that does not.
- `lookup.count=1000` is how many times each name is looked up (1 to 100,000).
- `lookup.timeout.millis=1000` is the timeout of each DNS query the lab resolver sends (1 to 10,000 milliseconds).

### `config/ttl-experiment.properties`

```properties
# The simulated TTL-change experiment (Module 6, Sections 6, 7 and 11). Every key is required.
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.zone
zone.weights=zones/seatwave.example.weights
# The SeatWave machine moves to a new machine: both are documentation addresses (RFC 5737).
migration.name=seatwave.example.
migration.old.address=192.0.2.10
migration.new.address=203.0.113.30
# 1,000 recursive resolvers, each asked by its fans every 10 s.
resolvers.count=1000
resolvers.query.interval.seconds=10
# An honest resolver keeps an answer for its TTL, up to one day; a "no" for up to 3 hours.
resolvers.maximum.hold.seconds=86400
resolvers.maximum.negative.hold.seconds=10800
# Each site is checked every 10 s; the guard accepts at most 30 s between healthy checks.
health.interval.seconds=10
health.max.gap.seconds=30
# The server's query rate is measured over the hour before the switch.
rate.window.seconds=3600
random.seed=20261110
arm.names=one-hour,one-hour-lowered,one-day,one-day-lowered,lowered-pinning
# SIMPLE: one hour, never lowered.
arm.one-hour.ttl.seconds=3600
arm.one-hour.lowered.ttl.seconds=3600
arm.one-hour.pinning.percent=0
arm.one-hour.pinning.hold.seconds=0
arm.one-hour-lowered.ttl.seconds=3600
arm.one-hour-lowered.lowered.ttl.seconds=60
arm.one-hour-lowered.pinning.percent=0
arm.one-hour-lowered.pinning.hold.seconds=0
# The walkthrough's record: 86,400 s (one day), never lowered, then lowered to 60 s.
arm.one-day.ttl.seconds=86400
arm.one-day.lowered.ttl.seconds=86400
arm.one-day.pinning.percent=0
arm.one-day.pinning.hold.seconds=0
arm.one-day-lowered.ttl.seconds=86400
arm.one-day-lowered.lowered.ttl.seconds=60
arm.one-day-lowered.pinning.percent=0
arm.one-day-lowered.pinning.hold.seconds=0
# Failure mode 4: 5 percent of resolvers keep every answer at least 30 minutes, whatever the TTL.
arm.lowered-pinning.ttl.seconds=3600
arm.lowered-pinning.lowered.ttl.seconds=60
arm.lowered-pinning.pinning.percent=5
arm.lowered-pinning.pinning.hold.seconds=1800
```

Read by `ExperimentSettings.from`.

- `zone.origin`, `zone.file` and `zone.weights` are the zone at the start of the experiment.
- The comment above `migration.name` says that both addresses are documentation addresses (RFC 5737). `migration.name=seatwave.example.` is the name that moves, `migration.old.address=192.0.2.10` is the address it moves from and `migration.new.address=203.0.113.30` the address it moves to. The zone must give the name the old address, or the settings are refused.
- `resolvers.count=1000` is the number of caching resolvers (1 to 100,000); `resolvers.query.interval.seconds=10` is how often each is asked by its fans (seconds, 1 to 604,800).
- The comment says an honest resolver keeps an answer for its TTL up to one day and a "no" for up to three hours. `resolvers.maximum.hold.seconds=86400` is one day; `resolvers.maximum.negative.hold.seconds=10800` is three hours.
- `health.interval.seconds=10` is how often each site is checked; `health.max.gap.seconds=30` is the longest gap between healthy checks the guard accepts.
- `rate.window.seconds=3600` is the hour before the switch over which the server's query rate and the hit ratio are measured.
- `random.seed=20261110` seeds every random choice, so a run repeats exactly.
- `arm.names=...` lists the five arms. Each name is a lower-case letter followed by up to 29 letters, digits or hyphens.
- For each arm, four keys: `arm.<name>.ttl.seconds` (the starting TTL), `arm.<name>.lowered.ttl.seconds` (the TTL after lowering; the same value means "not lowered"; it must not exceed the starting TTL), `arm.<name>.pinning.percent` (0 to 100: the share of resolvers that pin) and `arm.<name>.pinning.hold.seconds` (how long they keep an answer at least).
  - `one-hour`: 3,600 seconds, never lowered, no pinning. The comment calls it SIMPLE.
  - `one-hour-lowered`: 3,600 lowered to 60.
  - `one-day`: 86,400 seconds (the walkthrough's record), never lowered.
  - `one-day-lowered`: 86,400 lowered to 60.
  - `lowered-pinning`: 3,600 lowered to 60, and 5 percent of resolvers keep every answer at least 1,800 seconds (30 minutes), whatever the TTL. The comment names it failure mode 4.

### `config/ttl-live.properties`

```properties
# The live TTL-change run (Module 6, Sections 8 and 11). Every key is required.
# The lab server, where scripts/ttl-change.sh points dig while the run lasts.
server.port=5355
zone.origin=seatwave.example.
zone.file=zones/seatwave.example.live.zone
zone.weights=none
migration.name=seatwave.example.
migration.old.address=192.0.2.10
migration.new.address=203.0.113.30
# The TTL starts at the zone's 10 s and is lowered to 2 s one old TTL before the switch.
migration.lowered.ttl.seconds=2
# Loopback TCP ports that stand for the old and the new site, checked every 500 ms.
site.old.port=18081
site.new.port=18082
health.interval.millis=500
health.max.gap.millis=1500
health.timeout.millis=200
poll.interval.millis=250
clients.honest.count=3
# A client that keeps every answer at least 30 s, whatever the TTL (failure mode 4).
clients.pinning.hold.seconds=30
# The JVM's own cache time for answers: the JDK's documented default, written out.
jvm.cache.ttl.seconds=30
lookup.timeout.millis=1000
observe.after.switch.seconds=40
random.seed=20261110
```

Read by `LiveSettings.from`.

- `server.port=5355` is the UDP port of the lab server, where `scripts/ttl-change.sh` points `dig` while the run lasts (the comment above it).
- `zone.origin`, `zone.file=zones/seatwave.example.live.zone` and `zone.weights=none`: the live zone has a 10-second TTL and no weights.
- `migration.name`, `migration.old.address` and `migration.new.address` as in the experiment.
- The comment says the TTL starts at the zone's 10 seconds and is lowered one old TTL before the switch. `migration.lowered.ttl.seconds=2` is the lowered value (1 to 600).
- The comment says the next two lines are loopback TCP ports that stand for the old and the new site, checked every 500 milliseconds. `site.old.port=18081` and `site.new.port=18082` are those ports (1,024 to 65,535); `health.interval.millis=500` is the check interval.
- `health.max.gap.millis=1500` is the largest gap between healthy checks the guard accepts; `health.timeout.millis=200` is the longest wait for one check's TCP connection; `poll.interval.millis=250` is how often every client looks the name up. Each is 1 to 60,000 milliseconds.
- `clients.honest.count=3` is the number of honest caching clients (0 to 20).
- The comment says the next client keeps every answer at least 30 seconds, whatever the TTL (failure mode 4). `clients.pinning.hold.seconds=30` is that hold (0 to 600).
- The comment explains `jvm.cache.ttl.seconds=30`: the JVM's own cache time for answers, the JDK's documented default, written out.
- `lookup.timeout.millis=1000` is the timeout of each DNS query.
- `observe.after.switch.seconds=40` is how long to keep watching after the switch if retirement or a client does not finish first (1 to 600).
- `random.seed=20261110` seeds the server's weighted choice.

## Try it yourself

All commands run in the repository folder. Copies of settings files go in a scratch folder, never in `config/`.

**1. Check the properties rules.** Save this as `Props.java` in a scratch folder and run `java Props.java`:

```text
import java.io.StringReader;
import java.util.Properties;
import java.util.TreeMap;

public class Props {
  public static void main(String[] args) throws Exception {
    Properties p = new Properties();
    p.load(new StringReader("# c\nk=v\nh=x # not\nd=1\nd=2\nsp = a b \nc: z\n! bang\nlong=a\\\n   b\n"));
    System.out.println(new TreeMap<>(p) + " sp=[" + p.getProperty("sp") + "]");
  }
}
```

```text
{c=z, d=2, h=x # not, k=v, long=ab, sp=a b } sp=[a b ]
```

Both comment lines gave no key, `h` kept the `# not`, `d` is 2, `long` joined its two lines, and `sp` kept its trailing space.

**2. Break `serve.properties` in three ways and see every problem at once.** Make a copy without the `listener.tld.port` line, with `serve.seconds=0`, and with the root port changed to 5303:

```text
./gradlew -q serve --args='<path of your copy>'
```

```text
invalid: serve.seconds must be from 1 to 3600, but was 0
invalid: listener.tld.port is missing
```

Both mistakes in one run, then exit status 2. A shared port is reported the same way: with only `listener.tld.port` changed to the root listener's port, `ServeSettings.from` returns the line `two listeners share a port`, and the program prints it as an `invalid:` line and exits with status 2.

**3. Give `sample.properties` two unknown words.** A copy with `server.target=remote` and `lookup.type=mx`:

```text
./gradlew -q sample --args='<path of your copy>'
```

```text
invalid: lookup.type has an unknown value: mx
invalid: server.target has an unknown value: remote
```

**4. Break the experiment's arm list.** A copy with `arm.names=one-hour,Bad_Name`:

```text
./gradlew -q ttlExperiment --args='<path of your copy>'
```

```text
invalid: arm.names has Bad_Name, but allows only one-hour
```

A name that does not match the pattern is not in the allow-list, so it is reported.

## Mistakes people make

- **Putting a comment at the end of a value line.** `serve.seconds=300 # five minutes` makes the value `300 # five minutes`, which is not a number. This repository puts comments on their own lines.
- **Trailing spaces.** They stay in the value. `SettingsReader.text` strips them.
- **A typo in a key.** The key is then missing, and the reader reports `... is missing`. There is no silent default.
- **A backslash in a Windows path.** It is an escape. Use forward slashes (`zones/root.zone`).
- **Hiding a default in code.** If the code had a fallback for `lookup.count`, nobody could tell from the file what a run used. Here every key is required.
- **Units guessed.** The unit is in the key name (`.millis`, `.seconds`).
- **A non-UTF-8 file.** `load` decodes as UTF-8, so save the file as UTF-8.
- **Giving two listeners one port.** Two sockets cannot share a UDP port. `ServeSettings.from` checks this and reports `two listeners share a port` with the other settings problems. The `ServeSettings` constructor checks it too, so the rule holds even for code that builds the settings directly.

## Check yourself

1. How does the reader treat `serve.seconds=300 # five minutes`? (The whole text `300 # five minutes` is the value, so reading it as a number fails and the reader reports it.)
2. If the same key appears twice, which value is used? (The last one.)
3. What happens when you delete a key from `serve.properties`? (The reader records `<key> is missing`, the command prints every problem it found, and exits with status 2.)
4. Why is `server.external.port` present in `sample.properties` when the target is `local`? (Every key is required: there are no defaults in code.)
5. Why does `random.seed` exist? (So the weighted choices are the same on every run and every machine, and the table is reproducible.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- `java.util.Properties` and the exact file format (see the `load(Reader)` method): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- `java.nio.file.Files.newBufferedReader`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
- `java.nio.charset.StandardCharsets`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/charset/StandardCharsets.html
- The security properties `networkaddress.cache.ttl` and `networkaddress.cache.negative.ttl`, in the `InetAddress` documentation: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/InetAddress.html
- RFC 5737, IPv4 Address Blocks Reserved for Documentation: https://www.rfc-editor.org/rfc/rfc5737
