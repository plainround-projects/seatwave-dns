package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.zone.TestZones;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DnsServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final int DNS_PORT = 53;
  private static final int OVERSIZED = 600;

  @Test
  void answersAQueryOverUdpOnTheLoopbackAddress() throws Exception {
    try (DnsServer server = start();
        DnsClient client = new DnsClient(server.port(), TIMEOUT)) {
      Message response =
          client
              .ask(Question.of(TestNames.name("www.seatwave.example"), RecordType.A))
              .discardError()
              .orElseThrow();

      assertThat(response.answers())
          .extracting(ResourceRecord::type)
          .containsExactly(RecordType.CNAME, RecordType.A);
      assertThat(server.answered()).isEqualTo(1);
    }
  }

  @Test
  void dropsResponsesAndOversizedPacketsAndKeepsServing() throws Exception {
    try (DnsServer server = start();
        DnsClient client = new DnsClient(server.port(), TIMEOUT);
        DatagramSocket raw = new DatagramSocket()) {
      byte[] response =
          MessageCodec.encode(
              new Message(1, Message.RESPONSE_FLAG, List.of(), List.of(), List.of(), List.of()));
      send(raw, response, server.port());
      send(raw, new byte[OVERSIZED], server.port());

      // The server has one thread, so this answer arrives only after both bad packets were handled.
      client
          .ask(Question.of(TestNames.name("seatwave.example"), RecordType.A))
          .discardError()
          .orElseThrow();

      assertThat(server.dropped()).isEqualTo(2);
      assertThat(server.answered()).isEqualTo(1);
    }
  }

  @Test
  void refusesAPrivilegedPort() {
    assertThatThrownBy(() -> DnsServer.start(DNS_PORT, store(), new SplittableRandom(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("the port must be from 1024 to 65535, but was 53");
  }

  private static void send(DatagramSocket socket, byte[] bytes, int port) throws Exception {
    socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
  }

  private static DnsServer start() throws Exception {
    return DnsServer.start(DnsServer.ANY_FREE_PORT, store(), new SplittableRandom(1));
  }

  private static ZoneStore store() {
    return new ZoneStore(TestZones.seatwave(), Clock.systemUTC());
  }
}
