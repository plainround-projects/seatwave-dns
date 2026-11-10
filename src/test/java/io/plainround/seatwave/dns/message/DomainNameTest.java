package io.plainround.seatwave.dns.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainNameTest {

  private static final int TOO_LONG_LABEL = 64;
  private static final int LABELS_OVER_255_OCTETS = 5;
  private static final int BOOK_WIRE_OCTETS = 23;

  @Test
  void readsAbsoluteAndRelativeSpellingsAsOneLowerCaseName() {
    DomainName withDot = TestNames.name("SeatWave.Example.");
    DomainName withoutDot = TestNames.name("seatwave.example");

    assertThat(withDot).isEqualTo(withoutDot);
    assertThat(withDot.labels()).containsExactly("seatwave", "example");
    assertThat(withDot).hasToString("seatwave.example.");
  }

  @Test
  void readsTheRoot() {
    assertThat(TestNames.name(".")).isEqualTo(DomainName.ROOT);
    assertThat(DomainName.ROOT).hasToString(".");
    assertThat(DomainName.ROOT.wireLength()).isEqualTo(1);
  }

  @Test
  void refusesEmptyLabelsLongLabelsAndCharactersOutsideTheAllowList() {
    assertThat(problem("")).isEqualTo("a name must not be empty");
    assertThat(problem("seatwave..example")).startsWith("a name must not have an empty label");
    assertThat(problem("a".repeat(TOO_LONG_LABEL) + ".example"))
        .startsWith("a label is longer than 63 octets");
    assertThat(problem("seat wave.example")).startsWith("a label may hold only");
    assertThat(problem("-seatwave.example")).startsWith("a label may hold only");
  }

  @Test
  void refusesANameLongerThan255Octets() {
    String label = "a".repeat(DomainName.MAX_LABEL_OCTETS);
    String name = String.join(".", Collections.nCopies(LABELS_OVER_255_OCTETS, label));

    assertThat(problem(name)).startsWith("a name is longer than 255 octets");
  }

  @Test
  void refusesAnInvalidLabelInTheConstructor() {
    assertThatThrownBy(() -> new DomainName(List.of("Upper")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void knowsItsPlaceInTheTree() {
    DomainName book = TestNames.name("book.seatwave.example");

    assertThat(book.isAtOrBelow(TestNames.name("seatwave.example"))).isTrue();
    assertThat(book.isAtOrBelow(DomainName.ROOT)).isTrue();
    assertThat(book.isAtOrBelow(TestNames.name("example.com"))).isFalse();
    assertThat(TestNames.name("example").isAtOrBelow(book)).isFalse();
    assertThat(book.selfAndAncestors())
        .extracting(DomainName::toString)
        .containsExactly("book.seatwave.example.", "seatwave.example.", "example.", ".");
    // 1 + 4 (book) + 1 + 8 (seatwave) + 1 + 7 (example) + the final zero octet.
    assertThat(book.wireLength()).isEqualTo(BOOK_WIRE_OCTETS);
  }

  private static String problem(String text) {
    return ((Result.Error<DomainName, String>) DomainName.parse(text)).error();
  }
}
