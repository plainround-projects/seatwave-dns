package io.plainround.seatwave.dns.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.TestNames;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightsTest {

  private static final DomainName BOOK = TestNames.name("book.seatwave.example");
  private static final InetAddress SITE_ONE = TestNames.address("192.0.2.10");
  private static final InetAddress SITE_TWO = TestNames.address("198.51.100.20");
  private static final InetAddress NEW_SITE = TestNames.address("203.0.113.30");
  private static final int NINETY = 90;
  private static final int TEN = 10;
  private static final int TOO_HEAVY = 256;

  @Test
  void readsTheCoreDnsWeightFileFormat() {
    Weights weights =
        Weights.parse("# comment\nbook.seatwave.example\n192.0.2.10 90\n198.51.100.20 10\n")
            .discardError()
            .orElseThrow();

    assertThat(weights.weightOf(BOOK, SITE_ONE)).isEqualTo(NINETY);
    assertThat(weights.weightOf(BOOK, SITE_TWO)).isEqualTo(TEN);
    assertThat(weights.weightOf(BOOK, NEW_SITE)).isEqualTo(Weights.MIN_WEIGHT);
  }

  @Test
  void reportsWeightsOutOfRangeAndLinesInTheWrongPlace() {
    String text = "192.0.2.10 5\nbook..example\nbook.seatwave.example\n192.0.2.10 0\nx 1\na b c\n";

    List<String> problems = ((Result.Error<Weights, List<String>>) Weights.parse(text)).error();

    assertThat(problems)
        .containsExactly(
            "weights line 1: expected a name, or an address and a weight after a name",
            "weights line 2: a name must not have an empty label: book..example",
            "weights line 4: a weight must be from 1 to 255, but was 0",
            "weights line 5: not an address and a whole-number weight: x 1",
            "weights line 6: expected a name, or an address and a weight after a name");
    assertThatThrownBy(() -> new Weights(Map.of(BOOK, Map.of(SITE_ONE, TOO_HEAVY))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void movesAWeightToTheNewAddressOfOneNameOnly() {
    DomainName apex = TestNames.name("seatwave.example");
    Weights weights =
        new Weights(Map.of(BOOK, Map.of(SITE_ONE, NINETY), apex, Map.of(SITE_ONE, TEN)));

    Weights moved = weights.withAddressReplaced(apex, SITE_ONE, NEW_SITE);

    assertThat(moved.weightOf(apex, NEW_SITE)).isEqualTo(TEN);
    assertThat(moved.weightOf(BOOK, SITE_ONE)).isEqualTo(NINETY);
    assertThat(weights.withAddressReplaced(apex, SITE_TWO, NEW_SITE)).isEqualTo(weights);
  }
}
