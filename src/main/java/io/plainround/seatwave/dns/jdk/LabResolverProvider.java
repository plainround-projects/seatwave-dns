package io.plainround.seatwave.dns.jdk;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.time.Duration;
import java.util.Optional;

/**
 * Plugs the lab's DNS server into the JDK's own name lookup ({@code InetAddress}), through the
 * resolver service-provider interface of JDK 18 and later (JEP 418). The JDK keeps its own cache on
 * top of any resolver, so a program that calls {@code InetAddress.getByName("seatwave.example")}
 * shows exactly what the JVM's cache does with a DNS TTL.
 *
 * <p>The provider is off unless a program sets {@link #PORT_PROPERTY} before its first lookup; then
 * it answers names under {@code seatwave.example} from the lab server on 127.0.0.1 and refuses
 * every other name, so no lookup ever leaves the machine. When the property is not set, the JDK's
 * built-in resolver is used, which keeps tests and the other programs unaffected.
 */
public final class LabResolverProvider extends InetAddressResolverProvider {

  /** The system property that holds the lab server's UDP port. */
  public static final String PORT_PROPERTY = "seatwave.lab.dns.port";

  /** The system property that holds the timeout of each query, in milliseconds. */
  public static final String TIMEOUT_PROPERTY = "seatwave.lab.dns.timeout.millis";

  /** Creates the provider; the JDK calls this through the service loader. */
  public LabResolverProvider() {
    super();
  }

  @Override
  public InetAddressResolver get(Configuration configuration) {
    Optional<String> port = Optional.ofNullable(System.getProperty(PORT_PROPERTY));
    Optional<String> timeout = Optional.ofNullable(System.getProperty(TIMEOUT_PROPERTY));
    if (port.isEmpty() || timeout.isEmpty()) {
      return configuration.builtinResolver();
    }
    return new LabResolver(
        Integer.parseInt(port.get()), Duration.ofMillis(Long.parseLong(timeout.get())));
  }

  @Override
  public String name() {
    return "seatwave-lab";
  }
}
