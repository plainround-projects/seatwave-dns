package io.plainround.seatwave.dns.message;

import java.util.Objects;
import java.util.Optional;

/**
 * The question of a DNS message: a name, a type code and a class code. The codes are kept as
 * numbers, because a query may ask for a type this lab does not serve, and the answer must echo it.
 *
 * @param name the name asked about
 * @param typeCode the record type code, 0 to 65,535
 * @param classCode the class code, 0 to 65,535; 1 is IN, the internet
 */
public record Question(DomainName name, int typeCode, int classCode) {

  /** The class code of the internet (RFC 1035 section 3.2.4). */
  public static final int CLASS_INTERNET = 1;

  // Type and class are 16-bit fields on the wire.
  private static final int MAX_CODE = 0xFFFF;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if a code does not fit in 16 bits
   */
  public Question {
    Objects.requireNonNull(name, "name");
    if (typeCode < 0 || typeCode > MAX_CODE || classCode < 0 || classCode > MAX_CODE) {
      throw new IllegalArgumentException("type and class codes must fit in 16 bits");
    }
  }

  /**
   * Builds an internet-class question for a type this lab serves.
   *
   * @param name the name
   * @param type the type
   * @return the question
   */
  public static Question of(DomainName name, RecordType type) {
    return new Question(name, type.code(), CLASS_INTERNET);
  }

  /**
   * Returns the record type asked for, if this lab serves it.
   *
   * @return the type, or empty for any other type code
   */
  public Optional<RecordType> type() {
    return RecordType.fromCode(typeCode);
  }
}
