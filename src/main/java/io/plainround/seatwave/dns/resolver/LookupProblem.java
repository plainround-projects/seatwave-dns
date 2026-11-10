package io.plainround.seatwave.dns.resolver;

import io.plainround.seatwave.dns.message.MessageProblem;
import java.time.Duration;
import java.util.Objects;

/** Why a question got no usable response. Every variant is an expected failure of the network. */
public sealed interface LookupProblem {

  /**
   * Says why, in one line.
   *
   * @return the reason
   */
  String describe();

  /**
   * No response arrived in time.
   *
   * @param waited how long the client waited
   */
  record TimedOut(Duration waited) implements LookupProblem {

    /** Checks the field. */
    public TimedOut {
      Objects.requireNonNull(waited, "waited");
    }

    @Override
    public String describe() {
      return "no response within " + waited.toMillis() + " ms";
    }
  }

  /**
   * The server's port is closed: the operating system answered "port unreachable".
   *
   * @param detail the operating system's message
   */
  record Unreachable(String detail) implements LookupProblem {

    /** Checks the field. */
    public Unreachable {
      Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String describe() {
      return "the server is unreachable: " + detail;
    }
  }

  /**
   * A response arrived but could not be read.
   *
   * @param problem what was wrong with it
   */
  record Malformed(MessageProblem problem) implements LookupProblem {

    /** Checks the field. */
    public Malformed {
      Objects.requireNonNull(problem, "problem");
    }

    @Override
    public String describe() {
      return "unreadable response: " + problem.describe();
    }
  }
}
