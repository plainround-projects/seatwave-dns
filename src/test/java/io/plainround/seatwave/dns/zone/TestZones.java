package io.plainround.seatwave.dns.zone;

import java.nio.file.Path;

/** Loads the project's own zone files, so tests and the lab read the same records. */
public final class TestZones {

  private TestZones() {}

  /**
   * Loads seatwave.example with its 50 to 50 weights.
   *
   * @return the zone
   */
  public static Zone seatwave() {
    return ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/seatwave.example.zone"),
            "zones/seatwave.example.weights")
        .discardError()
        .orElseThrow();
  }

  /**
   * Loads seatwave.example with weights of 90 and 10.
   *
   * @return the zone
   */
  public static Zone seatwaveNinetyTen() {
    return ZoneLoader.load(
            "seatwave.example.",
            Path.of("zones/seatwave.example.zone"),
            "zones/seatwave.example.weights-90-10")
        .discardError()
        .orElseThrow();
  }

  /**
   * Loads the lab's root zone.
   *
   * @return the zone
   */
  public static Zone root() {
    return ZoneLoader.load(".", Path.of("zones/root.zone"), ZoneLoader.NO_WEIGHTS)
        .discardError()
        .orElseThrow();
  }

  /**
   * Loads the lab's example. zone.
   *
   * @return the zone
   */
  public static Zone example() {
    return ZoneLoader.load("example.", Path.of("zones/example.zone"), ZoneLoader.NO_WEIGHTS)
        .discardError()
        .orElseThrow();
  }
}
