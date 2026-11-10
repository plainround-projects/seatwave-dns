package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.MessageCodec;
import io.plainround.seatwave.dns.message.MessageProblem;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.migration.ZoneStore;
import io.plainround.seatwave.dns.server.Responder;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * An upstream that encodes the question, hands the bytes to the server's {@link Responder} and
 * decodes the reply, without a socket. The bytes are exactly those a UDP server would send, so a
 * simulation exercises the same code as the real server, only faster. Not thread-safe.
 */
public final class InMemoryUpstream implements Upstream {

  // Identifiers matter only on a shared network; here a counter keeps runs repeatable.
  private static final int ID_SPACE = 1 << 16;

  private final ZoneStore store;
  private final RandomGenerator random;
  private int nextId;
  private long queries;

  /**
   * Creates the upstream.
   *
   * @param store the zone the stand-in server answers from
   * @param random picks weighted answers
   */
  public InMemoryUpstream(ZoneStore store, RandomGenerator random) {
    this.store = Objects.requireNonNull(store, "store");
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public Result<Message, LookupProblem> ask(Question question) {
    queries++;
    nextId = (nextId + 1) % ID_SPACE;
    byte[] query = MessageCodec.encode(Message.query(nextId, question));
    Optional<byte[]> response = Responder.respond(query, store.current(), random);
    if (response.isEmpty()) {
      return Result.error(new LookupProblem.Unreachable("the server dropped the query"));
    }
    return switch (MessageCodec.decode(response.get())) {
      case Result.Success<Message, MessageProblem>(Message message) -> Result.success(message);
      case Result.Error<Message, MessageProblem>(MessageProblem problem) ->
          Result.error(new LookupProblem.Malformed(problem));
    };
  }

  /**
   * Returns how many questions reached the stand-in server.
   *
   * @return the count since creation
   */
  public long queries() {
    return queries;
  }
}
