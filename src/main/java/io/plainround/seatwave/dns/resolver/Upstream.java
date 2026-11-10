package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;

/**
 * Where a resolver sends a question it cannot answer from its cache. Two kinds exist: {@link
 * DnsClient}, which sends a real UDP packet, and {@link InMemoryUpstream}, which hands the same
 * bytes to the server's {@code Responder} directly, so a simulation of hours runs in a second.
 */
@FunctionalInterface
public interface Upstream {

  /**
   * Asks one question.
   *
   * @param question the question
   * @return the response, or why there is none
   */
  Result<Message, LookupProblem> ask(Question question);
}
