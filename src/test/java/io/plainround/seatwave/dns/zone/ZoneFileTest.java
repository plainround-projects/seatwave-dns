package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.TestNames;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneFileTest {

  private static final DomainName ORIGIN = TestNames.name("seatwave.example");

  @Test
  void readsOwnersTtlsClassesAndEveryType() {
    String text =
        """
        $TTL 300
        @      IN 3600 SOA ns1 hostmaster 1 7200 900 1209600 300 ; class before TTL
        @      3600 IN NS  ns1.seatwave.example.
        ns1    A   127.0.0.1
               AAAA 2001:db8::53
        www    60 CNAME @
        @      TXT "two words" "and; more"
        """;

    List<ResourceRecord> records = ZoneFile.parse(text, ORIGIN).discardError().orElseThrow();

    assertThat(records)
        .extracting(ResourceRecord::toString)
        .containsExactly(
            "seatwave.example. 3600 IN SOA ns1.seatwave.example. hostmaster.seatwave.example."
                + " 1 7200 900 1209600 300",
            "seatwave.example. 3600 IN NS ns1.seatwave.example.",
            "ns1.seatwave.example. 300 IN A 127.0.0.1",
            "ns1.seatwave.example. 300 IN AAAA 2001:db8:0:0:0:0:0:53",
            "www.seatwave.example. 60 IN CNAME seatwave.example.",
            "seatwave.example. 300 IN TXT \"two words\" \"and; more\"");
  }

  @Test
  void readsRelativeNamesUnderTheRoot() {
    List<ResourceRecord> records =
        ZoneFile.parse("$ORIGIN .\nexample 60 IN NS a.tld-lab.example.\n", DomainName.ROOT)
            .discardError()
            .orElseThrow();

    assertThat(records.getFirst().owner()).isEqualTo(TestNames.name("example"));
  }

  @Test
  void reportsEveryProblemWithItsLineNumber() {
    String text =
        """
          A 192.0.2.10
        @ 60 IN MX 10 mail
        @ 60 IN SOA ns1 hostmaster (
        @ A 192.0.2.10
        @ 60 A 2001:db8::1
        @ 60 TXT "open
        $INCLUDE other.zone
        @ 60 SOA ns1 hostmaster 1 2 3
        @ 60 A 192.0.2.10 192.0.2.11
        @ 99999999999 A 192.0.2.10
        bad..name 60 A 192.0.2.10
        @ 60 IN
        @ 60 TXT
        @ 60 SOA ns1 hostmaster 1 2 3 4 five
        """;

    List<String> problems =
        ((Result.Error<List<ResourceRecord>, List<String>>) ZoneFile.parse(text, ORIGIN)).error();

    assertThat(problems)
        .containsExactly(
            "line 1: a blank owner needs a record above it",
            "line 2: unsupported type or class MX",
            "line 3: parentheses are not supported; write the record on one line",
            "line 4: no TTL on the record and no $TTL line above it",
            "line 5: not an A address: 2001:db8::1",
            "line 6: a quote is not closed",
            "line 7: unsupported directive $INCLUDE",
            "line 8: an SOA needs 7 fields on one line; parentheses are not supported",
            "line 9: expected exactly one value, but found 2",
            "line 10: a number must be from 0 to 2147483647: 99999999999",
            "line 11: a name must not have an empty label: bad..name.seatwave.example.",
            "line 12: a record needs a type",
            "line 13: a TXT record needs a string",
            "line 14: not a whole number: five");
  }
}
