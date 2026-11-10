package io.plainround.seatwave.dns.resolver;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import io.plainround.seatwave.dns.message.ResponseCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolver with a cache, standing for the recursive resolver of an internet provider, or for a
 * client library that caches. It honors TTLs as its {@link CachePolicy} allows: a positive answer
 * is kept for the smallest TTL in it, raised or lowered by the policy; a negative answer for the
 * shorter of the SOA's TTL and its minimum field (RFC 2308 section 5); and when the server cannot
 * be reached, an expired answer may be served for a while (RFC 8767).
 *
 * <p>Time comes from an injected {@link Clock}, so a test or a simulation decides what "now" is.
 * Not thread-safe: one resolver belongs to one thread.
 */
public final class CachingResolver {

  private final Upstream upstream;
  private final Clock clock;
  private final CachePolicy policy;
  private final Map<Key, Entry> cache = new HashMap<>();
  private long hits;
  private long misses;

  /**
   * Creates a resolver with an empty cache.
   *
   * @param upstream where questions go on a cache miss
   * @param clock the clock
   * @param policy how long answers are kept
   */
  public CachingResolver(Upstream upstream, Clock clock, CachePolicy policy) {
    this.upstream = Objects.requireNonNull(upstream, "upstream");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * Resolves a name, from the cache when it holds a valid copy.
   *
   * @param name the name
   * @param type the record type
   * @return the answer and where it came from
   */
  public Resolution resolve(DomainName name, RecordType type) {
    Instant now = clock.instant();
    Key key = new Key(Objects.requireNonNull(name, "name"), Objects.requireNonNull(type, "type"));
    Optional<Entry> cached = Optional.ofNullable(cache.get(key));
    if (cached.isPresent() && now.isBefore(cached.get().expiresAt())) {
      hits++;
      return cached.get().as(Resolution.Source.CACHE);
    }
    misses++;
    return switch (upstream.ask(Question.of(name, type))) {
      case Result.Success<Message, LookupProblem>(Message response) -> store(key, response, now);
      case Result.Error<Message, LookupProblem> failed -> {
        boolean canServeStale =
            cached.isPresent()
                && now.isBefore(cached.get().expiresAt().plus(policy.serveStaleFor()));
        yield canServeStale
            ? cached.get().as(Resolution.Source.STALE)
            : new Resolution(Resolution.Source.FAILED, ResponseCode.SERVER_FAILURE, List.of());
      }
    };
  }

  /**
   * Returns the share of lookups answered from the cache.
   *
   * @return hits divided by all lookups, or 0 before the first lookup
   */
  public double cacheHitRatio() {
    long lookups = hits + misses;
    return lookups == 0 ? 0 : (double) hits / lookups;
  }

  /**
   * Returns how many questions went to the server.
   *
   * @return the misses since creation
   */
  public long serverQueries() {
    return misses;
  }

  private Resolution store(Key key, Message response, Instant now) {
    Optional<ResponseCode> code = ResponseCode.fromCode(response.responseCodeValue());
    boolean isUsable =
        code.isPresent()
            && (code.get() == ResponseCode.NO_ERROR || code.get() == ResponseCode.NAME_ERROR);
    if (!isUsable) {
      // REFUSED, SERVFAIL and the rest are not cached here (RFC 9520 allows a brief hold).
      return new Resolution(
          Resolution.Source.SERVER, code.orElse(ResponseCode.SERVER_FAILURE), List.of());
    }
    List<ResourceRecord> answers = response.answers();
    boolean hasData = answers.stream().anyMatch(record -> record.type() == key.type());
    Duration hold;
    if (hasData) {
      long smallestTtl = answers.stream().mapToLong(ResourceRecord::ttlSeconds).min().orElse(0);
      hold = policy.holdFor(Duration.ofSeconds(smallestTtl));
    } else {
      long negativeTtl =
          response.authority().stream()
              .filter(record -> record.type() == RecordType.SOA)
              .mapToLong(soa ->
                  Math.min(soa.ttlSeconds(), ((RecordData.Authority) soa.data()).minimumSeconds()))
              .findFirst()
              .orElse(0);
      Duration negative = Duration.ofSeconds(negativeTtl);
      hold =
          negative.compareTo(policy.maximumNegativeHold()) > 0
              ? policy.maximumNegativeHold()
              : negative;
    }
    Entry entry = new Entry(code.get(), hasData ? answers : List.of(), now.plus(hold));
    cache.put(key, entry);
    return entry.as(Resolution.Source.SERVER);
  }

  private record Key(DomainName name, RecordType type) {}

  private record Entry(ResponseCode code, List<ResourceRecord> records, Instant expiresAt) {

    Resolution as(Resolution.Source source) {
      return new Resolution(source, code, records);
    }
  }
}
