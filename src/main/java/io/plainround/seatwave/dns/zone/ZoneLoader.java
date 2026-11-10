package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.config.SettingsReader;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads a zone from the three settings every program uses: {@code <prefix>.origin}, {@code
 * <prefix>.file} and {@code <prefix>.weights}, where the weights are a file path or the word {@code
 * none}. Stateless and thread-safe.
 */
public final class ZoneLoader {

  /** The value of a weights key that means "this zone has no weighted names". */
  public static final String NO_WEIGHTS = "none";

  private ZoneLoader() {}

  /**
   * Reads the three keys and loads the zone they name.
   *
   * @param reader the settings, which records a problem for each missing key
   * @param prefix the key prefix, for example {@code zone}
   * @return the zone, or one line per problem in the files or the zone; a missing key is recorded
   *     in the reader only, so the caller reports it once with the reader's other problems
   */
  public static Result<Zone, List<String>> load(SettingsReader reader, String prefix) {
    Objects.requireNonNull(reader, "reader");
    String origin = reader.text(prefix + ".origin");
    String file = reader.text(prefix + ".file");
    String weights = reader.text(prefix + ".weights");
    if (origin.isEmpty() || file.isEmpty() || weights.isEmpty()) {
      return Result.error(List.of());
    }
    return load(origin, Path.of(file), weights);
  }

  /**
   * Loads a zone file and its weights file.
   *
   * @param originText the apex, for example {@code seatwave.example.}
   * @param zoneFile the zone file
   * @param weightsFile a weights file path, or {@code none}
   * @return the zone, or one line per problem
   */
  public static Result<Zone, List<String>> load(
      String originText, Path zoneFile, String weightsFile) {
    List<String> problems = new ArrayList<>();
    DomainName origin = DomainName.ROOT;
    switch (DomainName.parse(originText)) {
      case Result.Success<DomainName, String>(DomainName parsed) -> origin = parsed;
      case Result.Error<DomainName, String>(String problem) -> problems.add("origin: " + problem);
    }
    List<ResourceRecord> records = List.of();
    Weights weights = Weights.NONE;
    try {
      switch (ZoneFile.parse(Files.readString(zoneFile, StandardCharsets.UTF_8), origin)) {
        case Result.Success<List<ResourceRecord>, List<String>>(List<ResourceRecord> parsed) ->
            records = parsed;
        case Result.Error<List<ResourceRecord>, List<String>>(List<String> found) ->
            found.forEach(problem -> problems.add(zoneFile.getFileName() + " " + problem));
      }
      if (!weightsFile.equals(NO_WEIGHTS)) {
        switch (Weights.parse(Files.readString(Path.of(weightsFile), StandardCharsets.UTF_8))) {
          case Result.Success<Weights, List<String>>(Weights parsed) -> weights = parsed;
          case Result.Error<Weights, List<String>>(List<String> found) -> problems.addAll(found);
        }
      }
    } catch (IOException unreadable) {
      // A missing file is a mistake in the settings, so it is reported like any other problem.
      problems.add("cannot read " + unreadable.getMessage());
    }
    if (!problems.isEmpty()) {
      return Result.error(problems);
    }
    return Zone.of(origin, records, weights);
  }
}
