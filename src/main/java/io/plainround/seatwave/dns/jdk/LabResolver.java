package io.plainround.seatwave.dns.jdk;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Message;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.resolver.DnsClient;
import io.plainround.seatwave.dns.resolver.LookupProblem;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The JDK resolver that asks the lab server. It answers only names under {@code seatwave.example};
 * any other name is refused with {@link UnknownHostException}, never passed to the system's
 * resolver. Thread-safe: the JDK may call it from any thread, so each lookup opens its own
 * short-lived client.
 */
final class LabResolver implements InetAddressResolver {

  private static final DomainName LAB_ZONE = new DomainName(List.of("seatwave", "example"));

  private final int port;
  private final Duration timeout;

  LabResolver(int port, Duration timeout) {
    this.port = port;
    this.timeout = timeout;
  }

  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    DomainName name =
        switch (DomainName.parse(host)) {
          case Result.Success<DomainName, String>(DomainName parsed) -> parsed;
          case Result.Error<DomainName, String>(String problem) ->
              throw new UnknownHostException(problem);
        };
    if (!name.isAtOrBelow(LAB_ZONE)) {
      throw new UnknownHostException("the lab resolver answers only names under " + LAB_ZONE);
    }
    int characteristics = lookupPolicy.characteristics();
    List<RecordType> order = new ArrayList<>();
    if ((characteristics & LookupPolicy.IPV4) != 0) {
      order.add(RecordType.A);
    }
    if ((characteristics & LookupPolicy.IPV6) != 0) {
      order.add(
          (characteristics & LookupPolicy.IPV6_FIRST) != 0 ? 0 : order.size(), RecordType.AAAA);
    }
    List<InetAddress> addresses = new ArrayList<>();
    try (DnsClient client = new DnsClient(port, timeout)) {
      for (RecordType type : order) {
        addresses.addAll(addressesOf(client.ask(Question.of(name, type)), type, host));
      }
    }
    if (addresses.isEmpty()) {
      throw new UnknownHostException(host + ": no address in the lab zone");
    }
    return addresses.stream();
  }

  @Override
  public String lookupByAddress(byte[] address) throws UnknownHostException {
    throw new UnknownHostException("the lab resolver does not answer reverse lookups");
  }

  private static List<InetAddress> addressesOf(
      Result<Message, LookupProblem> response, RecordType type, String host)
      throws UnknownHostException {
    return switch (response) {
      case Result.Success<Message, LookupProblem>(Message message) ->
          message.answers().stream()
              .filter(record -> record.type() == type)
              .map(record -> ((RecordData.Address) record.data()).address())
              .toList();
      case Result.Error<Message, LookupProblem>(LookupProblem problem) ->
          throw new UnknownHostException(host + ": " + problem.describe());
    };
  }
}
