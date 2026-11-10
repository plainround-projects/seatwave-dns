package io.plainround.seatwave.dns.sampling;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import io.plainround.seatwave.dns.resolver.Upstream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sends the same question many times and counts which address each answer puts first, which is the
 * address a client that takes the first record would use. Stateless and thread-safe; the upstream
 * it is given is not, so give each thread its own.
 */
public final class Sampler {

  // Percentiles by nearest rank: the p50 and p99 of the lookup times.
  private static final double MEDIAN = 0.50;
  private static final double P99 = 0.99;

  private Sampler() {}

  /**
   * Looks a name up many times.
   *
   * @param upstream the server to ask, for example a {@code DnsClient} on 127.0.0.1
   * @param name the name
   * @param type A or AAAA
   * @param lookups how many lookups, at least 1
   * @return the counts and the latency
   */
  public static SampleResult sample(
      Upstream upstream, DomainName name, RecordType type, int lookups) {
    Objects.requireNonNull(upstream, "upstream");
    if (lookups < 1) {
      throw new IllegalArgumentException("at least one lookup is needed");
    }
    Map<InetAddress, Integer> counts = new LinkedHashMap<>();
    long[] nanos = new long[lookups];
    int failures = 0;
    Question question = Question.of(name, type);
    for (int lookupIndex = 0; lookupIndex < lookups; lookupIndex++) {
      long started = System.nanoTime();
      Result<Message, LookupProblem> response = upstream.ask(question);
      nanos[lookupIndex] = System.nanoTime() - started;
      Optional<InetAddress> first = firstAddress(response, type);
      if (first.isPresent()) {
        counts.merge(first.get(), 1, Integer::sum);
      } else {
        failures++;
      }
    }
    Arrays.sort(nanos);
    return new SampleResult(
        name,
        lookups,
        counts,
        failures,
        Duration.ofNanos(percentile(nanos, MEDIAN)),
        Duration.ofNanos(percentile(nanos, P99)),
        Duration.ofNanos(nanos[lookups - 1]));
  }

  private static Optional<InetAddress> firstAddress(
      Result<Message, LookupProblem> response, RecordType type) {
    if (!(response instanceof Result.Success<Message, LookupProblem>(Message message))) {
      return Optional.empty();
    }
    return message.answers().stream()
        .filter(record -> record.type() == type)
        .map(ResourceRecord::data)
        .map(data -> ((RecordData.Address) data).address())
        .findFirst();
  }

  private static long percentile(long[] sorted, double share) {
    int rank = (int) Math.ceil(share * sorted.length);
    return sorted[Math.max(rank, 1) - 1];
  }
}
