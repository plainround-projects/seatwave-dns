package io.plainround.seatwave.dns.message;

import com.github.sviperll.result4j.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A domain name in its canonical form: lower-case labels, from the leftmost to the top-level
 * domain, without the empty root label. The root itself has no labels.
 *
 * <p>Every label matches an allow-list (letters, digits, hyphen, underscore; no hyphen at either
 * end), is 1 to 63 octets long, and the whole name is at most 255 octets on the wire (RFC 1035
 * section 2.3.4). Names are compared case-insensitively by storing them in lower case (RFC 4343).
 *
 * @param labels the labels, leftmost first
 */
public record DomainName(List<String> labels) {

  /** The root of the name space, written "." */
  public static final DomainName ROOT = new DomainName(List.of());

  /** The longest label, in octets (RFC 1035 section 2.3.4). */
  public static final int MAX_LABEL_OCTETS = 63;

  /** The longest name on the wire, length octets and the final zero included (RFC 1035). */
  public static final int MAX_NAME_OCTETS = 255;

  // An allow-list, not a deny-list: anything else is refused at the boundary. The underscore is
  // allowed because service labels such as _dmarc use it (RFC 8552).
  private static final Pattern LABEL = Pattern.compile("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?");

  /**
   * Creates a name from labels that are already valid.
   *
   * @throws IllegalArgumentException if a label or the whole name is invalid
   */
  public DomainName {
    labels = List.copyOf(labels);
    String problem = problemWith(labels);
    if (!problem.isEmpty()) {
      throw new IllegalArgumentException(problem);
    }
  }

  /**
   * Parses a name written as text, such as {@code seatwave.example.} or {@code seatwave.example}.
   * Both forms mean the same absolute name.
   *
   * @param text the name
   * @return the name, or the reason it is invalid
   */
  public static Result<DomainName, String> parse(String text) {
    Objects.requireNonNull(text, "text");
    String trimmed = text.strip().toLowerCase(Locale.ROOT);
    if (trimmed.equals(".")) {
      return Result.success(ROOT);
    }
    String withoutDot =
        trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    if (withoutDot.isEmpty()) {
      return Result.error("a name must not be empty");
    }
    List<String> labels = List.of(withoutDot.split("\\.", -1));
    String problem = problemWith(labels);
    return problem.isEmpty()
        ? Result.success(new DomainName(labels))
        : Result.error(problem + ": " + text.strip());
  }

  /**
   * Says whether this name equals the other name or lies below it in the tree.
   *
   * @param ancestor the possible ancestor
   * @return true for {@code book.seatwave.example} against {@code seatwave.example}
   */
  public boolean isAtOrBelow(DomainName ancestor) {
    Objects.requireNonNull(ancestor, "ancestor");
    int extra = labels.size() - ancestor.labels.size();
    return extra >= 0 && labels.subList(extra, labels.size()).equals(ancestor.labels);
  }

  /**
   * Returns the names from this one up to the root, this one first and the root last.
   *
   * @return the chain of names, never empty
   */
  public List<DomainName> selfAndAncestors() {
    List<DomainName> chain = new ArrayList<>();
    for (int first = 0; first <= labels.size(); first++) {
      chain.add(new DomainName(labels.subList(first, labels.size())));
    }
    return List.copyOf(chain);
  }

  /**
   * Returns the length of this name on the wire, without compression.
   *
   * @return one length octet per label plus its octets, plus the final zero octet
   */
  public int wireLength() {
    int length = 1;
    for (String label : labels) {
      length += 1 + label.getBytes(StandardCharsets.US_ASCII).length;
    }
    return length;
  }

  /**
   * Writes the name as text, always absolute, with the trailing dot.
   *
   * @return for example {@code seatwave.example.}, or {@code .} for the root
   */
  @Override
  public String toString() {
    return labels.isEmpty() ? "." : String.join(".", labels) + ".";
  }

  // Package-private so the wire decoder checks labels with exactly the same rules.
  static String problemWith(List<String> labels) {
    int length = 1;
    for (String label : labels) {
      if (label.isEmpty()) {
        return "a name must not have an empty label";
      }
      if (label.length() > MAX_LABEL_OCTETS) {
        return "a label is longer than " + MAX_LABEL_OCTETS + " octets";
      }
      if (!LABEL.matcher(label).matches()) {
        return "a label may hold only lower-case letters, digits, hyphens and underscores";
      }
      length += 1 + label.length();
    }
    if (length > MAX_NAME_OCTETS) {
      return "a name is longer than " + MAX_NAME_OCTETS + " octets";
    }
    return "";
  }
}
