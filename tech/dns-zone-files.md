# DNS zone files and weights files (the `zones/` folder)

## What it is

A **zone file** is a plain text file that lists the DNS records of one zone, one record per line. The format is called the **master file** format and is defined in RFC 1035 section 5. Name servers such as BIND and CoreDNS read the same format, so the files in `zones/` work in this repository's Java server and in CoreDNS.

A **weights file** is a second, smaller text file. It says how likely each address of one name is to be put first in an answer. This repository uses the format of CoreDNS's `loadbalance weighted` policy, so CoreDNS reads the same files.

A **record** is one fact: an owner name, a time to live, a class, a type and data. `seatwave.example.  3600  IN  A  192.0.2.10` says "the name `seatwave.example.` has the IPv4 address 192.0.2.10, and a cache may keep that for 3600 seconds". `dns-protocol.md` explains the idea of a zone and of each type from zero.

## Why this repository uses it

SeatWave publishes its addresses in a zone. The zone files are the data of the whole lab: the three lab servers read them (`serve`), the weighted-routing test reads the weights, the experiments read the zone and change its TTLs, and the broken copy in `zones/broken/` shows the four mistakes the loader must refuse. Writing the data as files in the standard format means a reader can open one, change one line, and see the answer change in `dig`.

## Where it appears

| File | What it holds |
|---|---|
| `zones/root.zone` | A stand-in for the root zone: it delegates `example.`. Served on port 5301. |
| `zones/example.zone` | A stand-in for the top-level domain `example.`: it delegates `seatwave.example.`. Port 5302. |
| `zones/seatwave.example.zone` | SeatWave's own zone. Port 5303. |
| `zones/seatwave.example.weights` | Weights 50 and 50 for `book.seatwave.example.`. |
| `zones/seatwave.example.weights-90-10` | Weights 90 and 10 for the same name (Lab 2). |
| `zones/seatwave.example.live.zone` | The zone of the live TTL-change run, with a 10-second TTL. |
| `zones/broken/seatwave.example.zone` | A copy with three mistakes in the records. |
| `zones/broken/seatwave.example.weights` | The fourth mistake, in the weights. |

The code that reads them is `ZoneFile` and `Weights` (see `java-zone-and-server-walkthrough.md`). The settings that name them are in `java-properties.md`. `coredns/Corefile` points CoreDNS at the same files.

## How it works underneath

**The names of the lab.** `example.` is reserved for documentation (RFC 2606), so these names can never clash with a real one. Every server address that the zones hold is `127.0.0.1`, the loopback address of this machine, so no query ever leaves it. Every address a name resolves to is from a block reserved for documentation: `192.0.2.0/24`, `198.51.100.0/24` and `203.0.113.0/24` for IPv4 (RFC 5737), and `2001:db8::/32` for IPv6 (RFC 3849). Nothing is ever on those addresses.

**The layout of a line.** The fields are separated by spaces or tabs. The order is: owner, TTL, class, type, data.

```text
www    3600 IN CNAME seatwave.example.
```

- The **owner** is the name the record belongs to: `www`.
- The **TTL** is in seconds. The TTL and the class may be given in either order, and either may be left out (a missing TTL takes the `$TTL` default).
- The **class** is `IN`, the internet. It is the only class in practice.
- The **type** says what kind of data follows: `A`, `AAAA`, `NS`, `CNAME`, `SOA` or `TXT` here.
- The **data** depends on the type.

**Names.** A name that ends in a dot is **absolute**: `seatwave.example.` is complete. A name without a final dot is **relative**: the current origin is added to it. Under `$ORIGIN seatwave.example.` the owner `www` means `www.seatwave.example.`. The symbol `@` means the origin itself. A line that starts with a space or tab has a **blank owner**: it uses the owner of the line above.

**Directives.** `$ORIGIN name` sets the origin. `$TTL seconds` sets the default TTL for records that give none. Other directives (`$INCLUDE`) are not supported here.

**Comments.** A semicolon starts a comment that runs to the end of the line. A semicolon inside a quoted string is part of the string. In a **weights file** the comment character is `#` (a line that starts with it), as in CoreDNS.

**Record types used here.**

| Type | Data | Meaning |
|---|---|---|
| `SOA` | primary server, mailbox, serial, refresh, retry, expire, minimum | The start of authority of a zone: who is in charge, and the timers. |
| `NS` | a name | A name server of the zone. At the apex it is the zone's own; below the apex it is a **delegation** to a child zone. |
| `A` | an IPv4 address | The address of a name. |
| `AAAA` | an IPv6 address | The same for IPv6. |
| `CNAME` | a name | An alias: the owner name is another name for the target. |
| `TXT` | quoted strings | Free text. |

The SOA fields in order: the **primary server** (the name server that holds the master copy); the **mailbox** of the person responsible, written as a name where the first dot stands for the `@` (`hostmaster.seatwave.example.` is `hostmaster@seatwave.example`); the **serial number** (a version number that rises with every change, here the date and a count: 2026111001); the **refresh** time (how often a secondary server checks for a new serial); the **retry** time (how soon it tries again after a failed check); the **expire** time (how long it keeps answering without reaching the primary); and the **minimum**, which (RFC 2308) is the TTL for negative answers: how long a cache may keep "this name does not exist".

**A delegation and its glue.** The root zone holds `example. NS a.tld-lab.example.`. That says: "the zone `example.` is run by the server named `a.tld-lab.example.`". To ask that server, a resolver needs its address, so the root zone also holds `a.tld-lab.example. A 127.0.0.1`. An address held in a parent zone for a name server of a child zone is called **glue**. Without glue, to find `a.tld-lab.example.` you would have to ask `example.`, which you cannot reach without its address.

**The weights format.**

```text
book.seatwave.example
192.0.2.10 50
198.51.100.20 50
```

A line with one word is a name. Each line after it with two words is an address and a weight from 1 to 255, for that name. An address of the name with no weight counts as 1. The server picks the first address at random in proportion to the weights: 50 and 50 is half and half; 90 and 10 is nine in ten against one in ten.

## Walkthrough of this repository's use

### `zones/root.zone`

```text
; The lab's stand-in for the root zone. It delegates only "example." (reserved for documentation,
; RFC 2606), and every name server address is 127.0.0.1, so no query ever leaves this machine.
; The TTLs copy the ones the real root zone uses for its own NS set (518400) and a delegation (172800).
$ORIGIN .
$TTL 86400
@                    86400  IN SOA   a.root-lab.example. hostmaster.root-lab.example. 2026111001 1800 900 604800 86400
@                    518400 IN NS    a.root-lab.example.
a.root-lab.example.  518400 IN A     127.0.0.1
example.             172800 IN NS    a.tld-lab.example.
a.tld-lab.example.   172800 IN A     127.0.0.1
```

- Lines 1 to 3 are comments (they start with a semicolon). They say the file stands in for the root zone and delegates only `example.`, which RFC 2606 reserves for documentation. Every name server address is 127.0.0.1, so no query ever leaves this machine. The TTLs copy the ones the real root zone uses for its own NS set (518,400 seconds, six days) and for a delegation (172,800 seconds, two days).
- `$ORIGIN .` sets the origin to the root, written as a single dot. Relative names would get no suffix.
- `$TTL 86400` is the default TTL, one day. Every record below gives its own TTL, so the default is not used.
- The SOA line: owner `@` (the root), TTL 86,400, class `IN`, type `SOA`, primary server `a.root-lab.example.`, mailbox `hostmaster.root-lab.example.`, serial `2026111001`, refresh 1,800, retry 900, expire 604,800 and minimum 86,400.
- The NS line gives the root zone its name server `a.root-lab.example.` with TTL 518,400.
- The A line gives that name server the address 127.0.0.1. (The name is absolute, so the origin is not added.)
- `example. 172800 IN NS a.tld-lab.example.` is the **delegation**: the name `example.` belongs to a child zone run by `a.tld-lab.example.`. A query for any name under `example.` gets a referral that names this server.
- The last line is the glue for it: the address of `a.tld-lab.example.`. The server sends it in the additional section so the resolver can go on without another question.

### `zones/example.zone`

```text
; The lab's stand-in for a top-level domain. It delegates seatwave.example to SeatWave's own
; name server and carries its address as glue, because that name server lives inside the zone it serves.
$ORIGIN example.
$TTL 86400
@              86400  IN SOA   a.tld-lab.example. hostmaster.tld-lab.example. 2026111001 1800 900 604800 3600
@              172800 IN NS    a.tld-lab.example.
a.tld-lab      172800 IN A     127.0.0.1
seatwave       172800 IN NS    ns1.seatwave.example.
ns1.seatwave   172800 IN A     127.0.0.1
```

- The two comment lines say the file stands in for a top-level domain. It delegates `seatwave.example` to SeatWave's own name server and carries that server's address as glue, because that name server lives inside the zone it serves.
- `$ORIGIN example.` means a relative owner such as `seatwave` is `seatwave.example.`. `$TTL 86400` is again an unused default.
- The SOA line: the apex `@` is `example.`; primary server `a.tld-lab.example.`, mailbox `hostmaster.tld-lab.example.`, serial 2026111001, refresh 1,800, retry 900, expire 604,800, minimum 3,600.
- `@ 172800 IN NS a.tld-lab.example.` is the zone's name server. `a.tld-lab 172800 IN A 127.0.0.1` is its address; `a.tld-lab` is relative, so it is `a.tld-lab.example.`.
- `seatwave 172800 IN NS ns1.seatwave.example.` is the delegation of `seatwave.example.` to SeatWave's name server.
- `ns1.seatwave 172800 IN A 127.0.0.1` is the glue, held here because `ns1.seatwave.example.` is inside the zone it serves.
- The columns are aligned with spaces only to make the file easy to read; the reader splits on any run of white space.

### `zones/seatwave.example.zone`

```text
; SeatWave's own zone: the only part of the name space SeatWave owns (Module 6, Section 8).
; Every address is from a range reserved for documentation (RFC 5737, RFC 3849) except the lab
; name server's, which is the loopback address the lab server listens on.
$ORIGIN seatwave.example.
$TTL 3600
; SOA timers: refresh 2 h, retry 15 min, expire 14 days; the last field, 300 s, is how long a "no such name" may be cached.
@      3600 IN SOA   ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300
@      3600 IN NS    ns1.seatwave.example.
ns1    3600 IN A     127.0.0.1
; SIMPLE: one A record with a one-hour TTL pointing at the single SeatWave machine.
@      3600 IN A     192.0.2.10
@      3600 IN AAAA  2001:db8::10
@      3600 IN TXT   "seatwave-lab: documentation addresses only"
www    3600 IN CNAME seatwave.example.
; The two SeatWave instances, one per site.
site1  3600 IN A     192.0.2.10
site2  3600 IN A     198.51.100.20
; The weighted pair: both sites under one name, with a short TTL; the weights file picks the first.
book   60   IN A     192.0.2.10
book   60   IN A     198.51.100.20
```

- The first three comment lines: this is SeatWave's own zone, the only part of the name space SeatWave owns (Module 6, Section 8). Every address is from a range reserved for documentation (RFC 5737 and RFC 3849) except the lab name server's, which is the loopback address the lab server listens on.
- `$ORIGIN seatwave.example.` and `$TTL 3600`: relative names are under `seatwave.example.`; the default TTL is one hour.
- The comment above the SOA explains the timers: refresh two hours (7,200), retry 15 minutes (900), expire 14 days (1,209,600), and the last field, 300 seconds, is how long a "no such name" may be cached.
- The SOA line: the apex, TTL 3,600, primary server `ns1.seatwave.example.`, mailbox `hostmaster.seatwave.example.`, serial 2026111001 and the five timers.
- `@ 3600 IN NS ns1.seatwave.example.` names the zone's name server; `ns1 3600 IN A 127.0.0.1` gives it an address (glue inside the zone).
- The comment `SIMPLE:` introduces the simplest case: one A record with a one-hour TTL pointing at the single SeatWave machine. `@ 3600 IN A 192.0.2.10` is that record. `@ 3600 IN AAAA 2001:db8::10` is its IPv6 twin. `@ 3600 IN TXT "seatwave-lab: documentation addresses only"` is a text record; the quotation marks make the whole sentence one string, even with spaces and a colon.
- `www 3600 IN CNAME seatwave.example.` makes `www.seatwave.example.` an alias of the apex. Because the target is inside the zone, the server adds the A record of the target to the answer.
- The comment `The two SeatWave instances, one per site.` introduces `site1 3600 IN A 192.0.2.10` and `site2 3600 IN A 198.51.100.20`: one name for each site.
- The comment above `book` says it is the weighted pair: both sites under one name, with a short TTL (60 seconds); the weights file picks the first. `book 60 IN A 192.0.2.10` and `book 60 IN A 198.51.100.20` are two A records of one name, a **record set**. All records of one set must have the same TTL (RFC 2181 section 5.2).

### `zones/seatwave.example.live.zone`

```text
; The live TTL-change run (Module 6, Sections 8 and 11): the same apex record as the main zone,
; with a 10-second TTL so the whole migration happens in about a minute.
$ORIGIN seatwave.example.
@      10 IN SOA   ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 5
@      10 IN NS    ns1.seatwave.example.
ns1    10 IN A     127.0.0.1
@      10 IN A     192.0.2.10
```

- The comment says this is the zone of the live TTL-change run: the same apex record as the main zone, with a 10-second TTL so the whole migration happens in about a minute.
- The SOA has TTL 10 and minimum 5 (the last number). The timers are those of the main zone.
- `@ 10 IN NS ns1.seatwave.example.` and `ns1 10 IN A 127.0.0.1` are the name server and its address.
- `@ 10 IN A 192.0.2.10` is the record that moves to 203.0.113.30 during the run.

### `zones/seatwave.example.weights`

```text
# Weights for book.seatwave.example, in the format of CoreDNS's loadbalance weighted policy.
# Equal weights: each site is first in about half of the answers.
book.seatwave.example
192.0.2.10 50
198.51.100.20 50
```

- Two comment lines (they start with `#`): the file is in the format of CoreDNS's `loadbalance weighted` policy, and equal weights make each site first in about half of the answers.
- `book.seatwave.example` is the name the weights belong to (a name with no final dot is accepted).
- `192.0.2.10 50` and `198.51.100.20 50` give each address the weight 50. Weights of 50 and 50 mean a half each.

### `zones/seatwave.example.weights-90-10`

```text
# Lab 2: 90 to 10. Site 1 should be first in about 90 percent of the answers.
book.seatwave.example
192.0.2.10 90
198.51.100.20 10
```

- The comment names Lab 2: the first site should be first in about 90 percent of the answers.
- The name line, then `192.0.2.10 90` and `198.51.100.20 10`: nine chances in ten against one in ten.

### `zones/broken/seatwave.example.zone`

```text
; A broken copy of SeatWave's zone for failure mode 3. Each mistake is one a real change can make.
$ORIGIN seatwave.example.
$TTL 3600
@      3600 IN SOA   ns1.seatwave.example. hostmaster.seatwave.example. 2026111002 7200 900 1209600 300
; Mistake 1: the name server's address (glue) was deleted with an "unused" record.
@      3600 IN NS    ns1.seatwave.example.
; Mistake 2: the apex was turned into an alias of a hosting name.
@      3600 IN CNAME seatwave-prod.hosting.example.
; Mistake 3: one record of the pair was edited with a new TTL.
book   60   IN A     192.0.2.10
book   300  IN A     198.51.100.20
```

This zone is for failure mode 3. Each mistake is one a real change can make. The first comment line says so. Loading it with `serve` produces the messages quoted in the README.

- `$ORIGIN seatwave.example.` and `$TTL 3600` as in the good zone.
- The SOA has the serial 2026111002 (one more than the good zone: someone changed the file).
- Mistake 1: the comment says the name server's address (glue) was deleted with an "unused" record. The line `@ 3600 IN NS ns1.seatwave.example.` is still there, but no `ns1 ... A` line follows. The loader reports `broken delegation: the name server ns1.seatwave.example. of seatwave.example. has no A or AAAA record (glue) in the zone`.
- Mistake 2: the apex was turned into an alias of a hosting name. `@ 3600 IN CNAME seatwave-prod.hosting.example.` is refused: `a CNAME at the zone apex seatwave.example. is not allowed ...` (RFC 1034 section 3.6.2), because the apex must also hold the SOA and NS records and a CNAME cannot share a name with other records.
- Mistake 3: one record of the pair was edited with a new TTL. `book 60 IN A 192.0.2.10` and `book 300 IN A 198.51.100.20` have different TTLs inside one set: `the records of book.seatwave.example. A have different TTLs; one set has one TTL (RFC 2181 section 5.2)`.

### `zones/broken/seatwave.example.weights`

```text
# Mistake 4: the weight still names the address of a site that was removed.
book.seatwave.example
192.0.2.10 90
203.0.113.99 10
```

- Mistake 4, in the comment: the weight still names the address of a site that was removed.
- The name line, then `192.0.2.10 90` (valid) and `203.0.113.99 10`, an address that `book.seatwave.example.` does not have. The loader reports `a weight is given for 203.0.113.99, which book.seatwave.example. does not have`.

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only. `dig` is a command-line DNS client (`dig.md`).

**1. Serve the zones** and keep this terminal open:

```text
./gradlew -q serve --args='config/serve.properties'
```

**2. Read the weighted pair.** Eight lookups of `book`, keeping the first address of each answer:

```text
for run in 1 2 3 4 5 6 7 8; do dig @127.0.0.1 -p 5303 book.seatwave.example A +short | head -1; done
```

```text
198.51.100.20
192.0.2.10
198.51.100.20
198.51.100.20
198.51.100.20
192.0.2.10
192.0.2.10
198.51.100.20
```

Both addresses appear first. The server moves one of the two records to the top at random, with probabilities in proportion to the weights. The generator is seeded with `random.seed`, so a freshly started server gives this same sequence for the same queries (I got the same eight on two separate runs); 5 of 8 were the second site and 3 the first, a normal spread for 50 and 50. The whole answer always holds both records:

```text
dig @127.0.0.1 -p 5303 book.seatwave.example A +norecurse
```

```text
;; ANSWER SECTION:
book.seatwave.example.	60	IN	A	192.0.2.10
book.seatwave.example.	60	IN	A	198.51.100.20
```

**3. Read the other types.**

```text
dig @127.0.0.1 -p 5303 seatwave.example AAAA +short
dig @127.0.0.1 -p 5303 seatwave.example TXT +short
```

```text
2001:db8::10
"seatwave-lab: documentation addresses only"
```

**4. See a delegation and its glue.** Ask the `example.` server about `seatwave.example`:

```text
dig @127.0.0.1 -p 5302 seatwave.example NS +norecurse
```

```text
;; AUTHORITY SECTION:
seatwave.example.	172800	IN	NS	ns1.seatwave.example.

;; ADDITIONAL SECTION:
ns1.seatwave.example.	172800	IN	A	127.0.0.1
```

**5. Stop the servers** (Ctrl-C in the first terminal, or wait 300 seconds). Then see the loader refuse the broken zone:

```text
./gradlew -q serve --args='config/serve-broken.properties'
```

```text
invalid: seatwave: a CNAME at the zone apex seatwave.example. is not allowed, because the apex must also hold the SOA and NS records (RFC 1034 section 3.6.2); use A and AAAA records or a provider's alias record
invalid: seatwave: the records of book.seatwave.example. A have different TTLs; one set has one TTL (RFC 2181 section 5.2)
invalid: seatwave: broken delegation: the name server ns1.seatwave.example. of seatwave.example. has no A or AAAA record (glue) in the zone
invalid: seatwave: a weight is given for 203.0.113.99, which book.seatwave.example. does not have
```

**6. Write your own mistakes.** In a scratch folder, make a zone file with these lines under the usual SOA, NS and `ns1` lines, and a settings file that points one listener at it (copy `config/serve-broken.properties` and change `zone.file` and the weights to `none`):

```text
www    3600 IN CNAME
mail   3600 IN MX     10 mx.seatwave.example.
bad    3600 IN A     192.0.2.999
site1  3600 IN A     2001:db8::1
txt    3600 IN TXT   "never closed
```

`serve` prints one line per mistake with the line number of the file:

```text
invalid: seatwave: typo.zone line 6: expected exactly one value, but found 0
invalid: seatwave: typo.zone line 7: unsupported type or class MX
invalid: seatwave: typo.zone line 8: not an A address: 192.0.2.999
invalid: seatwave: typo.zone line 9: not an A address: 2001:db8::1
invalid: seatwave: typo.zone line 10: a quote is not closed
```

(In that file the five lines were lines 6 to 10, after the `$ORIGIN`, `$TTL`, SOA, NS and A lines.)

## Mistakes people make

- **Forgetting the final dot.** In the data of a record, `ns1.seatwave.example` without the dot is relative: with `$ORIGIN seatwave.example.` it becomes `ns1.seatwave.example.seatwave.example.`. This repository writes absolute names in data and relative names only as owners.
- **A CNAME beside other records.** A CNAME must be the only record of its name, and it cannot sit at the apex, where the SOA and NS records must be.
- **No glue for a name server inside its own zone.** Nobody can find the server.
- **Different TTLs in one record set.** A set has one TTL.
- **Forgetting to raise the SOA serial.** Secondary servers decide whether to copy the zone by comparing serials. CoreDNS's `reload` in `coredns/Corefile` also watches it.
- **Using a real address in a lesson.** The documentation blocks exist so a mistake cannot send traffic to a stranger.
- **Writing `#` in a zone file.** The comment character of a zone file is `;`. A `#` line is a parse error. (In a weights file the comment character is `#`.)
- **Using parentheses to spread an SOA over lines.** Real zone files do. This reader supports one record per line only and reports a problem.

## Check yourself

1. What does the `@` mean in a zone file? (The current origin: the apex of the zone.)
2. Why does `example.zone` carry an A record for `ns1.seatwave`? (It is the glue for the delegation of `seatwave.example.`: the name server is inside the zone it serves.)
3. What is the last number of an SOA for? (The TTL for negative answers: how long a cache may keep "no such name".)
4. How does the weights file make `book.seatwave.example.` return 192.0.2.10 first nine times out of ten? (The weights are 90 and 10; the server picks a random number from 0 to 99 and puts the first address on top for 0 to 89.)
5. Which of the four mistakes in `zones/broken/` is in a file other than the zone file? (Mistake 4, the weight for 203.0.113.99, in the weights file.)

## Further reading

- RFC 1035 section 5, Master files: https://www.rfc-editor.org/rfc/rfc1035
- RFC 1034 section 3.6, Resource records and CNAME rules: https://www.rfc-editor.org/rfc/rfc1034
- RFC 2181 section 5.2, one TTL per record set: https://www.rfc-editor.org/rfc/rfc2181
- RFC 2308, Negative caching, and the SOA minimum field: https://www.rfc-editor.org/rfc/rfc2308
- RFC 2606, Reserved Top Level DNS Names: https://www.rfc-editor.org/rfc/rfc2606
- RFC 5737, IPv4 address blocks reserved for documentation: https://www.rfc-editor.org/rfc/rfc5737
- RFC 3849, IPv6 address prefix reserved for documentation: https://www.rfc-editor.org/rfc/rfc3849
- CoreDNS `file` plugin: https://coredns.io/plugins/file/
- CoreDNS `loadbalance` plugin: https://coredns.io/plugins/loadbalance/
