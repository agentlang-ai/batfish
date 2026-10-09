package org.batfish.engine;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.batfish.engine.SnapshotRequest.Device;

/**
 * Derives a proposed snapshot from a base one by applying config lines, the same lines a change
 * request would push to the device.
 *
 * <p>Lines are added the way a device's config session would take them: after the existing config,
 * before a trailing {@code end}. The changed devices are reparsed into a new snapshot; the rest are
 * unchanged. Anything Batfish could not model in the proposed config that it could model in the
 * base becomes a reason the change's verdict must be {@code unknown}.
 */
public final class Deriver {

  /** Config lines for one device, in the device's own CLI syntax. */
  public record Change(@Nonnull String device, @Nonnull List<String> lines) {
    public Change {
      lines = ImmutableList.copyOf(lines);
    }
  }

  /**
   * The derived snapshot, and the holes the change introduced. A non-empty {@code introduced} means
   * the engine cannot vouch for the change's effect.
   */
  public record Derived(
      @Nonnull SnapshotBuilder.Built snapshot, @Nonnull List<CoverageHole> introduced) {}

  private final SnapshotBuilder _builder;

  public Deriver(@Nonnull SnapshotBuilder builder) {
    _builder = builder;
  }

  public @Nonnull Derived derive(
      @Nonnull SnapshotBuilder.Built base, @Nonnull List<Change> changes) {
    Map<String, List<String>> linesByDevice =
        changes.stream()
            .collect(
                Collectors.groupingBy(
                    c -> c.device().toLowerCase(Locale.ROOT),
                    Collectors.flatMapping(c -> c.lines().stream(), Collectors.toList())));
    Set<String> known =
        base.request().devices().stream()
            .map(d -> d.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    for (String device : linesByDevice.keySet()) {
      if (!known.contains(device)) {
        throw new IllegalArgumentException("change for unknown device " + device);
      }
    }

    List<Device> devices = new ArrayList<>();
    for (Device d : base.request().devices()) {
      List<String> lines = linesByDevice.get(d.name().toLowerCase(Locale.ROOT));
      devices.add(
          lines == null
              ? d
              : new Device(
                  d.name(), d.dialect(), apply(d.configText(), lines), d.linuxInterfaces()));
    }
    SnapshotRequest proposed =
        new SnapshotRequest(devices, base.request().layer1(), base.request().environment());
    SnapshotBuilder.Built built = _builder.build(proposed);
    List<CoverageHole> introduced =
        new ArrayList<>(introduced(base.holes(), built.holes(), linesByDevice.keySet()));
    linesByDevice.forEach(
        (device, lines) -> introduced.addAll(redeclaredClauses(device, base, lines)));
    return new Derived(built, ImmutableList.copyOf(introduced));
  }

  private static final Pattern ROUTE_MAP_CLAUSE =
      Pattern.compile("^\\s*route-map\\s+(\\S+)\\s+(permit|deny)\\s+(\\d+)\\s*$");

  /**
   * Re-declaring an existing route-map clause with the other action is silent in Batfish, which
   * keeps the original, while devices replace the clause (FRR) or change its action in place. No
   * warning reveals it, so the deriver flags it: the change's effect is unknown.
   */
  private static List<CoverageHole> redeclaredClauses(
      String device, SnapshotBuilder.Built base, List<String> lines) {
    DeviceFacts facts = base.facts().get(device);
    if (facts == null) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<CoverageHole> holes = ImmutableList.builder();
    for (String line : lines) {
      Matcher m = ROUTE_MAP_CLAUSE.matcher(line);
      if (!m.matches()) {
        continue;
      }
      String clause = m.group(1) + " " + m.group(3);
      for (DeviceFacts.PolicyFacts policy : facts.policies()) {
        for (DeviceFacts.ClauseFacts c : policy.clauses()) {
          if (!c.name().equals(clause) || c.lines().isEmpty()) {
            continue;
          }
          Matcher header = ROUTE_MAP_CLAUSE.matcher(c.lines().get(0));
          if (header.matches() && !header.group(2).equals(m.group(2))) {
            holes.add(
                new CoverageHole(
                    device,
                    CoverageHole.POLICY,
                    "redeclared-clause",
                    null,
                    line.strip()
                        + " changes the action of an existing clause; remove the clause"
                        + " first"));
          }
        }
      }
    }
    return holes.build();
  }

  /** Inserts {@code lines} after the config and before a trailing {@code end}, if any. */
  static @Nonnull String apply(@Nonnull String config, @Nonnull List<String> lines) {
    String body = config.endsWith("\n") ? config : config + "\n";
    String tail = "";
    int lastLineStart = body.lastIndexOf('\n', body.length() - 2) + 1;
    if (body.substring(lastLineStart).strip().equals("end")) {
      tail = body.substring(lastLineStart);
      body = body.substring(0, lastLineStart);
    }
    StringBuilder sb = new StringBuilder(body);
    for (String line : lines) {
      sb.append(line).append('\n');
    }
    return sb.append(tail).toString();
  }

  /** Holes on changed devices that the base did not have, ignoring line numbers. */
  private static List<CoverageHole> introduced(
      List<CoverageHole> base, List<CoverageHole> proposed, Set<String> changed) {
    Function<CoverageHole, String> key =
        h -> h.node() + "|" + h.family() + "|" + h.kind() + "|" + h.text();
    Set<String> before = new HashSet<>();
    base.forEach(h -> before.add(key.apply(h)));
    return proposed.stream()
        .filter(h -> changed.contains(h.node()))
        .filter(h -> !before.contains(key.apply(h)))
        .collect(ImmutableList.toImmutableList());
  }
}
