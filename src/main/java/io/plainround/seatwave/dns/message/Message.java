package io.plainround.seatwave.dns.message;

import java.util.List;

/**
 * A DNS message (RFC 1035 section 4.1): a 16-bit identifier, 16 bits of flags, and four lists. A
 * query carries one question; a response repeats it and adds answers, authority records (name
 * servers or the SOA) and additional records (addresses of those name servers, called glue).
 *
 * @param id the identifier that pairs a response with its query
 * @param flags the flags word: query or response, opcode, authoritative answer, truncated,
 *     recursion desired and available, and the response code
 * @param questions the questions, usually exactly one (RFC 9619)
 * @param answers the answer records
 * @param authority the authority records
 * @param additional the additional records
 */
public record Message(
    int id,
    int flags,
    List<Question> questions,
    List<ResourceRecord> answers,
    List<ResourceRecord> authority,
    List<ResourceRecord> additional) {

  /** Set in every response, clear in every query. */
  public static final int RESPONSE_FLAG = 0x8000;

  /** Set when the answer comes from a server authoritative for the name. */
  public static final int AUTHORITATIVE_FLAG = 0x0400;

  /** Set when the response did not fit and was cut short. */
  public static final int TRUNCATED_FLAG = 0x0200;

  /** Set by a client that wants the server to resolve the name fully on its behalf. */
  public static final int RECURSION_DESIRED_FLAG = 0x0100;

  // The opcode is bits 11 to 14 of the flags word; the response code is the lowest four bits.
  private static final int OPCODE_SHIFT = 11;
  private static final int OPCODE_MASK = 0xF;
  private static final int RESPONSE_CODE_MASK = 0xF;
  private static final int MAX_ID = 0xFFFF;

  /**
   * Checks the fields.
   *
   * @throws IllegalArgumentException if the identifier or the flags do not fit in 16 bits
   */
  public Message {
    if (id < 0 || id > MAX_ID || flags < 0 || flags > MAX_ID) {
      throw new IllegalArgumentException("the identifier and the flags must fit in 16 bits");
    }
    questions = List.copyOf(questions);
    answers = List.copyOf(answers);
    authority = List.copyOf(authority);
    additional = List.copyOf(additional);
  }

  /**
   * Builds a standard query with one question and recursion not desired, which is what a client
   * sends to an authoritative server.
   *
   * @param id the identifier, which should be random (RFC 5452)
   * @param question the question
   * @return the query
   */
  public static Message query(int id, Question question) {
    return new Message(id, 0, List.of(question), List.of(), List.of(), List.of());
  }

  /**
   * Says whether this message is a response.
   *
   * @return true if the response flag is set
   */
  public boolean isResponse() {
    return (flags & RESPONSE_FLAG) != 0;
  }

  /**
   * Says whether the answer came from a server authoritative for the name.
   *
   * @return true if the authoritative-answer flag is set
   */
  public boolean isAuthoritative() {
    return (flags & AUTHORITATIVE_FLAG) != 0;
  }

  /**
   * Returns the opcode; 0 is a standard query.
   *
   * @return the four-bit opcode
   */
  public int opcode() {
    return (flags >> OPCODE_SHIFT) & OPCODE_MASK;
  }

  /**
   * Returns the four-bit response code as a number.
   *
   * @return 0 for no error, 3 for a name that does not exist
   */
  public int responseCodeValue() {
    return flags & RESPONSE_CODE_MASK;
  }
}
