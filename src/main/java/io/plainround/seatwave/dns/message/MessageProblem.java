package io.plainround.seatwave.dns.message;

/** Why a packet is not a well-formed DNS message. Every variant is an expected failure of input. */
public sealed interface MessageProblem {

  /**
   * Says what is wrong, in one line.
   *
   * @return the reason
   */
  String describe();

  /**
   * The packet ended before a part that must be there.
   *
   * @param part the part that was cut off
   */
  record Truncated(String part) implements MessageProblem {
    @Override
    public String describe() {
      return "the packet ends inside the " + part;
    }
  }

  /**
   * A name on the wire breaks a rule: a label or the name too long, a compression pointer that does
   * not point backward, or a character outside the allow-list.
   *
   * @param reason the rule broken
   */
  record BadName(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad name: " + reason;
    }
  }

  /**
   * The header's counts cannot be right for a packet of this size, or for a query.
   *
   * @param reason the count that is wrong
   */
  record BadCounts(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad counts: " + reason;
    }
  }

  /**
   * A record's data does not have the length its type requires.
   *
   * @param reason the record and the length
   */
  record BadRecord(String reason) implements MessageProblem {
    @Override
    public String describe() {
      return "bad record: " + reason;
    }
  }

  /**
   * Bytes are left over after the last record the header announced.
   *
   * @param count how many bytes are left
   */
  record TrailingBytes(int count) implements MessageProblem {
    @Override
    public String describe() {
      return count + " bytes follow the last record";
    }
  }
}
