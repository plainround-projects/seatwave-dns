package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

  private static final int ID = 0x1234;
  private static final int OPT_TYPE = 41;
  private static final int EDNS_UDP_SIZE = 1232;
  private static final int POINTER = 0xC0;
  private static final int RESERVED_LABEL = 0x40;
  private static final int HEADER_END = 12;
  private static final int FOUR = 4;
  private static final int THREE = 3;
  private static final int TTL_WITH_TOP_BIT = 0x80;
  private static final int TEN = 10;

  @Test
  void writesAndReadsEveryRecordTypeUnchanged() {
    Message message =
        new Message(
            ID,
            Message.RESPONSE_FLAG | Message.AUTHORITATIVE_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(
                TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"),
                TestNames.addressRecord("seatwave.example", 3600, "2001:db8::10"),
                TestNames.hostRecord(
                    "www.seatwave.example", RecordType.CNAME, 3600, "seatwave.example"),
                new ResourceRecord(
                    TestNames.name("seatwave.example"),
                    RecordType.TXT,
                    3600,
                    new RecordData.Text(List.of("seatwave-lab", "second string")))),
            List.of(
                TestNames.hostRecord(
                    "seatwave.example", RecordType.NS, 3600, "ns1.seatwave.example")),
            List.of(TestNames.soa("seatwave.example", 3600, 300)));

    Result<Message, MessageProblem> decoded = MessageCodec.decode(MessageCodec.encode(message));

    assertThat(decoded.discardError().orElseThrow()).isEqualTo(message);
  }

  @Test
  void followsACompressionPointerThatPointsBackward() {
    Packet packet = Packet.header(1, 1, 0, 0);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    // The answer's owner is a pointer to the question's name at offset 12.
    packet.bytes(POINTER, HEADER_END).shorts(RecordType.A.code(), 1).ints(60).shorts(FOUR);
    packet.bytes(192, 0, 2, 10);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.answers().getFirst()).hasToString("seatwave.example. 60 IN A 192.0.2.10");
  }

  @Test
  void refusesAPointerToItself() {
    Packet packet = Packet.header(1, 0, 0, 0);
    packet.bytes(POINTER, HEADER_END).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet))
        .isEqualTo(new MessageProblem.BadName("a compression pointer must point backward"));
  }

  @Test
  void refusesTwoPointersThatPointAtEachOther() {
    Packet packet = Packet.header(1, 0, 0, 0);
    // A label at 12, then a pointer at 14 back to 12; the label is followed by the pointer again.
    packet.bytes(1, 'a', POINTER, HEADER_END).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isInstanceOf(MessageProblem.BadName.class);
  }

  @Test
  void refusesAReservedLabelType() {
    Packet packet = Packet.header(1, 0, 0, 0);
    packet.bytes(RESERVED_LABEL, 0).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isEqualTo(new MessageProblem.BadName("a reserved label type"));
  }

  @Test
  void refusesANameLongerThan255OctetsOnTheWire() {
    Packet packet = Packet.header(1, 0, 0, 0);
    String label = "a".repeat(DomainName.MAX_LABEL_OCTETS);
    packet.name(label, label, label, label).shorts(RecordType.A.code(), 1);

    assertThat(problem(packet)).isEqualTo(new MessageProblem.BadName("longer than 255 octets"));
  }

  @Test
  void refusesAShortHeaderCountsTooLargeAndACutName() {
    assertThat(MessageCodec.decode(new byte[] {1, 2, THREE}))
        .isEqualTo(Result.error(new MessageProblem.Truncated("header")));
    Packet tooMany = Packet.header(0xFFFF, 0, 0, 0);
    assertThat(problem(tooMany)).isInstanceOf(MessageProblem.BadCounts.class);
    Packet cut = Packet.header(1, 0, 0, 0);
    // A label that says 10 octets, followed by only 4.
    cut.bytes(TEN, 's', 'e', 'a', 't');
    assertThat(problem(cut)).isEqualTo(new MessageProblem.Truncated("name"));
  }

  @Test
  void refusesAnAddressOfTheWrongLengthAndBytesAfterTheLastRecord() {
    Packet wrongLength = Packet.header(0, 1, 0, 0);
    wrongLength.name("seatwave", "example").shorts(RecordType.A.code(), 1).ints(60).shorts(THREE);
    wrongLength.bytes(192, 0, 2);
    assertThat(problem(wrongLength))
        .isEqualTo(new MessageProblem.BadRecord("an address record must hold 4 octets"));

    Packet trailing = Packet.header(1, 0, 0, 0);
    trailing.name("seatwave", "example").shorts(RecordType.A.code(), 1).bytes(0, 0, 0, 0, 0);
    assertThat(problem(trailing)).isEqualTo(new MessageProblem.TrailingBytes(FOUR + 1));
  }

  @Test
  void refusesTextThatRunsPastItsRecordAndASoaOfTheWrongLength() {
    Packet text = Packet.header(0, 1, 0, 0);
    text.name("seatwave", "example").shorts(RecordType.TXT.code(), 1).ints(60).shorts(THREE);
    text.bytes(FOUR + 1, 'a', 'b');
    assertThat(problem(text)).isInstanceOf(MessageProblem.BadRecord.class);

    Packet authority = Packet.header(0, 1, 0, 0);
    authority.name("seatwave", "example").shorts(RecordType.SOA.code(), 1).ints(60).shorts(THREE);
    authority.bytes(0, 0, 0);
    assertThat(problem(authority))
        .isEqualTo(new MessageProblem.BadRecord("an SOA record must end in 20 octets"));
  }

  @Test
  void skipsTheEdnsOptionRecordThatDigAdds() {
    Packet packet = Packet.header(1, 0, 0, 1);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    packet.bytes(0).shorts(OPT_TYPE, EDNS_UDP_SIZE).ints(0).shorts(0);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.questions()).hasSize(1);
    assertThat(decoded.additional()).isEmpty();
  }

  @Test
  void readsATtlWithTheTopBitSetAsZero() {
    Packet packet = Packet.header(0, 1, 0, 0);
    packet.name("seatwave", "example").shorts(RecordType.A.code(), 1);
    packet.bytes(TTL_WITH_TOP_BIT, 0, 0, 0).shorts(FOUR).bytes(192, 0, 2, 10);

    Message decoded = MessageCodec.decode(packet.toBytes()).discardError().orElseThrow();

    assertThat(decoded.answers().getFirst().ttlSeconds()).isZero();
  }

  @Test
  void readsTheIdentifierOfAnyPacketWithAHeader() {
    assertThat(MessageCodec.idOf(Packet.header(1, 0, 0, 0).toBytes())).isEqualTo(ID);
  }

  private static MessageProblem problem(Packet packet) {
    return ((Result.Error<Message, MessageProblem>) MessageCodec.decode(packet.toBytes())).error();
  }

  /** Builds a raw packet byte by byte, so a test can write what no encoder would. */
  static final class Packet {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static Packet header(int questions, int answers, int authority, int additional) {
      Packet packet = new Packet();
      return packet.shorts(ID, 0, questions, answers, authority, additional);
    }

    Packet name(String... labels) {
      for (String label : labels) {
        out.write(label.length());
        out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
      }
      out.write(0);
      return this;
    }

    Packet bytes(int... values) {
      for (int value : values) {
        out.write(value);
      }
      return this;
    }

    Packet shorts(int... values) {
      for (int value : values) {
        out.write(value >> Byte.SIZE);
        out.write(value);
      }
      return this;
    }

    Packet ints(int value) {
      return shorts(value >>> Short.SIZE, value & 0xFFFF);
    }

    byte[] toBytes() {
      return out.toByteArray();
    }
  }
}
