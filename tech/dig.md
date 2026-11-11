# dig, the DNS question tool

## What it is

**DNS** (the Domain Name System) is the world's phone book for computers: you give it a name such as `seatwave.example` and it answers with data, most often an IP address such as `192.0.2.10`. A **name server** is a program that answers such questions. **dig** (domain information groper) is a command-line tool that sends one DNS question to one name server and prints the whole reply, exactly as it came. It only asks. It never changes a server. You run it in a **terminal**, a window where you type commands.

dig shows the reply in a fixed layout, with a header line, flags, and up to four sections. Reading that layout is the main skill this file teaches.

## Why this repository uses it

The lesson is about what happens when a browser resolves `seatwave.example`. dig lets a learner see each step of that path on the lab servers, one referral at a time, and watch the TTL in each answer. `scripts/ttl-change.sh` runs dig once a second to record what a client that never caches would see (`bash-ttl-change.md`). This repository does not install dig; the README says its commands ran with the macOS system dig, DiG 9.10.6.

## Where it appears

| Path | What it does with dig |
| --- | --- |
| `README.md` | Every shell example: the three-step walk, the record types, the weighted name. |
| `scripts/ttl-change.sh` | Runs `dig @127.0.0.1 -p <port> <name> A +norecurse +noall +answer +time=1 +tries=1` once per second. |
| `coredns/Corefile`, `compose.yaml` | The same three ports, so the same dig commands work against CoreDNS (`coredns.md`). |

## How it works underneath

1. dig builds one DNS **message**: a header and a question ("what is the A record of `seatwave.example.`?").
2. It sends the message as one **UDP packet** to the server's address and port. UDP is the simple way to send a packet on a network: no connection is set up, and no delivery is promised. The default DNS port is 53. The lab servers listen on 5301, 5302 and 5303 on `127.0.0.1`, the **loopback address** that always means "this computer", so `-p` is needed.
3. The server sends one packet back. dig waits for it (5 seconds by default, `+time=` changes that) and prints the reply.
4. The message carries a 16-bit number, the **id**. dig picks a random one for the question and the reply must repeat it, so dig can tell its own answer from a stray packet.

A **record** is one fact in DNS. It has an owner name, a **TTL** (time to live: how many seconds a copy may be kept), the class `IN` (the Internet), a type and the data. The types in this lab: `A` (an IPv4 address), `AAAA` (an IPv6 address), `NS` (the name of a name server for a zone), `SOA` (start of authority: the zone's serial number and timers), `CNAME` (this name is an alias for another) and `TXT` (free text). A **zone** is the part of the name space one server is in charge of. A server that holds a zone's records itself is **authoritative** for it.

## Walkthrough of this repository's use

All output below came from a real run in a scratch copy on 11 November 2026: the lab server was started with the `serve` task (with a shorter run time) and dig 9.10.6 asked it on `127.0.0.1`. No name was sent to any public server.

### Reading a whole reply

```text
$ dig @127.0.0.1 -p 5303 seatwave.example A +norecurse

; <<>> DiG 9.10.6 <<>> @127.0.0.1 -p 5303 seatwave.example A +norecurse
; (1 server found)
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 5361
;; flags: qr aa; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0

;; QUESTION SECTION:
;seatwave.example.		IN	A

;; ANSWER SECTION:
seatwave.example.	3600	IN	A	192.0.2.10

;; Query time: 1 msec
;; SERVER: 127.0.0.1#5303(127.0.0.1)
;; WHEN: Wed Nov 11 00:25:58 GMT 2026
;; MSG SIZE  rcvd: 66
```

- A line that starts with `;` is a comment line made by dig. Records have no semicolon.
- `; <<>> DiG 9.10.6 <<>> @127.0.0.1 -p 5303 seatwave.example A +norecurse` repeats the command, with dig's version.
- `; (1 server found)` and `;; global options: +cmd` say how dig was set up. `+cmd` means "print the command line".
- `;; Got answer:` says a reply arrived.
- The **header line**: `;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 5361`. `opcode: QUERY` is the kind of request, an ordinary question. `status` is the **response code**: `NOERROR` means the server understood and has no complaint (the answer may still be empty). Others seen below: `NXDOMAIN` (this name does not exist) and `REFUSED` (the server will not answer this). `id` is the random number from step 4.
- The **flags** line: `;; flags: qr aa; QUERY: 1, ANSWER: 1, AUTHORITY: 0, ADDITIONAL: 0`. `qr` means "this is a response" (query response). `aa` means **authoritative answer**: the server says it owns this zone and is not repeating a cached copy. Other flags: `rd` (recursion desired, set by dig unless `+norecurse`), `ra` (recursion available, a server that will look things up for you), `tc` (the answer was truncated). After the flags come the **counts** of the four sections: one question, one answer record, none in authority, none in additional.
- `;; QUESTION SECTION:` repeats the question: `;seatwave.example.		IN	A`. The leading `;` makes it a comment line. The final dot marks the name as complete from the root.
- `;; ANSWER SECTION:` holds the records that answer the question: owner `seatwave.example.`, TTL `3600`, class `IN`, type `A`, data `192.0.2.10`. A real resolver may keep this for 3600 seconds.
- `;; Query time: 1 msec` is how long dig waited for the reply.
- `;; SERVER: 127.0.0.1#5303(127.0.0.1)` is the address and port that answered.
- `;; WHEN:` is the time of the question and `;; MSG SIZE  rcvd: 66` is the size of the reply in bytes ("octets").

### The authority and additional sections: a referral

```text
$ dig @127.0.0.1 -p 5301 seatwave.example A +norecurse
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 1

;; AUTHORITY SECTION:
example.		172800	IN	NS	a.tld-lab.example.

;; ADDITIONAL SECTION:
a.tld-lab.example.	172800	IN	A	127.0.0.1
```

This is the answer of the stand-in root server (only the interesting lines are repeated; the full output is in the README). It has **no answer** and no `aa` flag: the root does not own `seatwave.example`. The **authority section** says who does: the name server `a.tld-lab.example.` for the zone `example.`. That is a **referral**: "ask them". The **additional section** carries **glue**, the address of that name server (`127.0.0.1`), because otherwise you would need DNS to find the server that you need for DNS. In the lab all three servers are on the same address, so the glue says nothing about the port, and that matters for `+trace` (below). The second hop (port 5302) refers to `ns1.seatwave.example.`, and the third (port 5303) answers with `aa`.

### An error: NXDOMAIN, with the SOA in authority

```text
$ dig @127.0.0.1 -p 5303 nowhere.seatwave.example A +norecurse
;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 19734
;; flags: qr aa; QUERY: 1, ANSWER: 0, AUTHORITY: 1, ADDITIONAL: 0

;; AUTHORITY SECTION:
seatwave.example.	300	IN	SOA	ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300
```

`NXDOMAIN` means the name does not exist. The server adds the zone's `SOA` record in the authority section. Its TTL, 300, is the last field of the SOA, and it tells caches how long they may remember "no such name" (the **negative TTL**). The SOA fields are: primary server, contact mailbox (with a dot for the `@`), serial number `2026111001`, refresh 7200, retry 900, expire 1209600 and minimum 300 seconds.

### Refused

```text
$ dig @127.0.0.1 -p 5303 example.com A +norecurse
;; ->>HEADER<<- opcode: QUERY, status: REFUSED, id: 36831
;; flags: qr; QUERY: 1, ANSWER: 0, AUTHORITY: 0, ADDITIONAL: 0
```

The seatwave server does not serve `example.com`, so it refuses, and there is no `aa` flag.

### The options this repository uses

- `@127.0.0.1` names the server to ask. Without it dig asks the servers in the computer's own settings, which would be a real network lookup. Every command here names the loopback address.
- `-p 5303` sets the UDP port, because the lab servers do not use 53.
- `A`, `AAAA`, `NS`, `SOA`, `TXT` choose the record type. The name goes before it: `dig @127.0.0.1 -p 5303 seatwave.example SOA`.
- `+norecurse` clears the `rd` flag. Recursion means "look it up for me, following referrals". The lab servers are authoritative only, so asking them to recurse is pointless. Without the option dig prints `;; flags: qr aa rd; ...` and the extra line `;; WARNING: recursion requested but not available`; I saw both with `dig @127.0.0.1 -p 5303 seatwave.example A`.
- `+short` prints only the data of the answer records, one per line. `dig @127.0.0.1 -p 5303 seatwave.example AAAA +short` printed `2001:db8::10`, and the TXT query printed `"seatwave-lab: documentation addresses only"`. The SOA query printed `ns1.seatwave.example. hostmaster.seatwave.example. 2026111001 7200 900 1209600 300`. For `www.seatwave.example` it printed two lines, the CNAME target `seatwave.example.` and then the address `192.0.2.10`.
- `+noall +answer` turns off every section and then turns on only the answer section. dig 9.10.6 still prints its comment lines before the records; that is why `scripts/ttl-change.sh` filters lines that start with `;`.
- `+time=1` and `+tries=1` set the wait to 1 second and the attempts to 1 (`bash-ttl-change.md`).
- `+tcp` forces TCP instead of UDP. The lab server is UDP only, and dig answered `;; Connection to 127.0.0.1#5303(127.0.0.1) for seatwave.example failed: connection refused.` with exit code 9. That is the "UDP only" simplification of the README made visible.

The weighted name: eight runs of `dig @127.0.0.1 -p 5303 book.seatwave.example A +short | head -1` printed `198.51.100.20`, `192.0.2.10`, `198.51.100.20`, `198.51.100.20`, `198.51.100.20`, `192.0.2.10`, `192.0.2.10`, `198.51.100.20`. The reply holds both addresses; the weights decide which comes first, and `head -1` keeps the first line (the README's own run printed another order, because the server picks at random).

### Why `+trace` cannot follow the lab

`dig +trace` imitates a resolver: it starts at the root servers and follows each referral by sending the next question to the address it finds in the answer. It uses glue or a lookup to find that address, and it sends every question to **port 53**. Here the three zones live on one address and differ only in port, 5301, 5302 and 5303, and a referral carries no port. The root zone's answer for `. NS` is `a.root-lab.example.` with no address in the additional section (I ran `dig @127.0.0.1 -p 5301 . NS +norecurse` and saw an empty additional section), so `+trace` would have to look that name up somewhere else, and it would be asking real servers for lab names. I therefore did **not** run `+trace`. The three commands on the ports 5301, 5302 and 5303 are the trace made by hand.

## Try it yourself

1. Start the lab servers in one terminal (they stop by themselves after 300 seconds):

```
./gradlew -q serve --args='config/serve.properties'
```

   Output: `serving on 127.0.0.1 (UDP) for 300 s:` and three lines naming the ports 5301, 5302 and 5303.

2. In a second terminal, walk the path:

```
dig @127.0.0.1 -p 5301 seatwave.example A +norecurse
dig @127.0.0.1 -p 5302 seatwave.example A +norecurse
dig @127.0.0.1 -p 5303 seatwave.example A +norecurse
```

   Output: the first two replies have `AUTHORITY: 1, ADDITIONAL: 1` and no `aa` flag, naming `example.` and then `seatwave.example.` name servers; the third has `flags: qr aa` and `ANSWER: 1` with `192.0.2.10`.

3. See an error code:

```
dig @127.0.0.1 -p 5303 nowhere.seatwave.example A +norecurse | grep status
```

   Output: `;; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: ...` (the id differs on every run).

4. See the short forms:

```
dig @127.0.0.1 -p 5303 seatwave.example TXT +short
```

   Output: `"seatwave-lab: documentation addresses only"`.

5. Follow an alias:

```
dig @127.0.0.1 -p 5303 www.seatwave.example A +short
```

   Output: two lines, `seatwave.example.` (the CNAME target) and `192.0.2.10`. When the server ends, it prints its counters; after my session it printed `root      answered 2, dropped 0`, `tld       answered 1, dropped 0` and `seatwave  answered 20, dropped 0`.

## Mistakes people make

- **Forgetting `-p`.** dig then asks port 53, where nothing of the lab listens.
- **Leaving out `@127.0.0.1`.** dig asks the computer's own resolver, which is a real network lookup.
- **Reading the answer count from the wrong place.** `ANSWER: 0` with `status: NOERROR` is "the name exists but has no record of that type", which is different from `NXDOMAIN`.
- **Thinking the TTL in a reply is the original TTL.** A caching resolver reports the seconds left. These servers are authoritative, so you see the zone's value.
- **Using `+trace` against the lab** (see above).
- **Reading `+short` as the whole story.** It hides the flags, the status and the TTL.

## Check yourself

1. *What does the `aa` flag mean?* The server is authoritative for the zone and answered from its own data.
2. *What is in the authority and additional sections of a referral?* The NS record of the next server to ask, and its address as glue.
3. *What is the difference between `NOERROR` with `ANSWER: 0` and `NXDOMAIN`?* The first means the name exists without a record of that type; the second means the name does not exist.
4. *Why does every command in this repository have `-p`?* The lab servers listen on 5301 to 5303, not on 53.
5. *Why can `+trace` not walk the lab?* Referrals carry addresses, not ports, and the trace sends every question to port 53.

## Further reading

- The dig manual page: <https://bind9.readthedocs.io/en/stable/manpages.html#dig-dns-lookup-utility>
- DNS message format: <https://www.rfc-editor.org/rfc/rfc1035#section-4>
