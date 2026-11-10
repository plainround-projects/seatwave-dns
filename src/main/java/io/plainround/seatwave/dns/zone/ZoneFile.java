package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads a zone file in the master-file format of RFC 1035 section 5, the same files CoreDNS's
 * {@code file} plugin reads, limited to what this lab needs.
 *
 * <p>Supported: {@code $ORIGIN} and {@code $TTL}; one record per line; an owner of {@code @}, a
 * relative name, an absolute name ending in a dot, or a blank owner meaning "the previous one"; an
 * optional TTL and the optional class {@code IN}; the types A, AAAA, NS, CNAME, TXT and SOA (the
 * SOA on one line); comments after a semicolon. Not supported, and reported as a problem:
 * parentheses, other classes, other types and {@code $INCLUDE}. Stateless and thread-safe.
 */
public final class ZoneFile {

  // The seven fields after "SOA": two names and five numbers (RFC 1035 section 3.3.13).
  private static final int SOA_FIELDS = 7;

  private ZoneFile() {}

  /**
   * Parses a zone file.
   *
   * @param text the file's content
   * @param origin the origin used until a {@code $ORIGIN} line changes it
   * @return the records in file order, or one line per problem with its line number
   */
  public static Result<List<ResourceRecord>, List<String>> parse(String text, DomainName origin) {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(origin, "origin");
    State state = new State(origin);
    List<String> lines = text.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      state.where = "line " + (index + 1) + ": ";
      String raw = lines.get(index);
      Optional<List<String>> tokens = tokens(raw, state);
      if (tokens.isEmpty() || tokens.get().isEmpty()) {
        continue;
      }
      boolean hasBlankOwner = Character.isWhitespace(raw.charAt(0));
      readLine(tokens.get(), hasBlankOwner, state);
    }
    return state.problems.isEmpty()
        ? Result.success(List.copyOf(state.records))
        : Result.error(List.copyOf(state.problems));
  }

  private static void readLine(List<String> tokens, boolean hasBlankOwner, State state) {
    String first = tokens.getFirst();
    if (first.equals("$ORIGIN") && tokens.size() == 2) {
      absoluteName(tokens.get(1), state).ifPresent(name -> state.origin = name);
      return;
    }
    if (first.equals("$TTL") && tokens.size() == 2) {
      ttl(tokens.get(1), state).ifPresent(state::setDefaultTtl);
      return;
    }
    if (first.startsWith("$")) {
      state.problems.add(state.where + "unsupported directive " + first);
      return;
    }
    int next = 0;
    Optional<DomainName> owner;
    if (hasBlankOwner) {
      owner = state.previousOwner();
      if (owner.isEmpty()) {
        state.problems.add(state.where + "a blank owner needs a record above it");
        return;
      }
    } else {
      owner = name(tokens.getFirst(), state);
      next = 1;
    }
    Optional<Long> recordTtl = Optional.empty();
    // The TTL and the class may come in either order (RFC 1035 section 5.1).
    for (int optional = 0; optional < 2 && next < tokens.size(); optional++) {
      String token = tokens.get(next);
      if (token.chars().allMatch(Character::isDigit)) {
        recordTtl = ttl(token, state);
        if (recordTtl.isEmpty()) {
          // The bad TTL is already reported; one problem per line is enough.
          return;
        }
        next++;
      } else if (token.equalsIgnoreCase("IN")) {
        next++;
      }
    }
    if (owner.isEmpty() || next >= tokens.size()) {
      if (owner.isPresent()) {
        state.problems.add(state.where + "a record needs a type");
      }
      return;
    }
    state.setPreviousOwner(owner.get());
    Optional<Long> effectiveTtl = recordTtl.or(state::defaultTtl);
    if (effectiveTtl.isEmpty()) {
      state.problems.add(state.where + "no TTL on the record and no $TTL line above it");
      return;
    }
    record(owner.get(), effectiveTtl.get(), tokens.subList(next, tokens.size()), state)
        .ifPresent(state.records::add);
  }

  private static Optional<ResourceRecord> record(
      DomainName owner, long ttlSeconds, List<String> typeAndData, State state) {
    Optional<RecordType> type = RecordType.fromName(typeAndData.getFirst());
    if (type.isEmpty()) {
      state.problems.add(state.where + "unsupported type or class " + typeAndData.getFirst());
      return Optional.empty();
    }
    List<String> data = typeAndData.subList(1, typeAndData.size());
    Optional<RecordData> parsed =
        switch (type.get()) {
          case A, AAAA -> address(data, type.get(), state);
          case NS, CNAME ->
              one(data, state).flatMap(target -> name(target, state)).map(RecordData.Host::new);
          case TXT -> text(data, state);
          case SOA -> authority(data, state);
        };
    return parsed.map(recordData -> new ResourceRecord(owner, type.get(), ttlSeconds, recordData));
  }

  private static Optional<RecordData> address(List<String> data, RecordType type, State state) {
    return one(data, state)
        .flatMap(text -> {
          try {
            // ofLiteral never looks a name up, so a typo cannot send a query anywhere.
            InetAddress address = InetAddress.ofLiteral(text);
            boolean isRightFamily =
                type == RecordType.A
                    ? address instanceof Inet4Address
                    : address instanceof Inet6Address;
            if (!isRightFamily) {
              state.problems.add(state.where + "not an " + type + " address: " + text);
              return Optional.empty();
            }
            return Optional.of(new RecordData.Address(address));
          } catch (IllegalArgumentException notAnAddress) {
            state.problems.add(state.where + "not an " + type + " address: " + text);
            return Optional.empty();
          }
        });
  }

  private static Optional<RecordData> text(List<String> data, State state) {
    if (data.isEmpty()) {
      state.problems.add(state.where + "a TXT record needs a string");
      return Optional.empty();
    }
    try {
      return Optional.of(new RecordData.Text(data));
    } catch (IllegalArgumentException tooLong) {
      state.problems.add(state.where + tooLong.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<RecordData> authority(List<String> data, State state) {
    if (data.size() != SOA_FIELDS) {
      state.problems.add(
          state.where + "an SOA needs 7 fields on one line; parentheses are not supported");
      return Optional.empty();
    }
    Optional<DomainName> primary = name(data.get(0), state);
    Optional<DomainName> mailbox = name(data.get(1), state);
    List<Optional<Long>> numbers =
        data.subList(2, SOA_FIELDS).stream().map(token -> ttl(token, state)).toList();
    if (primary.isEmpty() || mailbox.isEmpty() || numbers.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }
    return Optional.of(
        new RecordData.Authority(
            primary.get(),
            mailbox.get(),
            numbers.get(0).get(),
            numbers.get(1).get(),
            numbers.get(2).get(),
            numbers.get(3).get(),
            numbers.get(4).get()));
  }

  private static Optional<String> one(List<String> data, State state) {
    if (data.size() != 1) {
      state.problems.add(state.where + "expected exactly one value, but found " + data.size());
      return Optional.empty();
    }
    return Optional.of(data.getFirst());
  }

  private static Optional<DomainName> name(String token, State state) {
    if (token.equals("@")) {
      return Optional.of(state.origin);
    }
    // Under the root origin a relative name only gains its final dot.
    String suffix = state.origin.equals(DomainName.ROOT) ? "" : state.origin.toString();
    String absolute = token.endsWith(".") ? token : token + "." + suffix;
    return absoluteName(absolute, state);
  }

  private static Optional<DomainName> absoluteName(String token, State state) {
    return switch (DomainName.parse(token)) {
      case Result.Success<DomainName, String>(DomainName name) -> Optional.of(name);
      case Result.Error<DomainName, String>(String problem) -> {
        state.problems.add(state.where + problem);
        yield Optional.empty();
      }
    };
  }

  private static Optional<Long> ttl(String token, State state) {
    try {
      long seconds = Long.parseLong(token);
      if (seconds < 0 || seconds > ResourceRecord.MAX_TTL_SECONDS) {
        state.problems.add(state.where + "a number must be from 0 to 2147483647: " + token);
        return Optional.empty();
      }
      return Optional.of(seconds);
    } catch (NumberFormatException notANumber) {
      state.problems.add(state.where + "not a whole number: " + token);
      return Optional.empty();
    }
  }

  private static Optional<List<String>> tokens(String line, State state) {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    boolean isQuoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        isQuoted = !isQuoted;
        if (!isQuoted) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else if (isQuoted) {
        token.append(character);
      } else if (character == ';') {
        break;
      } else if (character == '(' || character == ')') {
        state.problems.add(
            state.where + "parentheses are not supported; write the record on one line");
        return Optional.empty();
      } else if (Character.isWhitespace(character)) {
        if (!token.isEmpty()) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else {
        token.append(character);
      }
    }
    if (isQuoted) {
      state.problems.add(state.where + "a quote is not closed");
      return Optional.empty();
    }
    if (!token.isEmpty()) {
      tokens.add(token.toString());
    }
    return Optional.of(tokens);
  }

  /** The parser's position in one file. Confined to one call of {@link #parse}. */
  private static final class State {
    private final List<ResourceRecord> records = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    // A one-element list stands for "set or not", because Optional is kept to return types.
    private final List<DomainName> previousOwner = new ArrayList<>();
    private final List<Long> defaultTtl = new ArrayList<>();
    private DomainName origin;
    private String where = "";

    State(DomainName origin) {
      this.origin = origin;
    }

    Optional<DomainName> previousOwner() {
      return previousOwner.stream().findFirst();
    }

    void setPreviousOwner(DomainName owner) {
      previousOwner.clear();
      previousOwner.add(owner);
    }

    Optional<Long> defaultTtl() {
      return defaultTtl.stream().findFirst();
    }

    void setDefaultTtl(long seconds) {
      defaultTtl.clear();
      defaultTtl.add(seconds);
    }
  }
}
