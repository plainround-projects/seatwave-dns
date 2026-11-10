package io.plainround.seatwave.dns.zone;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.dns.message.DomainName;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Weights for weighted answers, read from a file in the format of CoreDNS's {@code loadbalance
 * weighted} policy: a line with a name, then one line per address with its weight from 1 to 255. An
 * address with no weight counts as weight 1, as in CoreDNS.
 *
 * @param byName the weight of each address, per name, in file order
 */
public record Weights(Map<DomainName, Map<InetAddress, Integer>> byName) {

  /** No weights at all: every address of a name is equally likely to be first. */
  public static final Weights NONE = new Weights(Map.of());

  /** The smallest and largest weights CoreDNS accepts (its loadbalance documentation). */
  public static final int MIN_WEIGHT = 1;

  /** The largest weight. */
  public static final int MAX_WEIGHT = 255;

  /**
   * Copies and checks the weights.
   *
   * @throws IllegalArgumentException if a weight is outside 1 to 255
   */
  public Weights {
    Map<DomainName, Map<InetAddress, Integer>> copy = new LinkedHashMap<>();
    byName.forEach((name, weights) -> {
      weights
          .values()
          .forEach(weight -> {
            if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
              throw new IllegalArgumentException("a weight must be from 1 to 255");
            }
          });
      copy.put(name, Map.copyOf(new LinkedHashMap<>(weights)));
    });
    byName = Map.copyOf(copy);
  }

  /**
   * Reads a weights file.
   *
   * @param text the file's content
   * @return the weights, or one line per problem with its line number
   */
  public static Result<Weights, List<String>> parse(String text) {
    Objects.requireNonNull(text, "text");
    Map<DomainName, Map<InetAddress, Integer>> byName = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    Optional<DomainName> current = Optional.empty();
    List<String> lines = text.lines().toList();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index).strip();
      String where = "weights line " + (index + 1) + ": ";
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] fields = line.split("\\s+");
      if (fields.length == 1) {
        switch (DomainName.parse(fields[0])) {
          case Result.Success<DomainName, String>(DomainName name) -> {
            current = Optional.of(name);
            byName.putIfAbsent(name, new LinkedHashMap<>());
          }
          case Result.Error<DomainName, String>(String problem) -> problems.add(where + problem);
        }
      } else if (fields.length == 2 && current.isPresent()) {
        addWeight(fields, where, byName.get(current.get()), problems);
      } else {
        problems.add(where + "expected a name, or an address and a weight after a name");
      }
    }
    return problems.isEmpty() ? Result.success(new Weights(byName)) : Result.error(problems);
  }

  /**
   * Returns the weight of one address of a name.
   *
   * @param name the name
   * @param address the address
   * @return its weight, or 1 if the file gives none
   */
  public int weightOf(DomainName name, InetAddress address) {
    return byName.getOrDefault(name, Map.of()).getOrDefault(address, MIN_WEIGHT);
  }

  /**
   * Returns these weights with one address of one name replaced by another, so a migration keeps
   * the weight a site had. Other names keep their weights unchanged.
   *
   * @param name the name that moves
   * @param oldAddress the address leaving
   * @param newAddress the address taking its place
   * @return the changed copy
   */
  public Weights withAddressReplaced(
      DomainName name, InetAddress oldAddress, InetAddress newAddress) {
    Map<DomainName, Map<InetAddress, Integer>> changed = new LinkedHashMap<>(byName);
    if (byName.getOrDefault(name, Map.of()).containsKey(oldAddress)) {
      Map<InetAddress, Integer> copy = new LinkedHashMap<>(byName.get(name));
      copy.put(newAddress, copy.remove(oldAddress));
      changed.put(name, copy);
    }
    return new Weights(changed);
  }

  private static void addWeight(
      String[] fields, String where, Map<InetAddress, Integer> weights, List<String> problems) {
    try {
      // ofLiteral reads only an address written as digits; it never looks a name up.
      InetAddress address = InetAddress.ofLiteral(fields[0]);
      int weight = Integer.parseInt(fields[1]);
      if (weight < MIN_WEIGHT || weight > MAX_WEIGHT) {
        problems.add(
            String.format(
                Locale.ROOT, "%sa weight must be from 1 to 255, but was %d", where, weight));
        return;
      }
      weights.put(address, weight);
    } catch (IllegalArgumentException notAnAddressOrNumber) {
      // NumberFormatException is an IllegalArgumentException, so one handler covers both fields.
      problems.add(where + "not an address and a whole-number weight: " + String.join(" ", fields));
    }
  }
}
