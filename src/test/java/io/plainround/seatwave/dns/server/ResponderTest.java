package io.plainround.seatwave.dns.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import io.plainround.seatwave.dns.message.TestNames;
import io.plainround.seatwave.dns.zone.TestZones;
import io.plainround.seatwave.dns.zone.Weights;
import io.plainround.seatwave.dns.zone.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ResponderTest {

  private static final int ID = 4242;
  private static final long SEED = 1;
  private static final int CHAOS_CLASS = 3;
  private static final int NOTIFY_OPCODE_FLAGS = 4 << 11;
  private static final int TXT_RECORDS_OVER_512_OCTETS = 20;
  private static final long SOA_MINIMUM = 300;

  @Test
  void answersAuthoritativelyAndCopiesRecursionDesired() {
    Message query =
        new Message(
            ID,
            Message.RECURSION_DESIRED_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(),
            List.of(),
            List.of());

    Message response = respond(MessageCodec.encode(query), TestZones.seatwave());

    assertThat(response.id()).isEqualTo(ID);
    assertThat(response.isResponse()).isTrue();
    assertThat(response.isAuthoritative()).isTrue();
    assertThat(response.flags() & Message.RECURSION_DESIRED_FLAG).isNotZero();
    assertThat(response.answers())
        .extracting(ResourceRecord::toString)
        .containsExactly("seatwave.example. 3600 IN A 192.0.2.10");
  }

  @Test
  void saysNxdomainWithTheSoaAtItsNegativeTtl() {
    Message response =
        respond(query("nowhere.seatwave.example", RecordType.A), TestZones.seatwave());

    assertThat(response.responseCodeValue()).isEqualTo(ResponseCode.NAME_ERROR.code());
    assertThat(response.authority())
        .singleElement()
        .satisfies(soa -> assertThat(soa.ttlSeconds()).isEqualTo(SOA_MINIMUM));
  }

  @Test
  void refersWithoutTheAuthoritativeFlag() {
    Message response = respond(query("seatwave.example", RecordType.A), TestZones.example());

    assertThat(response.isAuthoritative()).isFalse();
    assertThat(response.authority())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.NS);
    assertThat(response.additional())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.A);
  }

  @Test
  void saysNoDataWithTheSoa() {
    Message response =
        respond(query("book.seatwave.example", RecordType.TXT), TestZones.seatwave());

    assertThat(response.responseCodeValue()).isEqualTo(ResponseCode.NO_ERROR.code());
    assertThat(response.answers()).isEmpty();
    assertThat(response.authority())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.SOA);
  }

  @Test
  void refusesNamesOutsideTheZoneAndOtherClasses() {
    Message outside = respond(query("example.com", RecordType.A), TestZones.seatwave());
    Message chaos =
        respond(
            MessageCodec.encode(
                Message.query(
                    ID, new Question(TestNames.name("seatwave.example"), 1, CHAOS_CLASS))),
            TestZones.seatwave());

    assertThat(outside.responseCodeValue()).isEqualTo(ResponseCode.REFUSED.code());
    assertThat(chaos.responseCodeValue()).isEqualTo(ResponseCode.REFUSED.code());
  }

  @Test
  void answersFormerrToUnreadableQueriesAndNotimpToOtherOpcodes() {
    byte[] unreadable = query("seatwave.example", RecordType.A);
    unreadable[MessageCodec.HEADER_OCTETS] = (byte) 0xC0;
    Message notify =
        new Message(
            ID,
            NOTIFY_OPCODE_FLAGS,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.SOA)),
            List.of(),
            List.of(),
            List.of());
    Message twoQuestions =
        new Message(
            ID,
            0,
            List.of(
                Question.of(TestNames.name("seatwave.example"), RecordType.A),
                Question.of(TestNames.name("seatwave.example"), RecordType.AAAA)),
            List.of(),
            List.of(),
            List.of());

    assertThat(respond(unreadable, TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.FORMAT_ERROR.code());
    assertThat(respond(MessageCodec.encode(notify), TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.NOT_IMPLEMENTED.code());
    assertThat(respond(MessageCodec.encode(twoQuestions), TestZones.seatwave()).responseCodeValue())
        .isEqualTo(ResponseCode.FORMAT_ERROR.code());
  }

  @Test
  void neverAnswersAResponseOrAPacketShorterThanAHeader() {
    Message response =
        new Message(
            ID,
            Message.RESPONSE_FLAG,
            List.of(Question.of(TestNames.name("seatwave.example"), RecordType.A)),
            List.of(),
            List.of(),
            List.of());

    assertThat(Responder.respond(MessageCodec.encode(response), TestZones.seatwave(), random()))
        .isEmpty();
    assertThat(Responder.respond(new byte[] {1, 2}, TestZones.seatwave(), random())).isEmpty();
  }

  @Test
  void cutsAnAnswerLongerThan512OctetsToItsHeaderAndQuestion() {
    Zone zone = TestZones.seatwave();
    List<ResourceRecord> records = new ArrayList<>(zone.records());
    for (int index = 0; index < TXT_RECORDS_OVER_512_OCTETS; index++) {
      records.add(
          new ResourceRecord(
              TestNames.name("big.seatwave.example"),
              RecordType.TXT,
              60,
              new RecordData.Text(List.of("padding-" + index + "-".repeat(30)))));
    }
    Zone big = zone.with(records, Weights.NONE).discardError().orElseThrow();

    Message response = respond(query("big.seatwave.example", RecordType.TXT), big);

    assertThat(response.flags() & Message.TRUNCATED_FLAG).isNotZero();
    assertThat(response.answers()).isEmpty();
    assertThat(response.questions()).hasSize(1);
  }

  private static byte[] query(String name, RecordType type) {
    return MessageCodec.encode(Message.query(ID, Question.of(TestNames.name(name), type)));
  }

  private static Message respond(byte[] query, Zone zone) {
    byte[] bytes = Responder.respond(query, zone, random()).orElseThrow();
    return MessageCodec.decode(bytes).discardError().orElseThrow();
  }

  private static SplittableRandom random() {
    return new SplittableRandom(SEED);
  }
}
