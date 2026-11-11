# CoreDNS 1.15.0 and `coredns/Corefile`

## What it is

**CoreDNS** is a DNS server written in Go. A DNS server is a program that listens on a port and answers questions about names (`dig.md`). CoreDNS is built from **plugins**: small pieces, each doing one job (read a zone file, write a log line, shuffle the order of addresses). Its settings file is called the **Corefile**, and it says which plugins run for which zone and on which port.

**This path was not run on the verification machine.** Docker was not running there, and the brief for this module says not to start it. Everything below comes from reading `coredns/Corefile`, `compose.yaml` and the README, and from the CoreDNS plugin documentation, not from a run of CoreDNS.

## Why this repository uses it

The Java server in this repository is a teaching tool. Real name servers are written by other people. The Corefile shows that the same three zone files and the same weights file, written in the standard formats, also work in a production-grade server, and that `dig` and the sampler can be pointed at it with `config/sample-coredns.properties`. It is the "production-like" layer, optional for a learner who has Docker.

## Where it appears

| Path | What it does with CoreDNS |
| --- | --- |
| `coredns/Corefile` | The settings: three server blocks, one per zone and port. |
| `compose.yaml` | Runs the image `coredns/coredns:1.15.0` with the Corefile and `zones/` mounted read-only (`docker-compose.md`). |
| `zones/root.zone`, `zones/example.zone`, `zones/seatwave.example.zone` | The zone files CoreDNS reads. They are the files the Java server reads. |
| `zones/seatwave.example.weights` | The weights for the weighted pair `book.seatwave.example`, in CoreDNS's own format. |
| `config/sample-coredns.properties` | Points the sampler at the running CoreDNS: `server.target=external` and `server.external.port=5303`. |

## How it works underneath

1. CoreDNS starts with `-conf /etc/coredns/Corefile` and parses the file into **server blocks**. A server block begins with a **zone and optional port** (`seatwave.example.:5303`) and a `{`, and lists plugins inside.
2. It opens one UDP and one TCP socket per block port. In the container the ports are 5301, 5302 and 5303. Compose publishes only the UDP ports, bound to `127.0.0.1` (`docker-compose.md`).
3. For each question it picks the block with the longest matching zone, then runs the block's plugins in a fixed order that CoreDNS decides, not the order of the lines.
4. The `file` plugin answers from a zone file loaded into memory. It sets the `aa` flag for names inside its zone.
5. `loadbalance` changes the order of the A and AAAA records in the answer. In the `weighted` policy it moves one address to the first position, picked at random using the weights.
6. `log` writes one line per query, and `errors` writes errors, both to the server's log.

## Walkthrough of this repository's use

### The comment header (lines 1 to 5)

```text
# CoreDNS serving the same three lab zones as the Java server, each on its own port (Module 6,
# Section 8). The zone and weight files are the project's own, mounted read-only at /zones.
# Checked against the CoreDNS plugin documentation (file, loadbalance, log, errors) on 10 November 2026.

# The lab's stand-in for the root zone.
```

A line starting with `#` is a comment. The header says the Corefile serves the same three lab zones as the Java server, each on its own port; the zone and weight files are the project's own, mounted read-only at `/zones`; and the plugin documentation was checked on 10 November 2026. A blank line follows.

### The root block (lines 6 to 10)

```text
.:5301 {
    file /zones/root.zone .
    log
    errors
}
```

- `.:5301 {` starts a server block for the zone `.` (the root, written as a single dot) on port 5301. The dot is the name of the top of the DNS tree. A zone name before a colon and a port after it is CoreDNS's way to put several zones on several ports in one file.
- `file /zones/root.zone .` loads the zone file `/zones/root.zone` (the path inside the container) for the zone `.`. The form is `file DBFILE [ZONES...]`: the file first, then the zones it is the authority for. The `.` at the end is the zone name.
- `log` turns on the query log: one line per question.
- `errors` turns on error logging.
- The closing `}` ends the block.

### The example. block (lines 12 to 17)

```text
# The lab's stand-in for the example. top-level domain.
example.:5302 {
    file /zones/example.zone example.
    log
    errors
}
```

The same plugins for the stand-in top-level domain `example.` on port 5302. `file /zones/example.zone example.` loads that zone. The file holds the delegation of `seatwave.example.` to `ns1.seatwave.example.` together with the glue address. When asked about `seatwave.example`, the plugin can answer with the referral in the authority and additional sections, the same shape the Java server returns (`dig.md`).

### The seatwave.example. block (lines 19 to 30)

```text
# SeatWave's own zone, with the weighted pair: the weights file uses CoreDNS's own format.
# reload 10s re-reads a zone whose SOA serial went up, which is how a change is published here.
seatwave.example.:5303 {
    file /zones/seatwave.example.zone seatwave.example. {
        reload 10s
    }
    loadbalance weighted /zones/seatwave.example.weights {
        reload 10s
    }
    log
    errors
}
```

- The two comment lines say what is special: the weights file uses CoreDNS's own format, and `reload 10s` re-reads a zone whose SOA serial went up, which is how a change is published here. The **serial** is the number in the SOA record (`2026111001` in this zone). A person who changes a record raises it, and CoreDNS reloads the zone only when the serial changed.
- `seatwave.example.:5303 {` is the block for SeatWave's own zone on port 5303.
- `file /zones/seatwave.example.zone seatwave.example. {` loads the zone file, and the `{` opens a small sub-block of options for `file`. `reload 10s` sets how often CoreDNS checks the file for a higher serial: every 10 seconds. The plugin documentation says the default is one minute, so the line makes a published change visible within about ten seconds in the lab.
- `loadbalance weighted /zones/seatwave.example.weights {` turns on the weighted policy with the weights file. The plugin documentation says the policy moves one address of the answer to the first position, picked at random according to the weights, 1 to 255 each; an address with no weight counts as 1. The file here is:

```text
# Weights for book.seatwave.example, in the format of CoreDNS's loadbalance weighted policy.
# Equal weights: each site is first in about half of the answers.
book.seatwave.example
192.0.2.10 50
198.51.100.20 50
```

  A name on a line of its own, then one `address weight` pair per line below it. The Java server reads the same file with its own parser (`WeightsTest`, `tests-message-and-zone-walkthrough.md`).
- The inner `reload 10s` is for the weights file; its default is 30 seconds according to the documentation.
- `log` and `errors` as before, then the closing `}`.

### How the same files would be used

With Docker, `docker compose up` would start one container that serves all three blocks. Then the commands of the README work on the same ports (stop the Java server first, because both use 5301 to 5303). `config/sample-coredns.properties` makes the sampler ask port 5303 and compare the shares with `zones/seatwave.example.weights`. Nothing of this was run here. The statements about the plugins come from the CoreDNS documentation, which I read for the `file` and `loadbalance` plugins; I did not test the version 1.15.0 itself.

## Try it yourself

I did not run CoreDNS. These commands only read files and run without Docker:

1. Count the server blocks and plugin lines:

```
grep -c '{$' coredns/Corefile
```

   Output (run in the repository): `5`, which counts the three block openings and the two `reload` sub-block openings.

2. See the zone files the blocks load:

```
grep -n 'file ' coredns/Corefile
```

   Output: four lines. Three are the loads, `7:    file /zones/root.zone .`, `14:    file /zones/example.zone example.` and `22:    file /zones/seatwave.example.zone seatwave.example. {`; the fourth is the comment on line 19, which contains the words `weights file`.

3. Check that the weights file has the CoreDNS shape:

```
grep -v '^#' zones/seatwave.example.weights
```

   Output: `book.seatwave.example`, `192.0.2.10 50` and `198.51.100.20 50`.

With Docker available, the steps in the README would be the next ones: `docker compose up`, then repeat the `dig` commands. That step is **not run** here.

## Mistakes people make

- **Forgetting the final dot** in a zone name: `seatwave.example` and `seatwave.example.` mean the same in the Corefile, but in a zone file a name without the final dot is relative to the origin.
- **Using a relative path.** The files must be at the paths inside the container (`/zones/...`), which `compose.yaml` mounts.
- **Starting CoreDNS while the Java server still holds the ports.** Only one program can listen on a UDP port.
- **Expecting a zone change to appear at once.** The `reload 10s` checks every 10 seconds, and only a higher SOA serial triggers a reload.
- **Weights outside 1 to 255.** The documentation limits them to that range (the Java parser refuses them too).

## Check yourself

1. *What does `.:5301 {` mean?* A server block for the root zone `.` on port 5301.
2. *What does `file /zones/example.zone example.` do?* Loads that zone file as the data for the zone `example.`.
3. *What does `reload 10s` do inside `file`?* Checks the zone file every 10 seconds and reloads when the SOA serial went up.
4. *What does `loadbalance weighted FILE` do?* Moves one of the answer's addresses to the first place, chosen at random with the weights from the file.
5. *Was CoreDNS run for these notes?* No. Docker was not running.

## Further reading

- CoreDNS manual and Corefile syntax: <https://coredns.io/manual/toc/>
- The `file` plugin: <https://coredns.io/plugins/file/>
- The `loadbalance` plugin: <https://coredns.io/plugins/loadbalance/>
- The `log` and `errors` plugins: <https://coredns.io/plugins/log/>, <https://coredns.io/plugins/errors/>
