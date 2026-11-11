# The DNS protocol, as this repository implements it

## What it is

**DNS** (the Domain Name System) is the phone book of the internet. A person types a name such as `seatwave.example`; a computer needs a number, an **IP address** such as `192.0.2.10`. DNS is the system of servers and rules that turns the one into the other. It is defined in a family of documents called **RFCs** (Requests for Comments), the standards of the internet. The core ones are RFC 1034 (the ideas) and RFC 1035 (the details of the messages).

DNS is a **protocol**: an agreement about the exact bytes two programs send each other. A program asks a question in a fixed binary layout; a server answers in the same layout. This file explains that agreement from zero, using the bytes and the `dig` output of this repository.

## Why this repository uses it

Module 6 of the course, "Naming the world: DNS", asks what happens, step by step, when a browser resolves `seatwave.example`, and how to move SeatWave to a new address without users noticing. To answer, the repository implements the protocol itself (a server, a client, a cache and a codec) in about a dozen small Java classes, so every byte can be seen. It stays on the loopback address `127.0.0.1`, so no query leaves the machine.

## Where it appears

| Idea | Where it is in the code |
|---|---|
| A name and its labels | `message/DomainName.java` |
| Record types | `message/RecordType.java`, `message/RecordData.java`, `message/ResourceRecord.java` |
| A question | `message/Question.java` |
| A whole message, its header and flags | `message/Message.java` |
| Bytes of a message, name compression, pointer loops | `message/MessageCodec.java` |
| Response codes | `message/ResponseCode.java`, `server/Responder.java` |
| A zone | `zone/Zone.java`, `zones/*.zone` (see `dns-zone-files.md`) |
| A referral with glue | `zone/Zone.java` (`referralFor`), `zones/root.zone`, `zones/example.zone` |
| TTL and caches | `resolver/CachingResolver.java`, `migration/MigrationGuard.java` |
| UDP and truncation | `server/DnsServer.java`, `server/Responder.java` (see `java-udp-and-resolver-api.md`) |

## How it works underneath

### Names, labels and the tree

A **domain name** is a list of **labels** separated by dots, read from right to left: in `book.seatwave.example.` the label `example` is the top, `seatwave` is below it and `book` is below that. The names form a **tree**. The top of the tree is the **root**, written as a single dot. The final dot in `seatwave.example.` writes the root explicitly; a name with it is **absolute**. Everyday programs leave it off, and this code treats both forms as the same name.

The rules (RFC 1035 section 2.3.4): a label is 1 to 63 bytes, and a whole name is at most 255 bytes on the wire. Names are compared without regard to case (RFC 4343); this code stores them in lower case. The code allows only letters, digits, hyphen and underscore in a label, a rule stricter than the protocol (the README lists this as a simplification).

### Zones and the servers that hold them

A **zone** is a part of the tree that one organization manages, kept as a list of records. SeatWave manages `seatwave.example.`. The zone of `example.` is managed by someone else, and so is the root. A zone can hand a part of itself to another zone: `example.` hands `seatwave.example.` to SeatWave. That hand-over is a **delegation**.

An **authoritative server** holds a zone and gives the final answer for names in it. A **resolver** (also called a recursive resolver) finds answers for other programs: it asks authoritative servers, one after another, and keeps the answers in a cache. A program such as a browser asks its resolver; the resolver does the walking. This repository has all three roles: the lab servers (authoritative), `DnsClient` and `CachingResolver` (the resolver's side), and `dig` or `InetAddress` as the asking program.

### Records and the types used here

A **resource record** is one fact: an **owner** name, a **type**, a **class**, a **TTL** and **data**. The class is always `IN` (the internet). The types this repository uses:

| Type | Code | Data | Meaning |
|---|---|---|---|
| `A` | 1 | 4 bytes | An IPv4 address of the owner name. |
| `NS` | 2 | a name | A name server of the zone that starts at the owner. |
| `CNAME` | 5 | a name | The owner is an alias of the target name. |
| `SOA` | 6 | two names and five numbers | The start of authority of a zone: primary server, responsible mailbox, serial, refresh, retry, expire, and the minimum (the TTL of negative answers, RFC 2308). |
| `TXT` | 16 | strings | Free text. |
| `AAAA` | 28 | 16 bytes | An IPv6 address (RFC 3596). |

The codes are the numbers on the wire. A query for another type (for example MX, code 15) is answered, but with no data.

A set of records with the same owner and type is a **record set**. All records of a set must have the same TTL (RFC 2181 section 5.2).

### A query and a response

A **query** is a message that carries one **question**: a name, a type and a class. A **response** is a message that repeats the question and adds records. Both have the same layout. The **identifier** is a 16-bit number the client picks at random; the server copies it into the response, so the client can match answers to questions and ignore forged ones (RFC 5452).

Here are the real bytes of a query for `seatwave.example` type A, as the codec of this repository writes them, 34 bytes in hexadecimal (two digits per byte):

```text
12 34 00 00 00 01 00 00 00 00 00 00 08 73 65 61
74 77 61 76 65 07 65 78 61 6d 70 6c 65 00 00 01
00 01
```

| Bytes | Value | Meaning |
|---|---|---|
| `12 34` | 0x1234 | The identifier (the client chose it; the example uses 0x1234). |
| `00 00` | 0 | The flags: a query (top bit 0), opcode 0, recursion not desired. |
| `00 01` | 1 | One question. |
| `00 00` `00 00` `00 00` | 0, 0, 0 | No answer, authority or additional records. |
| `08 73 65 61 74 77 61 76 65` | 8, "seatwave" | The first label: a length byte, then 8 letters. |
| `07 65 78 61 6d 70 6c 65` | 7, "example" | The second label. |
| `00` | 0 | The end of the name: the root's empty label. |
| `00 01` | 1 | The type: A. |
| `00 01` | 1 | The class: IN. |

The response to it from the lab server, 66 bytes:

```text
12 34 84 00 00 01 00 01 00 00 00 00 08 73 65 61
74 77 61 76 65 07 65 78 61 6d 70 6c 65 00 00 01
00 01 08 73 65 61 74 77 61 76 65 07 65 78 61 6d
70 6c 65 00 00 01 00 01 00 00 0e 10 00 04 c0 00
02 0a
```

| Bytes | Meaning |
|---|---|
| `12 34` | The same identifier. |
| `84 00` | The flags 0x8400: the response bit and the authoritative bit, response code 0. |
| `00 01` `00 01` `00 00` `00 00` | One question, one answer, no authority, no additional. |
| the next 22 bytes | The question, repeated. |
| `08 73 ... 65 00` | The owner of the answer record (18 bytes, the name again, not compressed). |
| `00 01` `00 01` | Type A, class IN. |
| `00 00 0e 10` | The TTL: 0x0e10 is 3600 seconds. |
| `00 04` | The data is 4 bytes long. |
| `c0 00 02 0a` | The address: 192.0.2.10. |

`dig` shows the same in words, and its last line, `MSG SIZE rcvd: 66`, matches the 66 bytes.

### The header and its flags

The first 12 bytes of every message are the **header**: six 16-bit numbers (RFC 1035 section 4.1.1). The second is the **flags** word, whose 16 bits are:

| Bits (15 is the top) | Name | Meaning | Mask in `Message.java` |
|---|---|---|---|
| 15 | QR | 0 for a query, 1 for a response | `0x8000` |
| 14 to 11 | Opcode | 0 is a standard query; other values are other kinds of request | shift 11, mask `0xF` |
| 10 | AA | Authoritative answer: the answer comes from a server that holds the zone | `0x0400` |
| 9 | TC | Truncated: the response did not fit and was cut | `0x0200` |
| 8 | RD | Recursion desired: the client asks the server to do the whole walk | `0x0100` |
| 7 | RA | Recursion available: the server says it does recursion | not used by this lab |
| 6 to 4 | Z | Reserved by RFC 1035 (later used for DNSSEC flags, which this lab does not use) | not used |
| 3 to 0 | RCODE | The response code | mask `0xF` |

A flags value of `0x8400` is QR and AA. `0x8600` adds TC. `0x8500` is QR, AA and RD (the response to a query that had RD set: the server copies RD and the opcode from the query).

### The four sections

After the header come the **question section** and then three sections of records. The header says how many entries each holds.

1. **Question**: what is being asked.
2. **Answer**: the records that answer it.
3. **Authority**: records that say who is in charge: the NS records of a delegation, or the SOA in a negative answer.
4. **Additional**: records that help but were not asked for: the addresses of the name servers named in the authority section. These are **glue**.

`dig` prints them as `QUESTION SECTION`, `ANSWER SECTION`, `AUTHORITY SECTION` and `ADDITIONAL SECTION`, and the header line shows the counts: `QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0`.

### How a name is written on the wire, and compression

On the wire a name is a sequence of labels, each a length byte followed by that many bytes, ending with a zero byte (the root's empty label). `seatwave.example.` is `08 "seatwave" 07 "example" 00`.

A message repeats names a lot (the owner of every answer is usually the question's name). RFC 1035 section 4.1.4 lets a name or the end of a name be replaced by a **pointer**: two bytes whose top two bits are `11`. The other 14 bits are an **offset**: the position, counted in bytes from the start of the message, where the name continues. The first name of a message is at offset 12 (right after the header), so `c0 0c` means "the name that starts at byte 12". In the example above, the answer's owner could be the two bytes `c0 0c` instead of 18 bytes. I decoded a hand-made 50-byte response that does this with this repository's decoder and it read it correctly as `seatwave.example. 3600 IN A 192.0.2.10`.

The first two bits of a length byte therefore have meaning: `00` is a normal length (so labels are at most 63), `11` is a pointer, and `01` and `10` are reserved. The decoder refuses the reserved values. (I sent a label starting `0x40` and got the problem `a reserved label type`.)

### Why a pointer loop must be refused

A pointer is a jump, and a reader that follows jumps can be sent in a circle. A packet can hold `c0 0c` at offset 12: a pointer to offset 12, itself. A reader that follows it reads the same pointer again, and again, for ever. One such packet would freeze a server. Two pointers that point at each other do the same.

The rule that stops it: **a pointer must point strictly backward**, to a place before where the name began, and each further pointer in the same name must point before the one before it. Each jump then goes to a smaller offset, and an offset cannot get smaller than 0, so a chain of jumps always ends. `MessageCodec.readName` keeps a `pointerLimit` for this, and refuses `target >= pointerLimit`.

I tested it with real bytes. This 18-byte query has a name made of a single pointer to itself:

```text
12 34 00 00 00 01 00 00 00 00 00 00 c0 0c 00 01 00 01
```

Sent to the lab server it did not loop. The reply was 12 bytes, the identifier `1234` and the flags `8001` (response, code 1 = FORMERR) and no sections:

```text
00000000: 1234 8001 0000 0000 0000 0000
```

### Response codes

The last four bits of the flags are the **response code** (RCODE). The ones this lab sends (RFC 1035 section 4.1.1):

| Code | `dig` name | Meaning | When the lab sends it |
|---|---|---|---|
| 0 | NOERROR | No error. The name exists; the answer section may still be empty. | An answer, a referral, or "no data". |
| 1 | FORMERR | Format error: the server could not read the query. | A packet that `MessageCodec.decode` refuses, or a query with other than one question. |
| 2 | SERVFAIL | The server failed. | Not sent by the server; the caching resolver uses it for "no server answered". |
| 3 | NXDOMAIN | The name does not exist. | A name in the zone with nothing at it or below it. |
| 4 | NOTIMP | The kind of request is not implemented. | An opcode other than 0. |
| 5 | REFUSED | The server will not answer. | A name outside the server's zone, or a class other than IN. |

NXDOMAIN and "no data" are different. **NXDOMAIN** says the name does not exist at all. **No data** (NOERROR with an empty answer) says the name exists but has no record of the type asked. Both carry the zone's SOA in the authority section so a cache can keep the "no" (RFC 2308). The SOA's TTL in the answer is the smaller of its own TTL and its minimum field. A name with records below it counts as existing even when it has none of its own (RFC 8020).

Real answers from the lab server (`dig @127.0.0.1 -p 5303 ... +norecurse`):

```text
;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 32643
;; flags: qr aa; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 0
;; QUESTION SECTION:
;nothing.seatwave.example.	IN	A

;; AUTHORITY SECTION:
seatwave.example.	300	IN	SOA	ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300

;; MSG SIZE  rcvd: 141
```

```text
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 54790
;; flags: qr aa; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 0
;; QUESTION SECTION:
;seatwave.example.		IN	MX

;; AUTHORITY SECTION:
seatwave.example.	300	IN	SOA	ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300

;; MSG SIZE  rcvd: 133
```

```text
;; ->>HEADER<<- opcode: QUERY, status: REFUSED, id: 31394
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 0, ADDITIONAL: 0
;; QUESTION SECTION:
;example.com.			IN	A

;; MSG SIZE  rcvd: 29
```

The first is NXDOMAIN, the second is "no data" (the name exists, there is no MX), the third is REFUSED. In the first two the SOA record shows TTL 300, the SOA's minimum, even though the zone's SOA has TTL 3600.

### A referral with glue

When a server is asked for a name that lies in a child zone it delegates, it cannot answer. It answers with a **referral**: the NS records of the child zone in the authority section, the addresses of those name servers (glue) in the additional section, an empty answer section, and no AA flag. The resolver then asks the child's name server.

The lab has three servers on three ports, which stand for the root, the top-level domain `example.` and SeatWave. Asking the root for `seatwave.example`:

```text
$ dig @127.0.0.1 -p 5301 seatwave.example A +norecurse
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 27921
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 1

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; AUTHORITY SECTION:
example.		172800	IN	NS	a.tld-lab.example.

;; ADDITIONAL SECTION:
a.tld-lab.example.	172800	IN	A	127.0.0.1

;; MSG SIZE  rcvd: 105
```

The flags show `qr` and no `aa`. The root says: "I do not hold it. Ask `a.tld-lab.example.`, whose address is 127.0.0.1." Without the glue the resolver would have to look up `a.tld-lab.example.` first, which needs the very servers it is looking for.

Asking the `example.` server on port 5302 gives the next referral (to `ns1.seatwave.example.`, with its address as glue). Asking SeatWave's server on port 5303 gives the final answer:

```text
$ dig @127.0.0.1 -p 5303 seatwave.example A +norecurse
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 19986
;; flags: qr aa; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; ANSWER SECTION:
seatwave.example.	3600	IN	A	192.0.2.10

;; MSG SIZE  rcvd: 66
```

(This is the output in the README, from an earlier run; the identifier differs from run to run.) Here the flags have `aa`: the server holds the zone.

An alias works inside one zone. `www.seatwave.example` is a CNAME for `seatwave.example`, so the answer holds both records:

```text
;; ANSWER SECTION:
www.seatwave.example.	3600	IN	CNAME	seatwave.example.
seatwave.example.	3600	IN	A	192.0.2.10
```

### TTL and caches

The **TTL** (time to live) of a record is a number of seconds: how long a cache may keep the record before it must ask again. It is a 32-bit number on the wire; RFC 2181 section 8 limits it to 2,147,483,647 and says a value with the top bit set must be treated as 0.

A **cache** is a memory of earlier answers. A resolver caches so it need not ask the same question again. That is a big saving, and it is the reason a name change is slow: every cache that holds the old answer keeps serving it until its TTL runs out. A TTL of 3600 means a change can take an hour to reach everyone; 86,400 means a day. Lowering the TTL beforehand makes a change quick, at the price of more queries to the server. The experiment of this repository measures exactly that.

A cache also keeps **negative answers** ("no such name") for the SOA minimum (RFC 2308). A cache may keep an answer for less than the TTL, never for more; some caches do keep it longer, and this lab calls them **pinning** resolvers.

### Why DNS uses UDP, and what truncation means

DNS normally runs over **UDP**: single messages sent with no connection. A question and its answer are each one packet, so a lookup costs one round trip (RFC 1035 section 4.2.1). TCP would add a handshake. The price is that a packet can be lost, so a client uses a timeout and asks again.

RFC 1035 limits a UDP message to 512 bytes. If a response is longer, the server sends a cut copy with the **TC** (truncated) flag set, and the client repeats the question over TCP (RFC 7766). I built a zone with 30 A records for one name and asked for it: the lab server answered with 38 bytes, the flags `8600` (response, authoritative and truncated) and no answer records:

```text
12 34 86 00 00 01 00 00 00 00 00 00 03 62 69 67
08 73 65 61 74 77 61 76 65 07 65 78 61 6d 70 6c
65 00 00 01 00 01
```

The lab server has no TCP; real servers must have it (RFC 7766). Modern DNS also lets a client ask for bigger UDP messages with the EDNS option (RFC 6891); this lab reads and skips that option and does not use it.

## Walkthrough of this repository's use

The code is explained line by line in `java-message-walkthrough.md`, `java-zone-and-server-walkthrough.md` and `java-resolver-and-jdk-walkthrough.md`. These are the lines where the ideas of this file become code.

**The flag masks:**

```java
  /** Set in every response, clear in every query. */
  public static final int RESPONSE_FLAG = 0x8000;

  /** Set when the answer comes from a server authoritative for the name. */
  public static final int AUTHORITATIVE_FLAG = 0x0400;

  /** Set when the response did not fit and was cut short. */
  public static final int TRUNCATED_FLAG = 0x0200;

  /** Set by a client that wants the server to resolve the name fully on its behalf. */
  public static final int RECURSION_DESIRED_FLAG = 0x0100;

  // The opcode is bits 11 to 14 of the flags word; the response code is the lowest four bits.
  private static final int OPCODE_SHIFT = 11;
  private static final int OPCODE_MASK = 0xF;
  private static final int RESPONSE_CODE_MASK = 0xF;
  private static final int MAX_ID = 0xFFFF;
```

**The rule that stops pointer loops:**

```java
    private DomainName readName() throws Malformed {
      List<String> labels = new ArrayList<>();
      int cursor = position;
      // Every pointer must point strictly before this mark, and the mark moves back with each
      // jump, so a chain of pointers always ends: no packet can make this loop forever.
      int pointerLimit = position;
      boolean hasJumped = false;
      int wireLength = 1;
      while (true) {
        int length = byteAt(cursor);
        if ((length & POINTER_BITS) == POINTER_BITS) {
          int target = ((length & POINTER_OFFSET_MASK) << BITS_PER_BYTE) | byteAt(cursor + 1);
          if (target >= pointerLimit) {
            throw new Malformed(
                new MessageProblem.BadName("a compression pointer must point backward"));
          }
          if (!hasJumped) {
            position = cursor + 2;
            hasJumped = true;
          }
          pointerLimit = target;
          cursor = target;
          continue;
        }
```

- `pointerLimit` starts at the place the name began and moves back with every jump. A pointer to the same place or forward is refused (`target >= pointerLimit`).

**The codes the lab sends:**

```java
  /** NOERROR: the name exists; the answer may still be empty (no data of that type). */
  NO_ERROR(0),
  /** FORMERR: the query could not be read. */
  FORMAT_ERROR(1),
  /** SERVFAIL: the server could not answer. */
  SERVER_FAILURE(2),
  /** NXDOMAIN: the name does not exist in the zone. */
  NAME_ERROR(3),
  /** NOTIMP: the kind of query is not supported. */
  NOT_IMPLEMENTED(4),
  /** REFUSED: the server will not answer, here because the name is outside its zones. */
  REFUSED(5);
```

**The choice among the five answers, and the cut at 512 bytes:**

```java
    Message response =
        switch (MessageCodec.decode(packet)) {
          case Result.Success<Message, MessageProblem>(Message query) ->
              answer(query, zone, random);
          case Result.Error<Message, MessageProblem> unreadable ->
              reply(MessageCodec.idOf(packet), queryFlags, List.of(), ResponseCode.FORMAT_ERROR);
        };
    byte[] encoded = MessageCodec.encode(response);
    if (encoded.length > MAX_UDP_OCTETS) {
      Message cut =
          new Message(
              response.id(),
              response.flags() | Message.TRUNCATED_FLAG,
              response.questions(),
              List.of(),
              List.of(),
              List.of());
      return Optional.of(MessageCodec.encode(cut));
    }
    return Optional.of(encoded);
  }
```

**The referral with its glue:**

```java
  private Optional<Lookup> referralFor(DomainName name) {
    // The highest cut below the apex wins: walk from just under the apex down to the name.
    List<DomainName> chain = new ArrayList<>(name.selfAndAncestors());
    Collections.reverse(chain);
    for (DomainName candidate : chain) {
      if (!candidate.isAtOrBelow(origin) || candidate.equals(origin)) {
        continue;
      }
      List<ResourceRecord> nameServers = recordsAt(candidate, RecordType.NS);
      if (!nameServers.isEmpty()) {
        List<ResourceRecord> glue = new ArrayList<>();
        for (ResourceRecord nameServer : nameServers) {
          DomainName host = ((RecordData.Host) nameServer.data()).target();
          glue.addAll(recordsAt(host, RecordType.A));
          glue.addAll(recordsAt(host, RecordType.AAAA));
        }
        return Optional.of(new Lookup.Referral(nameServers, glue));
      }
    }
    return Optional.empty();
  }
```

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only. `dig` is a command-line DNS client (`dig.md`).

**1. Start the lab servers** (they stop by themselves after 300 seconds):

```text
./gradlew -q serve --args='config/serve.properties'
```

**2. Walk the path by hand, one referral at a time.** Run each in a second terminal:

```text
dig @127.0.0.1 -p 5301 seatwave.example A +norecurse
dig @127.0.0.1 -p 5302 seatwave.example A +norecurse
dig @127.0.0.1 -p 5303 seatwave.example A +norecurse
```

The first two show `flags: qr` and an `AUTHORITY SECTION` with an NS record and an `ADDITIONAL SECTION` with its address; the third shows `flags: qr aa` and the answer `seatwave.example. 3600 IN A 192.0.2.10`.

**3. See the three kinds of "no".**

```text
dig @127.0.0.1 -p 5303 nothing.seatwave.example A +norecurse
dig @127.0.0.1 -p 5303 seatwave.example MX +norecurse
dig @127.0.0.1 -p 5303 example.com A +norecurse
```

The statuses are `NXDOMAIN`, `NOERROR` with no answer, and `REFUSED`.

**4. Send the loop packet.** `nc -u` sends UDP; `xxd` prints bytes.

```text
printf '\x12\x34\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\xc0\x0c\x00\x01\x00\x01' | nc -u -w1 127.0.0.1 5303 | xxd
```

```text
00000000: 1234 8001 0000 0000 0000 0000            .4..........
```

**5. Send a response and get no reply.** The first byte pair after the identifier is the flags; `8000` has the response bit set. The server drops it, so `nc` prints nothing:

```text
printf '\x12\x34\x80\x00\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x00\x01' | nc -u -w1 127.0.0.1 5303 | xxd
```

(No output. Answering a response could make two servers answer each other for ever.)

**6. Stop.** Ctrl-C in the first terminal, or wait.

## Mistakes people make

- **Thinking the server must be asked about the right zone only.** A server can be asked anything. A name outside its zones gets REFUSED.
- **Confusing NXDOMAIN with "no data".** The first says the name does not exist; the second says it exists without that type. A resolver that mixes them breaks mail and IPv6 lookups.
- **Following compression pointers without a limit.** A pointer to itself loops for ever. Only pointers that go backward are followed.
- **Putting a CNAME at the apex.** The apex must hold the SOA and NS records, and a CNAME cannot share a name with other records (RFC 1034 section 3.6.2).
- **Forgetting glue.** A delegation to a name server inside the delegated zone needs an address in the parent.
- **Setting a long TTL and then needing to move.** Caches keep the old answer until the TTL ends. Lower it one old TTL before the change.
- **Trusting an answer by its identifier alone.** The client checks the question too (RFC 5452).
- **Sending a long answer over UDP.** Over 512 bytes it is cut and flagged. A client must retry over TCP.

## Check yourself

1. What are the four sections of a DNS message after the header? (Question, answer, authority and additional.)
2. What does `c0 0c` mean inside a name? (A compression pointer to offset 12, where the first name of the message starts.)
3. Why must a pointer point strictly backward? (So every chain of pointers ends; a pointer to itself or forward could loop for ever.)
4. What is the difference between NXDOMAIN and NOERROR with no answer? (NXDOMAIN: the name does not exist; NOERROR with no answer: the name exists but has no record of that type.)
5. What is glue, and why does `root.zone` hold `a.tld-lab.example. 172800 IN A 127.0.0.1`? (An address held in the parent for a child's name server, so a resolver can reach the child without another lookup.)

## Further reading

- RFC 1034, Domain Names - Concepts and Facilities: https://www.rfc-editor.org/rfc/rfc1034
- RFC 1035, Domain Names - Implementation and Specification (sections 3, 4 and 5): https://www.rfc-editor.org/rfc/rfc1035
- RFC 2181, Clarifications to the DNS Specification: https://www.rfc-editor.org/rfc/rfc2181
- RFC 2308, Negative Caching of DNS Queries: https://www.rfc-editor.org/rfc/rfc2308
- RFC 3596, DNS Extensions to Support IP Version 6: https://www.rfc-editor.org/rfc/rfc3596
- RFC 4343, DNS Case Insensitivity Clarification: https://www.rfc-editor.org/rfc/rfc4343
- RFC 5452, Measures for Making DNS More Resilient against Forged Answers: https://www.rfc-editor.org/rfc/rfc5452
- RFC 6891, Extension Mechanisms for DNS (EDNS(0)): https://www.rfc-editor.org/rfc/rfc6891
- RFC 7766, DNS Transport over TCP: https://www.rfc-editor.org/rfc/rfc7766
- RFC 8020, NXDOMAIN: There Really Is Nothing Underneath: https://www.rfc-editor.org/rfc/rfc8020
- RFC 9619, In the DNS, QDCOUNT Is (Usually) One: https://www.rfc-editor.org/rfc/rfc9619
