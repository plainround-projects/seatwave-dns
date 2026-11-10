package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import io.plainround.seatwave.dns.message.Question;
import io.plainround.seatwave.dns.message.RecordData;
import io.plainround.seatwave.dns.message.RecordType;
import io.plainround.seatwave.dns.message.ResourceRecord;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

/**
 * One zone: its origin, its records and the weights of its weighted names. A zone can only be made
 * by {@link #of}, which checks it, so an invalid zone cannot exist. Immutable and thread-safe.
 *
 * <p>The checks catch the misconfigurations of failure mode 3 before a server loads them: records
 * outside the zone, a missing or extra SOA, no NS at the apex, a CNAME at the apex or beside other
 * data, a CNAME that points at a name the zone does not have, a CNAME loop, TTLs that differ inside
 * one record set (RFC 2181 section 5.2), duplicate records, a name server without an address
 * (broken delegation) and weights for addresses the name does not have.
 */
public final class Zone {

  // A CNAME chain inside one zone longer than this is a loop or a mistake.
  private static final int MAX_CNAME_HOPS = 8;

  private final DomainName origin;
  private final List<ResourceRecord> records;
  private final Weights weights;

  private Zone(DomainName origin, List<ResourceRecord> records, Weights weights) {
    this.origin = origin;
    this.records = List.copyOf(records);
    this.weights = weights;
  }

  /**
   * Checks records and builds a zone.
   *
   * @param origin the apex of the zone
   * @param records the records
   * @param weights the weights of weighted names, or {@link Weights#NONE}
   * @return the zone, or one line per problem
   */
  public static Result<Zone, List<String>> of(
      DomainName origin, List<ResourceRecord> records, Weights weights) {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(weights, "weights");
    Zone candidate = new Zone(origin, records, weights);
    List<String> problems = candidate.problems();
    return problems.isEmpty() ? Result.success(candidate) : Result.error(problems);
  }

  /**
   * Returns the apex.
   *
   * @return for example {@code seatwave.example.}
   */
  public DomainName origin() {
    return origin;
  }

  /**
   * Returns every record.
   *
   * @return the records, in file order
   */
  public List<ResourceRecord> records() {
    return records;
  }

  /**
   * Returns the weights.
   *
   * @return the weights of weighted names
   */
  public Weights weights() {
    return weights;
  }

  /**
   * Returns the records of one name and type.
   *
   * @param owner the name
   * @param type the type
   * @return the records, in file order; empty if there are none
   */
  public List<ResourceRecord> recordsAt(DomainName owner, RecordType type) {
    return records.stream()
        .filter(record -> record.owner().equals(owner) && record.type() == type)
        .toList();
  }

  /**
   * Returns the addresses (A and AAAA) of one name.
   *
   * @param owner the name
   * @return the addresses, in file order
   */
  public List<InetAddress> addressesOf(DomainName owner) {
    return records.stream()
        .filter(record -> record.owner().equals(owner))
        .map(ResourceRecord::data)
        .flatMap(data ->
            data instanceof RecordData.Address(var address) ? Stream.of(address) : Stream.empty())
        .toList();
  }

  /**
   * Returns the zone's SOA record.
   *
   * @return the one SOA at the apex, which {@link #of} guarantees
   */
  public ResourceRecord soa() {
    return recordsAt(origin, RecordType.SOA).getFirst();
  }

  /**
   * Builds a changed copy of this zone and checks it like any other.
   *
   * @param newRecords the records of the copy
   * @param newWeights the weights of the copy
   * @return the copy, or one line per problem
   */
  public Result<Zone, List<String>> with(List<ResourceRecord> newRecords, Weights newWeights) {
    return of(origin, newRecords, newWeights);
  }

  /**
   * Answers one question from this zone. For a weighted name, the address the weights pick is moved
   * to the top, as CoreDNS's weighted policy does.
   *
   * @param question the question
   * @param random picks the weighted address; not shared between threads
   * @return the answer, a referral, no data, no such name, or out of zone
   */
  public Lookup lookup(Question question, RandomGenerator random) {
    Objects.requireNonNull(question, "question");
    Objects.requireNonNull(random, "random");
    DomainName name = question.name();
    if (!name.isAtOrBelow(origin)) {
      return new Lookup.OutOfZone();
    }
    Optional<Lookup> referral = referralFor(name);
    if (referral.isPresent()) {
      return referral.get();
    }
    List<ResourceRecord> atName =
        records.stream().filter(record -> record.owner().equals(name)).toList();
    if (atName.isEmpty()) {
      // A name with records below it exists even when it has none itself (RFC 8020).
      boolean hasChildren = records.stream().anyMatch(record -> record.owner().isAtOrBelow(name));
      return hasChildren ? new Lookup.NoData(soa()) : new Lookup.NameError(soa());
    }
    return answer(name, question, random, 0);
  }

  private Lookup answer(DomainName name, Question question, RandomGenerator random, int hops) {
    List<ResourceRecord> cname = recordsAt(name, RecordType.CNAME);
    Optional<RecordType> type = question.type();
    if (!cname.isEmpty() && type.orElse(RecordType.A) != RecordType.CNAME) {
      List<ResourceRecord> chain = new ArrayList<>(cname);
      DomainName target = ((RecordData.Host) cname.getFirst().data()).target();
      if (target.isAtOrBelow(origin) && hops < MAX_CNAME_HOPS) {
        if (answer(target, question, random, hops + 1) instanceof Lookup.Answer(var rest)) {
          chain.addAll(rest);
        }
      }
      return new Lookup.Answer(chain);
    }
    List<ResourceRecord> matching = type.map(found -> recordsAt(name, found)).orElse(List.of());
    if (matching.isEmpty()) {
      return new Lookup.NoData(soa());
    }
    return new Lookup.Answer(weighted(name, matching, random));
  }

  private Optional<Lookup> referralFor(DomainName name) {
    // The highest cut below the apex wins: walk from just under the apex down to the name.
    List<DomainName> chain = new ArrayList<>(name.selfAndAncestors());
    Collections.reverse(chain);
    for (DomainName candidate : chain) {
      if (!candidate.isAtOrBelow(origin) || candidate.equals(origin)) {
        continue;
      }
      List<ResourceRecord> nameServers = recordsAt(candidate, RecordType.NS);
      if (!nameServers.isEmpty()) {
        List<ResourceRecord> glue = new ArrayList<>();
        for (ResourceRecord nameServer : nameServers) {
          DomainName host = ((RecordData.Host) nameServer.data()).target();
          glue.addAll(recordsAt(host, RecordType.A));
          glue.addAll(recordsAt(host, RecordType.AAAA));
        }
        return Optional.of(new Lookup.Referral(nameServers, glue));
      }
    }
    return Optional.empty();
  }

  private List<ResourceRecord> weighted(
      DomainName name, List<ResourceRecord> answer, RandomGenerator random) {
    boolean isAddress =
        answer.getFirst().type() == RecordType.A || answer.getFirst().type() == RecordType.AAAA;
    if (!isAddress || answer.size() == 1 || !weights.byName().containsKey(name)) {
      return answer;
    }
    int total = 0;
    for (ResourceRecord record : answer) {
      total += weights.weightOf(name, ((RecordData.Address) record.data()).address());
    }
    int pick = random.nextInt(total);
    int chosen = 0;
    for (int index = 0; index < answer.size(); index++) {
      pick -= weights.weightOf(name, ((RecordData.Address) answer.get(index).data()).address());
      if (pick < 0) {
        chosen = index;
        break;
      }
    }
    // Only the first record is chosen; the rest keep their order, as in CoreDNS.
    List<ResourceRecord> ordered = new ArrayList<>(answer);
    ordered.addFirst(ordered.remove(chosen));
    return List.copyOf(ordered);
  }

  private List<String> problems() {
    List<String> problems = new ArrayList<>();
    for (ResourceRecord record : records) {
      if (!record.owner().isAtOrBelow(origin)) {
        problems.add(record.owner() + " is outside the zone " + origin);
      }
    }
    long soaCount = records.stream().filter(record -> record.type() == RecordType.SOA).count();
    if (soaCount != 1 || recordsAt(origin, RecordType.SOA).size() != 1) {
      problems.add("the zone needs exactly one SOA record, at its apex " + origin);
    }
    if (recordsAt(origin, RecordType.NS).isEmpty()) {
      problems.add("the zone needs at least one NS record at its apex " + origin);
    }
    checkAliases(problems);
    checkRecordSets(problems);
    checkNameServers(problems);
    checkWeights(problems);
    return problems;
  }

  private void checkAliases(List<String> problems) {
    for (ResourceRecord alias : records) {
      if (alias.type() != RecordType.CNAME) {
        continue;
      }
      DomainName owner = alias.owner();
      if (owner.equals(origin)) {
        problems.add(
            "a CNAME at the zone apex "
                + origin
                + " is not allowed, because the apex must also hold the SOA and NS records"
                + " (RFC 1034 section 3.6.2); use A and AAAA records or a provider's alias record");
      } else if (records.stream()
          .anyMatch(other -> other.owner().equals(owner) && other != alias)) {
        problems.add("a CNAME at " + owner + " must be the only record of that name");
      }
      DomainName target = ((RecordData.Host) alias.data()).target();
      if (target.isAtOrBelow(origin)
          && records.stream().noneMatch(record -> record.owner().equals(target))) {
        problems.add(
            "the CNAME " + owner + " points at " + target + ", which the zone does not have");
      }
      if (hasAliasLoop(owner)) {
        problems.add("the CNAME chain from " + owner + " loops");
      }
    }
  }

  private boolean hasAliasLoop(DomainName start) {
    Set<DomainName> seen = new HashSet<>();
    DomainName current = start;
    while (seen.add(current)) {
      List<ResourceRecord> alias = recordsAt(current, RecordType.CNAME);
      if (alias.isEmpty()) {
        return false;
      }
      current = ((RecordData.Host) alias.getFirst().data()).target();
    }
    return true;
  }

  private void checkRecordSets(List<String> problems) {
    Set<String> seen = new HashSet<>();
    Set<String> reported = new HashSet<>();
    for (ResourceRecord record : records) {
      String identity = record.owner() + " " + record.type() + " " + record.data();
      if (!seen.add(identity)) {
        problems.add("the record " + record + " appears twice");
      }
      String set = record.owner() + " " + record.type();
      boolean hasOtherTtl =
          recordsAt(record.owner(), record.type()).stream()
              .anyMatch(other -> other.ttlSeconds() != record.ttlSeconds());
      if (hasOtherTtl && reported.add(set)) {
        problems.add(
            "the records of "
                + set
                + " have different TTLs; one set has one TTL (RFC 2181 section 5.2)");
      }
    }
  }

  private void checkNameServers(List<String> problems) {
    for (ResourceRecord nameServer : records) {
      if (nameServer.type() != RecordType.NS) {
        continue;
      }
      DomainName host = ((RecordData.Host) nameServer.data()).target();
      // A name server inside this zone can only be found through an address held here (glue).
      if (host.isAtOrBelow(origin) && addressesOf(host).isEmpty()) {
        problems.add(
            "broken delegation: the name server "
                + host
                + " of "
                + nameServer.owner()
                + " has no A or AAAA record (glue) in the zone");
      }
    }
  }

  private void checkWeights(List<String> problems) {
    weights
        .byName()
        .forEach((name, weighted) -> {
          List<InetAddress> addresses = addressesOf(name);
          if (addresses.isEmpty()) {
            problems.add("weights are given for " + name + ", which has no A or AAAA records");
          }
          for (InetAddress address : weighted.keySet()) {
            if (!addresses.isEmpty() && !addresses.contains(address)) {
              problems.add(
                  "a weight is given for "
                      + address.getHostAddress()
                      + ", which "
                      + name
                      + " does not have");
            }
          }
        });
  }
}
