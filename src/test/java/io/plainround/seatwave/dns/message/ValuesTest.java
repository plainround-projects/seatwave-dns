package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ValuesTest {

  private static final int MX_TYPE = 15;
  private static final int TOO_BIG = 0x1_0000;
  private static final int TEXT_LIMIT = 255;

  @Test
  void refusesDataThatDoesNotMatchTheType() {
    RecordData ipv6 = new RecordData.Address(TestNames.address("2001:db8::10"));
    RecordData host = new RecordData.Host(TestNames.name("seatwave.example"));

    assertThatThrownBy(() -> new ResourceRecord(TestNames.name("x.example"), RecordType.A, 1, ipv6))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ResourceRecord(TestNames.name("x.example"), RecordType.TXT, 1, host))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesATtlOutsideTheRangeOfRfc2181() {
    assertThatThrownBy(() -> TestNames.addressRecord("x.example", -1, "192.0.2.10"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            TestNames.addressRecord("x.example", ResourceRecord.MAX_TTL_SECONDS + 1, "192.0.2.10"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesEachRecordTheWayDigDoes() {
    assertThat(TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"))
        .hasToString("seatwave.example. 3600 IN A 192.0.2.10");
    assertThat(
            TestNames.hostRecord("www.seatwave.example", RecordType.CNAME, 60, "seatwave.example"))
        .hasToString("www.seatwave.example. 60 IN CNAME seatwave.example.");
    assertThat(TestNames.soa("seatwave.example", 3600, 300))
        .hasToString(
            "seatwave.example. 3600 IN SOA ns1.seatwave.example. hostmaster.seatwave.example."
                + " 1 7200 900 1209600 300");
    assertThat(
            new ResourceRecord(
                TestNames.name("seatwave.example"),
                RecordType.TXT,
                3600,
                new RecordData.Text(List.of("one", "two"))))
        .hasToString("seatwave.example. 3600 IN TXT \"one\" \"two\"");
  }

  @Test
  void refusesEmptyOrLongTextAndSoaNumbersBeyond32Bits() {
    assertThatThrownBy(() -> new RecordData.Text(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RecordData.Text(List.of("x".repeat(TEXT_LIMIT + 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TestNames.soa("seatwave.example", 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsUnknownQuestionTypesAndRefusesCodesBeyond16Bits() {
    Question mail =
        new Question(TestNames.name("seatwave.example"), MX_TYPE, Question.CLASS_INTERNET);

    assertThat(mail.type()).isEmpty();
    assertThat(Question.of(TestNames.name("seatwave.example"), RecordType.AAAA).type())
        .contains(RecordType.AAAA);
    assertThatThrownBy(() -> new Question(TestNames.name("x.example"), TOO_BIG, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsTheFlagsOfAMessage() {
    Message response =
        new Message(
            1,
            Message.RESPONSE_FLAG | Message.AUTHORITATIVE_FLAG | ResponseCode.NAME_ERROR.code(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThat(response.isResponse()).isTrue();
    assertThat(response.isAuthoritative()).isTrue();
    assertThat(response.opcode()).isZero();
    assertThat(ResponseCode.fromCode(response.responseCodeValue()))
        .contains(ResponseCode.NAME_ERROR);
    assertThatThrownBy(() -> new Message(TOO_BIG, 0, List.of(), List.of(), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findsTypesByCodeAndByName() {
    assertThat(RecordType.fromCode(RecordType.AAAA.code())).contains(RecordType.AAAA);
    assertThat(RecordType.fromCode(MX_TYPE)).isEmpty();
    assertThat(RecordType.fromName("cname")).contains(RecordType.CNAME);
    assertThat(RecordType.fromName("MX")).isEmpty();
    assertThat(ResponseCode.fromCode(MX_TYPE)).isEmpty();
  }
}
