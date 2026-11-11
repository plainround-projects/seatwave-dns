package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * Sends one question at a time to one DNS server over UDP and waits for the matching response.
 *
 * <p>Each query gets a random identifier from {@link SecureRandom}, and a response counts only if
 * its identifier and its question match the query; anything else is ignored until the timeout. That
 * is the defense RFC 5452 asks for against forged answers. The client asks without the
 * recursion-desired flag, as a resolver asks an authoritative server. Not thread-safe: one client
 * per thread; close it to release its socket.
 */
public final class DnsClient implements Upstream, AutoCloseable {

  // Identifiers are 16 bits; the response buffer holds the largest UDP message without EDNS.
  private static final int ID_SPACE = 1 << 16;
  private static final int RESPONSE_BUFFER_OCTETS = 512;

  private final DatagramSocket socket;
  private final Duration timeout;
  private final SecureRandom random = new SecureRandom();

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

  /** Closes the socket. */
  @Override
  public void close() {
    socket.close();
  }

  private static boolean isAnswerTo(Message response, int id, Question question) {
    return response.isResponse()
        && response.id() == id
        && response.questions().size() == 1
        && response.questions().getFirst().equals(question);
  }
}
