package io.plainround.seatwave.dns.migration;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;

/** Why the migration guard refused a step. Every variant is an expected outcome, not a bug. */
public sealed interface MigrationRefusal {

  /**
   * Says why, in one line.
   *
   * @return the reason
   */
  String describe();

  /**
   * A site did not answer its health checks for the whole window the step needs.
   *
   * @param site the address of the site
   * @param reason the failed or missing check
   */
  record UnhealthySite(InetAddress site, String reason) implements MigrationRefusal {

    /** Checks the fields. */
    public UnhealthySite {
      Objects.requireNonNull(site, "site");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason;
    }
  }

  /**
   * The step is allowed, but not yet.
   *
   * @param earliest the first moment it will be allowed
   * @param reason why it must wait
   */
  record TooEarly(Instant earliest, String reason) implements MigrationRefusal {

    /** Checks the fields. */
    public TooEarly {
      Objects.requireNonNull(earliest, "earliest");
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason + "; allowed from " + earliest;
    }
  }

  /**
   * The step does not make sense for the zone as it is now.
   *
   * @param reason what does not fit
   */
  record BadRequest(String reason) implements MigrationRefusal {

    /** Checks the field. */
    public BadRequest {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String describe() {
      return "refused: " + reason;
    }
  }
}
