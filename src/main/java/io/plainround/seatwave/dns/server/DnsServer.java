package io.plainround.seatwave.dns.server;

import io.plainround.seatwave.dns.migration.ZoneStore;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

/**
 * An authoritative DNS server for one zone, on UDP, on the loopback address only. One platform
 * thread receives a packet, asks the {@link Responder} and sends the reply; the work per packet is
 * a few microseconds, so one thread is enough for a lab and no queue can grow without bound.
 *
 * <p>The socket is bound before {@link #start} returns, so a test can send a query at once, without
 * waiting. Ports below 1024 are refused: they are privileged, and this lab never needs
 * administrator rights. Thread-safe: the counters may be read from any thread.
 */
public final class DnsServer implements AutoCloseable {

  /** Port 0 asks the operating system for any free port; tests use it. */
  public static final int ANY_FREE_PORT = 0;

  /** The lowest port a lab server may use; lower ports need administrator rights. */
  public static final int LOWEST_UNPRIVILEGED_PORT = 1024;

  private static final int HIGHEST_PORT = 65_535;
  // One octet more than the largest query accepted, so an oversized packet can be told apart.
  private static final int RECEIVE_BUFFER_OCTETS = Responder.MAX_UDP_OCTETS + 1;
  private static final Duration STOP_WAIT = Duration.ofSeconds(2);

  private final DatagramSocket socket;
  private final ZoneStore store;
  private final RandomGenerator random;
  private final Thread receiver;
  private final AtomicLong answered = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();

  private DnsServer(DatagramSocket socket, ZoneStore store, RandomGenerator random) {
    this.socket = socket;
    this.store = store;
    this.random = random;
    this.receiver = Thread.ofPlatform().name("dns-" + socket.getLocalPort()).unstarted(this::serve);
  }

  /**
   * Binds the socket on 127.0.0.1 and starts answering.
   *
   * @param port the UDP port, 1024 to 65,535, or {@link #ANY_FREE_PORT}
   * @param store the zone to answer from
   * @param random picks weighted answers; used only by this server's thread
   * @return the running server
   * @throws IOException if the port is taken
   */
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

  /**
   * Returns the port the server listens on.
   *
   * @return the bound port
   */
  public int port() {
    return socket.getLocalPort();
  }

  /**
   * Returns how many queries got a response.
   *
   * @return the count since start
   */
  public long answered() {
    return answered.get();
  }

  /**
   * Returns how many packets got no response: responses, packets shorter than a header, and packets
   * longer than 512 octets.
   *
   * @return the count since start
   */
  public long dropped() {
    return dropped.get();
  }

  /** Closes the socket and waits briefly for the receiving thread to end. */
  @Override
  public void close() {
    socket.close();
    try {
      receiver.join(STOP_WAIT);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

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
