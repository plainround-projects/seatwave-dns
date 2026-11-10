package io.plainround.seatwave.dns.message;

import java.util.Arrays;
import java.util.Optional;

/** The response codes this lab sends, from RFC 1035 section 4.1.1, with dig's names in comments. */
public enum ResponseCode {
  /** NOERROR: the name exists; the answer may still be empty (no data of that type). */
  NO_ERROR(0),
  /** FORMERR: the query could not be read. */
  FORMAT_ERROR(1),
  /** SERVFAIL: the server could not answer. */
  SERVER_FAILURE(2),
  /** NXDOMAIN: the name does not exist in the zone. */
  NAME_ERROR(3),
  /** NOTIMP: the kind of query is not supported. */
  NOT_IMPLEMENTED(4),
  /** REFUSED: the server will not answer, here because the name is outside its zones. */
  REFUSED(5);

  private final int code;

  ResponseCode(int code) {
    this.code = code;
  }

  /**
   * Returns the code on the wire.
   *
   * @return 0 to 5
   */
  public int code() {
    return code;
  }

  /**
   * Finds a response code by its wire value.
   *
   * @param code the four-bit value
   * @return the response code, or empty for a code this lab never sends
   */
  public static Optional<ResponseCode> fromCode(int code) {
    return Arrays.stream(values()).filter(value -> value.code == code).findFirst();
  }
}
