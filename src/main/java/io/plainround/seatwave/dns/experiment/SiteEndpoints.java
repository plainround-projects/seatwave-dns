package io.plainround.seatwave.dns.experiment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stand-ins for the two SeatWave sites: one TCP listener on the loopback address per site address,
 * so a health check is a real TCP connection with a real timeout. The site addresses themselves are
 * documentation addresses that nothing answers, which is why each is mapped to a local port.
 * Thread-safe.
 */
public final class SiteEndpoints implements AutoCloseable {

  private final Map<InetAddress, ServerSocket> listeners;

  private SiteEndpoints(Map<InetAddress, ServerSocket> listeners) {
    this.listeners = Map.copyOf(listeners);
  }

  /**
   * Opens one listener per site and accepts connections on a virtual thread each.
   *
   * @param portBySite the loopback port that stands for each site address
   * @return the running stand-ins
   * @throws IOException if a port is taken
   */
  public static SiteEndpoints start(Map<InetAddress, Integer> portBySite) throws IOException {
    Map<InetAddress, ServerSocket> listeners = new LinkedHashMap<>();
    for (Map.Entry<InetAddress, Integer> site : portBySite.entrySet()) {
      ServerSocket listener = new ServerSocket();
      listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), site.getValue()));
      listeners.put(site.getKey(), listener);
      Thread.ofVirtual().name("site-" + site.getValue()).start(() -> accept(listener));
    }
    return new SiteEndpoints(listeners);
  }

  /**
   * Checks one site: a TCP connection to its stand-in, within a timeout.
   *
   * @param site the site address
   * @param timeout the longest wait for the connection
   * @return true if the connection opened in time
   */
  public boolean isHealthy(InetAddress site, Duration timeout) {
    ServerSocket listener = Objects.requireNonNull(listeners.get(site), "unknown site");
    try (Socket probe = new Socket()) {
      probe.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()),
          Math.toIntExact(timeout.toMillis()));
      return true;
    } catch (IOException refusedOrTimedOut) {
      // A refused or slow connection is exactly what a failed health check is.
      return false;
    }
  }

  /** Closes every listener. */
  @Override
  public void close() {
    listeners.values().forEach(SiteEndpoints::closeQuietly);
  }

  private static void accept(ServerSocket listener) {
    while (!listener.isClosed()) {
      try (Socket connection = listener.accept()) {
        // The check needs only the connection; closing it at once is the whole answer.
        connection.shutdownOutput();
      } catch (SocketException closed) {
        return;
      } catch (IOException failed) {
        throw new UncheckedIOException("a site stand-in failed", failed);
      }
    }
  }

  private static void closeQuietly(ServerSocket listener) {
    try {
      listener.close();
    } catch (IOException failed) {
      throw new UncheckedIOException("cannot close a site stand-in", failed);
    }
  }
}
