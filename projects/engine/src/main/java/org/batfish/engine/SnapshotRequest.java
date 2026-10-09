package org.batfish.engine;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Everything the engine needs to build one model of a tenant's network: each device's config text,
 * the physical links between them, and the observed environment Batfish cannot compute.
 */
public record SnapshotRequest(
    @Nonnull List<Device> devices, @Nonnull List<Link> layer1, @Nonnull Environment environment) {

  /** Optional parts may be omitted, as in the HTTP contract. */
  public SnapshotRequest {
    devices = ImmutableList.copyOf(devices);
    layer1 = layer1 == null ? ImmutableList.of() : ImmutableList.copyOf(layer1);
    environment = environment == null ? Environment.NONE : environment;
  }

  /**
   * One device. {@code name} is the tenant-unique lowered name, and must match the hostname in the
   * config text (case-insensitively). {@code linuxInterfaces} is only used by {@link
   * Dialect#FRR_LINUX}, whose addresses live in the kernel rather than the FRR config.
   */
  public record Device(
      @Nonnull String name,
      @Nonnull Dialect dialect,
      @Nonnull String configText,
      @Nullable List<LinuxInterface> linuxInterfaces) {
    public Device(String name, Dialect dialect, String configText) {
      this(name, dialect, configText, null);
    }
  }

  /** A kernel interface on an FRR host: its name, CIDR addresses, and whether it is up. */
  public record LinuxInterface(@Nonnull String name, @Nonnull List<String> addresses, boolean up) {
    public LinuxInterface {
      addresses = addresses == null ? ImmutableList.of() : ImmutableList.copyOf(addresses);
    }
  }

  /** A physical link between two interfaces, by lowered node name and interface name. */
  public record Link(
      @Nonnull String node1,
      @Nonnull String interface1,
      @Nonnull String node2,
      @Nonnull String interface2) {}

  /** An interface by lowered node name and interface name. */
  public record NodeInterface(@Nonnull String node, @Nonnull String iface) {}

  /** Observed state Batfish cannot compute: interfaces whose line is down, and nodes down. */
  public record Environment(
      @Nonnull Set<NodeInterface> interfacesDown, @Nonnull Set<String> nodesDown) {
    public static final Environment NONE = new Environment(ImmutableSet.of(), ImmutableSet.of());

    public Environment {
      interfacesDown =
          interfacesDown == null ? ImmutableSet.of() : ImmutableSet.copyOf(interfacesDown);
      nodesDown = nodesDown == null ? ImmutableSet.of() : ImmutableSet.copyOf(nodesDown);
    }
  }
}
