# UDP, byte buffers and the JDK's resolver interface (in Java)

## What it is

This file teaches three things the DNS code in this repository leans on, each from zero.

1. **UDP**: a way to send a small message from one program to another over a network, with no connection set up first. The JDK classes `DatagramSocket` and `DatagramPacket` use it.
2. **Bytes in memory and their order**: how a number such as 0x1234 becomes two bytes on the wire, and why Java needs care with a `byte`.
3. **The resolver service-provider interface**: a plug-in point of the JDK (since Java 18) that lets a program supply its own way to turn a host name into addresses, found through a file under `META-INF/services`.

## Why this repository uses it

DNS is the first thing a program does when it meets a name, and it is built to be small and quick. A question and its answer each fit in one packet, so DNS uses UDP. The lab server and the lab client speak real UDP on the loopback address, so `dig` can talk to the Java server and the Java client can talk to CoreDNS. The codec must write and read exact byte layouts, so byte order matters. And the last part of the module shows what the JVM's own lookup does with a TTL, which needs the resolver interface.

## Where it appears

| File | What it does here |
|---|---|
| `server/DnsServer.java` | Binds a `DatagramSocket` on 127.0.0.1, receives packets in a loop, sends replies. |
| `resolver/DnsClient.java` | Opens a `DatagramSocket`, connects it, sends a question, waits with a timeout for the matching answer. |
| `message/MessageCodec.java` | Writes and reads numbers byte by byte, in big-endian order. |
| `server/Responder.java` | Reads the flags straight from the bytes 2 and 3 of a packet. |
| `jdk/LabResolverProvider.java`, `jdk/LabResolver.java` | The resolver provider and resolver. |
| `src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider` | The file that tells the JDK the provider exists. |
| `jdk/JdkLookupCli.java` | Sets the properties and measures `InetAddress.getByName`. |

## How it works underneath

### Two ways to send bytes: TCP and UDP

A **network** moves packets between computers. A **packet** is a chunk of bytes with a header that says where it comes from and where it goes. An **IP address** names a computer (`127.0.0.1` is "this computer", the **loopback** address). A **port** is a number from 0 to 65,535 that names one program on that computer; the pair address and port names one end of a conversation. The ports below 1,024 are **privileged**: on many systems only an administrator may use them. The lab's ports (5301, 5302, 5303, 5355 and 5356) are not.

Two protocols carry most traffic.

- **TCP** gives a **stream**: a reliable, ordered row of bytes, like a telephone call. Before any data moves, the two sides do a **handshake** (an exchange of a few packets), which costs one round trip. TCP resends lost data and keeps order.
- **UDP** (RFC 768) gives **datagrams**: single messages, like postcards. There is no handshake and no connection. Each datagram has an 8-byte header (source port, destination port, length, checksum) and then your bytes. Nothing is resent: if a datagram is lost, nobody is told. Nothing keeps order. But one `send` is exactly one datagram, and the receiver gets it whole or not at all.

**Why DNS uses UDP.** A DNS question is a few dozen bytes and so is the answer. With UDP the whole exchange is one packet out and one packet back: one round trip, and the server keeps no state per client. With TCP it would be a handshake first, then the question, then the answer, which is two round trips for every first lookup, and a server would hold a connection for each client. DNS accepts that a packet may be lost, and handles it with a **timeout** and a retry. RFC 1035 section 4.2.1 limits a UDP message to 512 bytes; a longer answer is cut and flagged as truncated, and the client asks again over TCP (RFC 7766 requires servers to support TCP too). This lab implements UDP only.

**The JDK classes.**

- `DatagramSocket` is one end of UDP communication. `new DatagramSocket(address)` **binds** it: it claims a port, so packets sent to that port arrive here. `new DatagramSocket()` binds to a port the system picks, for a client.
- `DatagramPacket` is one datagram in memory: a byte array, a length, and an address and port. For **receiving**, you give it an empty array; `socket.receive(packet)` fills it and sets the length to the number of bytes that arrived. If the datagram is bigger than the array, the rest is silently thrown away. For **sending**, you give it the bytes and the destination.
- `socket.connect(address)` on a UDP socket does **not** open a connection (UDP has none). It only remembers the other end: the socket then sends to it by default, accepts datagrams from it alone, and the operating system reports an unreachable port as an exception.
- `socket.setSoTimeout(milliseconds)` sets how long `receive` may wait. When the time is up it throws `SocketTimeoutException`. With 0, the default, it waits for ever.
- `socket.close()` closes it; a thread blocked in `receive` then throws `SocketException`.

I checked three behaviors on this machine with small programs on JDK 25: a datagram of 600 bytes received into a 513-byte buffer gives a length of 513 (the rest is dropped); a following datagram of 100 bytes gives 100; and `DnsClient` aimed at a port nobody listens on gets `Unreachable` at once, while one aimed at a socket that never answers gets `TimedOut` after the timeout (about 304 ms for 300 ms).

### Bytes and their order

A **byte** is 8 bits and holds one of 256 values. Java's `byte` type is **signed**: it holds -128 to 127. The byte `0x84` (decimal 132) is stored as -124. To get 132 back you write `b & 0xFF`: the `&` (bitwise and) with `0xFF` keeps the low eight bits and turns the value into an `int` from 0 to 255. I ran `(byte) 0x84` and got -124, and `(b & 0xFF)` and got 132.

A number bigger than 255 needs several bytes, and the order matters. **Big-endian** puts the most significant byte first: 0x1234 is sent as `12 34`. **Little-endian** puts the least significant first: `34 12`. Network protocols, DNS included, use big-endian, so it is also called **network byte order**. Many processors, including the ARM and Intel processors of most laptops, store numbers little-endian in memory (`ByteOrder.nativeOrder()` printed `LITTLE_ENDIAN` on this Mac), so code that writes numbers for the network must not copy memory directly: it shifts and masks.

The JDK has a `ByteBuffer` class that reads and writes numbers and defaults to big-endian (it printed `BIG_ENDIAN` for a new buffer). This repository does not use it. `MessageCodec` writes into a `ByteArrayOutputStream` (a growing buffer of bytes) and reads from a plain `byte[]` with shifts and masks, so every byte in the code can be matched to a byte in RFC 1035.

### Timeouts on every receive

A network can lose a packet, and a server can be slow or dead. A `receive` with no timeout then waits for ever. Every place in this code that waits on the network has a limit:

- `DnsClient` sets `setSoTimeout` in the constructor and again before each `receive` with the time left in a deadline, so wrong packets cannot extend the wait.
- `SiteEndpoints.isHealthy` connects with a timeout.
- `DnsServer.close` waits at most two seconds for its thread.
- The server's own `receive` has no timeout: its job is to wait for the next query, and `close()` ends it.

### The resolver service-provider interface

**Name resolution** is turning a host name such as `seatwave.example` into addresses. A Java program does it with `InetAddress.getByName(name)`. Normally the JDK asks the operating system, which asks DNS servers.

A **service-provider interface** (SPI) is a plug-in point. The JDK defines an interface or abstract class (the **service**); other code supplies implementations (the **providers**); the JDK finds them at run time with `java.util.ServiceLoader`. Since Java 18 (JEP 418), name resolution is such a service:

- `java.net.spi.InetAddressResolverProvider` is the service, an abstract class with two methods: `get(Configuration)`, which returns the resolver, and `name()`.
- `java.net.spi.InetAddressResolver` is the resolver, an interface with `lookupByName(String host, LookupPolicy policy)` and `lookupByAddress(byte[] address)`.
- `LookupPolicy` says which families the caller wants (`IPV4`, `IPV6`) and which should come first (`IPV4_FIRST`, `IPV6_FIRST`).
- `Configuration.builtinResolver()` gives the JDK's normal resolver, so a provider can pass some lookups through.

**How the JDK finds a provider.** The Javadoc of `InetAddressResolverProvider` says: when the first lookup happens, `InetAddress` uses `ServiceLoader` with the system class loader to locate an `InetAddressResolverProvider`; the first provider found is asked for the resolver with `get(...)`; and if none is found, the built-in resolver is used. A JVM keeps one system-wide resolver. If `get` throws, the error goes to the caller of the lookup.

**The `META-INF/services` file.** On the class path, `ServiceLoader` finds providers through text files in the folder `META-INF/services`. The name of the file is the full name of the service. Each line of the file is the full name of one provider class. This repository has one file, `src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`, and it holds one line:

```text
io.plainround.seatwave.dns.jdk.LabResolverProvider
```

- The file name says which service it is for: `java.net.spi.InetAddressResolverProvider`.
- The line says which class provides it: `LabResolverProvider` in the package `io.plainround.seatwave.dns.jdk`. That class must be public, have a public constructor with no parameters, and extend the service class. It does (see `java-resolver-and-jdk-walkthrough.md`).
- Gradle copies everything under `src/main/resources` into the build output (`build/resources/main`), and puts that folder on the class path with the compiled classes. If the folder were left off the class path, the file would not be seen.

I checked it with a short program (`ServiceLoader.load(InetAddressResolverProvider.class)`): with only the compiled classes on the class path it found no provider (`providers found: []`); with `build/resources/main` added it found `[io.plainround.seatwave.dns.jdk.LabResolverProvider]`, and `name()` returned `seatwave-lab`.

**Off unless asked.** The provider is found by every program that has this project on its class path, including the tests. If it always replaced the resolver, every lookup in the tests would go to the lab server. So `LabResolverProvider.get` checks two system properties, and returns `configuration.builtinResolver()` unless both are set. Only `JdkLookupCli` and `TtlLiveCli` set them, before their first lookup, because the JVM loads the resolver once.

**The JVM's cache.** The JDK caches the results of lookups on top of any resolver. The time comes from two **security properties**: `networkaddress.cache.ttl` for successful lookups and `networkaddress.cache.negative.ttl` for failures. The commands set them with `Security.setProperty` before the first lookup. A record's TTL does not matter to this cache; that is the point of the experiment.

## Walkthrough of this repository's use

All the code below is explained line by line in `java-zone-and-server-walkthrough.md` (server), `java-resolver-and-jdk-walkthrough.md` (client and resolver) and `java-message-walkthrough.md` (bytes). Here are the lines that carry the ideas of this file.

### Binding a UDP socket on the loopback address

```java
  public static DnsServer start(int port, ZoneStore store, RandomGenerator random)
      throws IOException {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(random, "random");
    if (port != ANY_FREE_PORT && (port < LOWEST_UNPRIVILEGED_PORT || port > HIGHEST_PORT)) {
      throw new IllegalArgumentException("the port must be from 1024 to 65535, but was " + port);
    }
    DatagramSocket socket =
        new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    DnsServer server = new DnsServer(socket, store, random);
    server.receiver.start();
    return server;
  }
```

- `InetAddress.getLoopbackAddress()` is 127.0.0.1. Binding to it means only this machine can send to the socket. Binding to the wildcard address would let other computers in.
- `port == 0` asks the system for any free port. Other ports must be 1,024 to 65,535.
- The socket is bound when the constructor returns, before `start` returns: a client can send at once.

### Receiving without a timeout, ended by `close()`

```java
  private void serve() {
    byte[] buffer = new byte[RECEIVE_BUFFER_OCTETS];
    while (!socket.isClosed()) {
      DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (SocketException closed) {
        // close() makes receive fail; that is the normal way this loop ends.
        return;
      } catch (IOException failed) {
        dropped.incrementAndGet();
        continue;
      }
      if (packet.getLength() > Responder.MAX_UDP_OCTETS) {
        dropped.incrementAndGet();
        continue;
      }
      byte[] query = Arrays.copyOf(packet.getData(), packet.getLength());
      Optional<byte[]> response = Responder.respond(query, store.current(), random);
      if (response.isEmpty()) {
        dropped.incrementAndGet();
        continue;
      }
      send(response.get(), packet);
    }
  }
```

- One buffer of 513 bytes is reused. A packet of more than 512 bytes gets a length above 512 and is dropped.
- The data is copied out (`Arrays.copyOf`) before the buffer is reused.
- A `SocketException` is how `close()` ends the loop.

### Replying to the sender

```java
  private void send(byte[] response, DatagramPacket query) {
    try {
      socket.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
      answered.incrementAndGet();
    } catch (IOException failed) {
      // A client that went away cannot be answered; the next packet is unaffected.
      dropped.incrementAndGet();
    }
  }
}
```

- The reply goes to `query.getSocketAddress()`: the address and port the query came from. The server does not need to remember clients.

### A client socket with a deadline

```java
  /**
   * Opens a socket connected to one server on the loopback address.
   *
   * @param serverPort the server's UDP port
   * @param timeout the longest wait for each response
   */
  public DnsClient(int serverPort, Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("the timeout must be positive");
    }
    try {
      socket = new DatagramSocket();
      // connect() makes the socket accept packets from this server only, and makes the system
      // report a closed port as PortUnreachableException instead of a silent timeout.
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort));
      socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
    } catch (IOException failed) {
      throw new UncheckedIOException("cannot open a UDP socket", failed);
    }
  }
```

- `connect` fixes the server; `setSoTimeout` sets the first limit.

```java
  @Override
  public Result<Message, LookupProblem> ask(Question question) {
    Objects.requireNonNull(question, "question");
    int id = random.nextInt(ID_SPACE);
    byte[] query = MessageCodec.encode(Message.query(id, question));
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      socket.send(new DatagramPacket(query, query.length));
      byte[] buffer = new byte[RESPONSE_BUFFER_OCTETS];
      while (true) {
        long remainingMillis = (deadline - System.nanoTime()) / Duration.ofMillis(1).toNanos();
        if (remainingMillis <= 0) {
          return Result.error(new LookupProblem.TimedOut(timeout));
        }
        socket.setSoTimeout(Math.toIntExact(remainingMillis));
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        byte[] bytes = Arrays.copyOf(packet.getData(), packet.getLength());
        switch (MessageCodec.decode(bytes)) {
          case Result.Success<Message, MessageProblem>(Message response) -> {
            if (isAnswerTo(response, id, question)) {
              return Result.success(response);
            }
          }
          case Result.Error<Message, MessageProblem>(MessageProblem problem) -> {
            return Result.error(new LookupProblem.Malformed(problem));
          }
        }
      }
    } catch (SocketTimeoutException timedOut) {
      return Result.error(new LookupProblem.TimedOut(timeout));
    } catch (PortUnreachableException closedPort) {
      // The exception carries no message of its own, so name the port that refused the packet.
      return Result.error(
          new LookupProblem.Unreachable("nothing is listening on port " + socket.getPort()));
    } catch (IOException failed) {
      throw new UncheckedIOException("the UDP socket failed", failed);
    }
  }
```

- The deadline logic: `deadline` is a point on the monotonic clock; before each `receive` the remaining time becomes the socket timeout. The three `catch` clauses map a timeout, a closed port and every other failure.

### Big-endian numbers, by hand

```java
  public static int idOf(byte[] packet) {
    return ((packet[0] & BYTE_MASK) << BITS_PER_BYTE) | (packet[1] & BYTE_MASK);
  }
```

- Reading the identifier: the first byte, masked to 0 to 255 and moved eight bits left, or-ed with the second byte masked the same way.

```java
  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write((value >> BITS_PER_BYTE) & BYTE_MASK);
    out.write(value & BYTE_MASK);
  }

  private static void writeInt(ByteArrayOutputStream out, long value) {
    writeShort(out, (int) ((value >> (2 * BITS_PER_BYTE)) & 0xFFFF));
    writeShort(out, (int) (value & 0xFFFF));
  }
```

- Writing: the high byte first (`value >> 8`), then the low byte. `writeInt` writes two halves of 16 bits.

```java
    private int readShort(String part) throws Malformed {
      if (position + 2 > packet.length) {
        throw new Malformed(new MessageProblem.Truncated(part));
      }
      int value =
          ((packet[position] & BYTE_MASK) << BITS_PER_BYTE) | (packet[position + 1] & BYTE_MASK);
      position += 2;
      return value;
    }

    private long readInt(String part) throws Malformed {
      long high = readShort(part);
      long low = readShort(part);
      return ((high << (2 * BITS_PER_BYTE)) | low) & UNSIGNED_32_MASK;
    }
```

- Reading: `readShort` combines two bytes; `readInt` combines two shorts as `long`s and masks to 32 bits, so a value above 2,147,483,647 is not negative.

### The provider and the resolver

```java
  @Override
  public InetAddressResolver get(Configuration configuration) {
    Optional<String> port = Optional.ofNullable(System.getProperty(PORT_PROPERTY));
    Optional<String> timeout = Optional.ofNullable(System.getProperty(TIMEOUT_PROPERTY));
    if (port.isEmpty() || timeout.isEmpty()) {
      return configuration.builtinResolver();
    }
    return new LabResolver(
        Integer.parseInt(port.get()), Duration.ofMillis(Long.parseLong(timeout.get())));
  }

  @Override
  public String name() {
    return "seatwave-lab";
  }
}
```

- `get` is called by the JDK for the system-wide resolver. With both properties set it builds a `LabResolver`; otherwise it returns the built-in one.

```java
  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    DomainName name =
        switch (DomainName.parse(host)) {
          case Result.Success<DomainName, String>(DomainName parsed) -> parsed;
          case Result.Error<DomainName, String>(String problem) ->
              throw new UnknownHostException(problem);
        };
    if (!name.isAtOrBelow(LAB_ZONE)) {
      throw new UnknownHostException("the lab resolver answers only names under " + LAB_ZONE);
    }
    int characteristics = lookupPolicy.characteristics();
    List<RecordType> order = new ArrayList<>();
    if ((characteristics & LookupPolicy.IPV4) != 0) {
      order.add(RecordType.A);
    }
    if ((characteristics & LookupPolicy.IPV6) != 0) {
      order.add(
          (characteristics & LookupPolicy.IPV6_FIRST) != 0 ? 0 : order.size(), RecordType.AAAA);
    }
    List<InetAddress> addresses = new ArrayList<>();
    try (DnsClient client = new DnsClient(port, timeout)) {
      for (RecordType type : order) {
        addresses.addAll(addressesOf(client.ask(Question.of(name, type)), type, host));
      }
    }
    if (addresses.isEmpty()) {
      throw new UnknownHostException(host + ": no address in the lab zone");
    }
    return addresses.stream();
  }
```

- `lookupByName` is called for every `InetAddress.getByName`. It refuses names outside `seatwave.example`, reads which families the policy wants, asks the lab server over UDP with a short-lived `DnsClient`, and returns a stream of addresses or throws `UnknownHostException`.

## Try it yourself

All commands run in the repository folder, on 127.0.0.1 only.

**1. Look at the service file.**

```text
cat src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider
```

```text
io.plainround.seatwave.dns.jdk.LabResolverProvider
```

**2. Run the lookup through the plug-in.** The command starts its own lab server on port 5356.

```text
./gradlew -q jdkLookup --args='config/jdk-lookup.properties'
```

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; InetAddress.getByName through the lab resolver to 127.0.0.1:5356; networkaddress.cache.ttl=30, networkaddress.cache.negative.ttl=10
lookup                       result          lookups     first us    median us  server queries
seatwave.example             192.0.2.10         1000      15722.8          1.1               2
nowhere.seatwave.example     UnknownHost        1000       1368.3          3.4               2
```

The times change on every run. The `2` in the last column does not: one A query and one AAAA query reached the server, and the JVM's own cache answered the other 999 lookups.

**3. See the effect of the cache time.** Copy `config/jdk-lookup.properties` to a scratch folder, set `jvm.cache.ttl.seconds=0` and `lookup.count=20`, and run the command with the copy:

```text
lookup                       result          lookups     first us    median us  server queries
seatwave.example             192.0.2.10           20      13802.8        726.7              40
nowhere.seatwave.example     UnknownHost          20       1239.2          5.5               2
```

With a cache time of 0 the JVM does not cache successful lookups: 20 lookups cost 40 queries (an A and an AAAA each), and the median time rose from about 1 microsecond to hundreds, because every lookup now goes over UDP. The missing name is still cached for the negative time (10 seconds), so it cost only 2 queries.

**4. Hear a packet by hand.** Start the servers (`./gradlew -q serve --args='config/serve.properties'`) and send the bytes of a query with `nc`:

```text
printf '\x12\x34\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x08seatwave\x07example\x00\x00\x01\x00\x01' | nc -u -w1 127.0.0.1 5303 | xxd
```

The answer starts `1234 8400 0001 0001`: your identifier `1234`, flags `8400` (response, authoritative), one question, one answer. The `-u` flag of `nc` means UDP; `-w1` means wait one second for the reply.

**5. Stop.** The servers stop by themselves after 300 seconds, or press Ctrl-C.

## Mistakes people make

- **Reading a `byte` without `& 0xFF`.** It turns 132 into -124. Every read in `MessageCodec` masks.
- **Little-endian numbers on the wire.** DNS is big-endian. The codec shifts and masks.
- **Waiting without a timeout on UDP.** A lost datagram means waiting for ever. `DnsClient` always has a deadline.
- **Accepting any datagram.** On an open port, any computer can send anything. `connect` limits the sender, and the client still checks the identifier and the question.
- **Believing UDP delivers.** It does not. The client must time out and ask again; the lab client reports the timeout and does not retry.
- **Ignoring a truncated reply.** A UDP reply over 512 bytes is cut to its header and question and flagged; a real client asks again over TCP. This lab server does the cutting but offers no TCP.
- **Forgetting that the resolver is loaded once.** The properties must be set before the first lookup in the JVM.
- **Sending a lookup to the real network from a test.** `LabResolver` refuses every name outside `seatwave.example`.
- **Putting the services file in the wrong place.** The file must be in `META-INF/services` inside a folder on the class path, with the name of the service, one class name per line. A typo gives no provider and no error.

## Check yourself

1. What is the difference between TCP and UDP in one sentence each? (TCP is a reliable, ordered stream set up by a handshake; UDP is single unreliable datagrams with no setup.)
2. Why is a DNS query sent over UDP? (One packet out and one packet back, no handshake, no per-client state; a lost packet is handled by a timeout.)
3. What does `0x1234` look like on the wire, and what does `(byte) 0x84 & 0xFF` give? (The bytes `12 34`; 132.)
4. What does the file `META-INF/services/java.net.spi.InetAddressResolverProvider` do? (It names the class that provides the resolver, so `ServiceLoader` can find it.)
5. Why is the lab provider off unless a system property is set? (Every program with this project on its class path finds it, including the tests; off means the built-in resolver is used.)

## Further reading

- RFC 768, User Datagram Protocol: https://www.rfc-editor.org/rfc/rfc768
- RFC 1035 section 4.2, Transport (UDP and TCP): https://www.rfc-editor.org/rfc/rfc1035
- RFC 7766, DNS Transport over TCP: https://www.rfc-editor.org/rfc/rfc7766
- The Java SE 25 API, `java.net.DatagramSocket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/DatagramSocket.html
- The Java SE 25 API, `java.net.DatagramPacket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/DatagramPacket.html
- The Java SE 25 API, `java.net.spi.InetAddressResolverProvider`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/spi/InetAddressResolverProvider.html
- The Java SE 25 API, `java.util.ServiceLoader` (the `META-INF/services` format): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/ServiceLoader.html
- JEP 418, Internet-Address Resolution SPI: https://openjdk.org/jeps/418
