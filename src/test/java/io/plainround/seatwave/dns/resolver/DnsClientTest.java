package io.plainround.seatwave.dns.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.TestNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DnsClientTest {

  private static final Duration SHORT = Duration.ofMillis(200);
  private static final Duration LONG = Duration.ofSeconds(2);
  private static final Question QUESTION =
      Question.of(TestNames.name("seatwave.example"), RecordType.A);
  private static final int BUFFER = 512;

  @Test
  void timesOutWhenTheServerNeverAnswers() throws Exception {
    try (DatagramSocket silent =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(silent.getLocalPort(), SHORT)) {
      assertThat(client.ask(QUESTION)).isEqualTo(Result.error(new LookupProblem.TimedOut(SHORT)));
    }
  }

  @Test
  void reportsAClosedPortAsUnreachable() throws Exception {
    int closedPort;
    try (DatagramSocket probe =
        new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
      closedPort = probe.getLocalPort();
    }
    try (DnsClient client = new DnsClient(closedPort, LONG)) {
      assertThat(client.ask(QUESTION))
          .isInstanceOf(Result.Error.class)
          .extracting(result -> ((Result.Error<Message, LookupProblem>) result).error())
          .isInstanceOf(LookupProblem.Unreachable.class);
    }
  }

  @Test
  void ignoresAResponseWithTheWrongIdentifierAndTakesTheRightOne() throws Exception {
    try (DatagramSocket fake =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(fake.getLocalPort(), LONG)) {
      Thread server =
          Thread.ofVirtual()
              .start(() -> {
                try {
                  DatagramPacket query = new DatagramPacket(new byte[BUFFER], BUFFER);
                  fake.receive(query);
                  Message parsed =
                      MessageCodec.decode(Arrays.copyOf(query.getData(), query.getLength()))
                          .discardError()
                          .orElseThrow();
                  reply(fake, query, (parsed.id() + 1) & 0xFFFF);
                  reply(fake, query, parsed.id());
                } catch (IOException failed) {
                  throw new UncheckedIOException(failed);
                }
              });

      Result<Message, LookupProblem> answer = client.ask(QUESTION);
      server.join(LONG);

      assertThat(answer).isInstanceOf(Result.Success.class);
    }
  }

  @Test
  void reportsAnUnreadableResponseAsMalformed() throws Exception {
    try (DatagramSocket fake =
            new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        DnsClient client = new DnsClient(fake.getLocalPort(), LONG)) {
      Thread server =
          Thread.ofVirtual()
              .start(() -> {
                try {
                  DatagramPacket query = new DatagramPacket(new byte[BUFFER], BUFFER);
                  fake.receive(query);
                  fake.send(new DatagramPacket(new byte[] {1, 2, 3}, 3, query.getSocketAddress()));
                } catch (IOException failed) {
                  throw new UncheckedIOException(failed);
                }
              });

      Result<Message, LookupProblem> answer = client.ask(QUESTION);
      server.join(LONG);

      assertThat(((Result.Error<Message, LookupProblem>) answer).error().describe())
          .isEqualTo("unreadable response: the packet ends inside the header");
    }
  }

  @Test
  void refusesAZeroTimeout() {
    assertThatThrownBy(() -> new DnsClient(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void reply(DatagramSocket socket, DatagramPacket query, int id)
      throws IOException {
    byte[] response =
        MessageCodec.encode(
            new Message(
                id, Message.RESPONSE_FLAG, List.of(QUESTION), List.of(), List.of(), List.of()));
    socket.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
  }
}
