# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-11

### Added

- `tech/`: 30 beginner notes, one per technology, with line-by-line walkthroughs of the main code,
  the tests, the build files and the scripts, and an index with a reading order.

### Fixed

- `ServeSettings.from` reports two listeners on one port as a settings problem; before, the
  program stopped with a stack trace.
- `DnsClient` names the port when nothing is listening; before, the message ended in "null".
- `scripts/ttl-change.sh` refuses a port or a count with a leading zero, which Bash would have
  read as an octal number.

## [1.0.0] - 2026-11-10

### Added

- A DNS message codec written against RFC 1035, with every incoming packet checked at the boundary:
  header length, counts against the packet size, label and name lengths, compression pointers that
  must point backward (so no packet can make the decoder loop), record data lengths, trailing bytes.
- A zone-file reader for the master-file format CoreDNS reads, and zone validation that refuses a
  CNAME at the apex, a dangling or looping CNAME, differing TTLs in one record set, duplicates, a
  name server without glue, and weights for addresses a name does not have.
- An authoritative UDP server on 127.0.0.1 (ports 1024 and up only) answering A, AAAA, CNAME, NS,
  SOA and TXT, with referrals and glue, NXDOMAIN and no-data answers carrying the SOA for negative
  caching, REFUSED outside the zone, FORMERR for unreadable packets and truncation above 512 octets.
- Weighted answers in the manner of CoreDNS's `loadbalance weighted` policy, from the same weights files.
- Three lab zones (a stand-in root, `example.` and `seatwave.example`) served on three ports.
- A UDP client with random identifiers and response matching, and a caching resolver on an injected
  clock with minimum and maximum holds, negative caching and serving stale answers.
- The weighted-routing test, the simulated TTL-change experiment and the live TTL-change run.
- The migration guard and the zone store whose only mutator takes an approved change.
- A resolver provider that points the JDK's `InetAddress` at the lab server, and a measurement of the
  JVM's own cache.
- `scripts/ttl-change.sh`: dig once per second, a dry run by default, a confirmation, an allow-list
  of port and name, and an audit line.
- Optional Docker Compose file running CoreDNS 1.15.0, pinned by tag and digest, on the same zones.
- Build with a pinned Gradle wrapper, dependency lock files, `-Xlint:all -Werror`, Spotless with
  google-java-format, a JaCoCo line-coverage floor of 0.80 and an XML coverage report.
