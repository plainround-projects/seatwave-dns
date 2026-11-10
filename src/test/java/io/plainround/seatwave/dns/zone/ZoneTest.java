package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ZoneTest {

  private static final DomainName APEX = TestNames.name("seatwave.example");
  private static final int MX_TYPE = 15;
  private static final int PICKS = 10_000;
  private static final long SEED = 20_261_110;
  // Three standard errors of a 90 percent share over 10,000 picks: 0.9 percentage points.
  private static final double TOLERANCE = 0.009;
  private static final double NINETY_PERCENT = 0.90;

  @Test
  void answersAnAddressFromTheZone() {
    Lookup lookup =
        TestZones.seatwave().lookup(question("seatwave.example", RecordType.A), random());

    assertThat(lookup)
        .isEqualTo(
            new Lookup.Answer(
                List.of(TestNames.addressRecord("seatwave.example", 3600, "192.0.2.10"))));
  }

  @Test
  void followsACnameInsideTheZone() {
    Lookup lookup =
        TestZones.seatwave().lookup(question("www.seatwave.example", RecordType.A), random());

    assertThat(((Lookup.Answer) lookup).records())
        .extracting(ResourceRecord::type)
        .containsExactly(RecordType.CNAME, RecordType.A);
  }

  @Test
  void refersToTheChildZoneWithGlue() {
    Lookup lookup =
        TestZones.root().lookup(question("book.seatwave.example", RecordType.A), random());

    Lookup.Referral referral = (Lookup.Referral) lookup;
    assertThat(referral.nameServers())
        .extracting(ResourceRecord::toString)
        .containsExactly("example. 172800 IN NS a.tld-lab.example.");
    assertThat(referral.glue())
        .extracting(ResourceRecord::toString)
        .containsExactly("a.tld-lab.example. 172800 IN A 127.0.0.1");
  }

  @Test
  void saysNoDataForAMissingTypeAndForANameWithOnlyChildren() {
    Zone seatwave = TestZones.seatwave();

    assertThat(seatwave.lookup(question("book.seatwave.example", RecordType.AAAA), random()))
        .isInstanceOf(Lookup.NoData.class);
    assertThat(seatwave.lookup(new Question(APEX, MX_TYPE, Question.CLASS_INTERNET), random()))
        .isInstanceOf(Lookup.NoData.class);
    assertThat(TestZones.example().lookup(question("tld-lab.example", RecordType.A), random()))
        .isInstanceOf(Lookup.NoData.class);
  }

  @Test
  void saysNameErrorWithTheSoaAndOutOfZoneForOtherNames() {
    Zone seatwave = TestZones.seatwave();

    Lookup missing = seatwave.lookup(question("nowhere.seatwave.example", RecordType.A), random());

    assertThat(missing).isEqualTo(new Lookup.NameError(seatwave.soa()));
    assertThat(seatwave.lookup(question("example.com", RecordType.A), random()))
        .isInstanceOf(Lookup.OutOfZone.class);
  }

  @Test
  void putsTheWeightedAddressFirstInProportionToItsWeight() {
    Zone zone = TestZones.seatwaveNinetyTen();
    SplittableRandom random = random();
    InetAddress siteOne = TestNames.address("192.0.2.10");
    int firstIsSiteOne = 0;

    for (int pick = 0; pick < PICKS; pick++) {
      Lookup.Answer answer =
          (Lookup.Answer) zone.lookup(question("book.seatwave.example", RecordType.A), random);
      assertThat(answer.records()).hasSize(2);
      if (((RecordData.Address) answer.records().getFirst().data()).address().equals(siteOne)) {
        firstIsSiteOne++;
      }
    }

    assertThat((double) firstIsSiteOne / PICKS).isCloseTo(NINETY_PERCENT, offset(TOLERANCE));
  }

  @Test
  void refusesEveryMisconfigurationWithOneLineEach() {
    List<ResourceRecord> records = new ArrayList<>(TestZones.seatwave().records());
    records.removeIf(record -> record.owner().equals(TestNames.name("ns1.seatwave.example")));
    records.add(
        TestNames.hostRecord("seatwave.example", RecordType.CNAME, 3600, "hosting.example"));
    records.add(
        TestNames.hostRecord(
            "site1.seatwave.example", RecordType.CNAME, 3600, "gone.seatwave.example"));
    records.add(
        TestNames.hostRecord(
            "loop1.seatwave.example", RecordType.CNAME, 60, "loop2.seatwave.example"));
    records.add(
        TestNames.hostRecord(
            "loop2.seatwave.example", RecordType.CNAME, 60, "loop1.seatwave.example"));
    records.add(TestNames.addressRecord("book.seatwave.example", 300, "203.0.113.30"));
    records.add(TestNames.addressRecord("book.seatwave.example", 60, "192.0.2.10"));
    records.add(TestNames.addressRecord("elsewhere.example", 60, "192.0.2.99"));
    records.add(TestNames.soa("seatwave.example", 3600, 300));
    Weights weights =
        new Weights(
            Map.of(
                TestNames.name("www.seatwave.example"), Map.of(TestNames.address("192.0.2.10"), 1),
                TestNames.name("site2.seatwave.example"),
                    Map.of(TestNames.address("192.0.2.77"), 1)));

    List<String> problems =
        ((Result.Error<Zone, List<String>>) Zone.of(APEX, records, weights)).error();

    assertThat(problems)
        .anyMatch(problem -> problem.startsWith("elsewhere.example. is outside the zone"))
        .anyMatch(problem -> problem.startsWith("the zone needs exactly one SOA record"))
        .anyMatch(problem -> problem.startsWith("a CNAME at the zone apex"))
        .anyMatch(problem ->
            problem.equals(
                "a CNAME at site1.seatwave.example. must be the only record of that name"))
        .anyMatch(problem ->
            problem.contains("points at gone.seatwave.example., which the zone does not have"))
        .anyMatch(problem -> problem.equals("the CNAME chain from loop1.seatwave.example. loops"))
        .anyMatch(problem ->
            problem.startsWith("the records of book.seatwave.example. A have different TTLs"))
        .anyMatch(problem ->
            problem.startsWith(
                "the record book.seatwave.example. 60 IN A 192.0.2.10 appears twice"))
        .anyMatch(problem ->
            problem.startsWith("broken delegation: the name server ns1.seatwave.example."))
        .anyMatch(problem -> problem.startsWith("weights are given for www.seatwave.example."))
        .anyMatch(problem -> problem.startsWith("a weight is given for 192.0.2.77"));
  }

  @Test
  void refusesAZoneWithoutNameServers() {
    List<String> problems =
        ((Result.Error<Zone, List<String>>)
                Zone.of(APEX, List.of(TestNames.soa("seatwave.example", 3600, 300)), Weights.NONE))
            .error();

    assertThat(problems)
        .containsExactly("the zone needs at least one NS record at its apex seatwave.example.");
  }

  private static Question question(String name, RecordType type) {
    return Question.of(TestNames.name(name), type);
  }

  private static SplittableRandom random() {
    return new SplittableRandom(SEED);
  }
}
