package io.plainround.seatwave.dns.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.dns.message.RecordType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final int TEN = 10;

  @Test
  void readsEveryKindOfValue() {
    SettingsReader reader =
        reader("count", "7", "wait.millis", "40", "type", "aaaa", "names", "root, tld");

    assertThat(reader.intBetween("count", 1, TEN)).isEqualTo(7);
    assertThat(reader.millisBetween("wait.millis", 0, Integer.MAX_VALUE))
        .isEqualTo(Duration.ofMillis(40));
    assertThat(reader.enumValue("type", RecordType.class, RecordType.A)).isEqualTo(RecordType.AAAA);
    assertThat(reader.wordsIn("names", List.of("root", "tld"))).containsExactly("root", "tld");
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void reportsEveryProblemInOneRun() {
    SettingsReader reader =
        reader("count", "70", "word", "seven", "type", "mx", "names", "root,,tld", "other", "x");

    reader.intBetween("count", 1, TEN);
    reader.intBetween("word", 1, TEN);
    reader.intBetween("absent", 1, TEN);
    reader.enumValue("type", RecordType.class, RecordType.A);
    reader.wordsIn("names", List.of("root", "tld"));
    reader.wordsIn("other", List.of("root"));

    assertThat(reader.problems())
        .containsExactly(
            "count must be from 1 to 10, but was 70",
            "word must be a whole number, but was seven",
            "absent is missing",
            "type has an unknown value: mx",
            "names has an empty item",
            "other has x, but allows only root");
  }

  @Test
  void refusesTwoConstantsWhereOneIsExpected() {
    SettingsReader reader = reader("type", "a,aaaa");

    assertThat(reader.enumValue("type", RecordType.class, RecordType.TXT))
        .isEqualTo(RecordType.TXT);
    assertThat(reader.problems()).containsExactly("type takes one value, but has 2");
  }

  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }

  private static SettingsReader reader(String... keysAndValues) {
    Properties properties = new Properties();
    List<String> pairs = List.of(keysAndValues);
    for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex += 2) {
      properties.setProperty(pairs.get(pairIndex), pairs.get(pairIndex + 1));
    }
    return new SettingsReader(properties);
  }
}
