package org.batfish.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.Bgpv4Route;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.bgp.community.StandardCommunity;
import org.batfish.engine.SnapshotRequest.Device;
import org.batfish.engine.SnapshotRequest.Environment;
import org.batfish.engine.SnapshotRequest.NodeInterface;
import org.batfish.main.Batfish;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Building snapshots from config text: semantics, environment, and coverage. */
public final class SnapshotBuilderTest {

  @Rule public TemporaryFolder _folder = new TemporaryFolder();

  private EngineBatfish _engine;

  @Before
  public void setUp() throws Exception {
    _engine = new EngineBatfish(_folder.newFolder().toPath(), 4);
  }

  private SnapshotBuilder.Built build(SnapshotRequest request) {
    return new SnapshotBuilder(_engine).build(request);
  }

  private DataPlane dataPlane(SnapshotBuilder.Built built) {
    Batfish bf = _engine.batfish(built.id(), null);
    bf.computeDataPlane(EngineBatfish.snapshot(built.id()));
    return bf.loadDataPlane(EngineBatfish.snapshot(built.id()));
  }

  private static Set<AbstractRoute> routes(DataPlane dp, String node, String prefix) {
    return dp.getRibs().get(node, Configuration.DEFAULT_VRF_NAME).getRoutes(Prefix.parse(prefix));
  }

  @Test
  public void frrRouteMapSetsCommunityOnExport() {
    DataPlane dp = dataPlane(build(Fixtures.frrOspfBgp()));

    Set<Bgpv4Route> received =
        dp.getBgpRoutes().get("r2", Configuration.DEFAULT_VRF_NAME).stream()
            .filter(r -> r.getNetwork().equals(Prefix.parse("10.1.0.0/24")))
            .collect(Collectors.toSet());

    assertThat(received, hasSize(1));
    assertThat(
        received.iterator().next().getCommunities().getCommunities(),
        contains(StandardCommunity.of(65001, 100)));
  }

  @Test
  public void interfaceDownRemovesItsAdjacencies() {
    SnapshotRequest down =
        Fixtures.frrOspfBgp(
            new Environment(ImmutableSet.of(new NodeInterface("r2", "eth1")), ImmutableSet.of()));

    DataPlane dp = dataPlane(build(down));

    assertThat(routes(dp, "r1", "2.2.2.2/32"), empty());
    assertThat(routes(dp, "r1", "10.2.0.0/24"), empty());
  }

  @Test
  public void nodeDownRemovesItsRoutes() {
    SnapshotRequest down =
        Fixtures.frrOspfBgp(new Environment(ImmutableSet.of(), ImmutableSet.of("r2")));

    DataPlane dp = dataPlane(build(down));

    assertThat(routes(dp, "r1", "10.2.0.0/24"), empty());
  }

  @Test
  public void frrDefaultEbgpPolicyRequirementIsAHole() {
    String requiresPolicy =
        Fixtures.text("frr-ospf-bgp/r2.frr").replace(" no bgp ebgp-requires-policy\n", "");
    SnapshotRequest request =
        new SnapshotRequest(
            ImmutableList.of(
                new Device("r2", Dialect.FRR_LINUX, requiresPolicy, Fixtures.r2Interfaces())),
            ImmutableList.of(),
            Environment.NONE);

    List<CoverageHole> holes = build(request).holes();

    assertThat(holes, hasSize(1));
    assertThat(holes.get(0).family(), equalTo(CoverageHole.BGP));
    assertThat(holes.get(0).kind(), equalTo("dialect-gap"));
  }

  @Test
  public void unmodeledBgpKnobIsABgpHole() {
    List<CoverageHole> holes = build(Fixtures.eosPolicy()).holes();

    assertThat(
        holes.stream().map(h -> h.family() + "/" + h.kind()).toList(),
        contains("bgp/unimplemented"));
  }

  @Test
  public void managementLinesAreNotForwardingHoles() {
    List<CoverageHole> holes = build(Fixtures.dc1Eos()).holes();

    assertThat(holes, not(empty()));
    holes.forEach(h -> assertThat(h.family(), equalTo(CoverageHole.MANAGEMENT)));
  }

  @Test
  public void hostnameMustMatchLoweredName() {
    SnapshotRequest request =
        new SnapshotRequest(
            ImmutableList.of(
                new Device("not-core1", Dialect.ARISTA_EOS, Fixtures.text("dc1-eos/core1.cfg"))),
            ImmutableList.of(),
            Environment.NONE);

    List<CoverageHole> holes = build(request).holes();

    assertThat(holes.stream().map(CoverageHole::kind).toList(), hasItem(equalTo("unparsed")));
  }

  @Test
  public void policyViewSplitsClausesAndReferences() {
    DeviceFacts r1 = build(Fixtures.frrOspfBgp()).facts().get("r1");

    DeviceFacts.PolicyFacts exportSite = r1.policies().get(0);
    assertThat(exportSite.name(), equalTo("EXPORT-SITE"));
    assertThat(exportSite.compiled(), equalTo(true));
    assertThat(
        exportSite.clauses().stream().map(DeviceFacts.ClauseFacts::name).toList(),
        contains("EXPORT-SITE 10", "EXPORT-SITE 20"));
    assertThat(
        exportSite.clauses().get(0).references(),
        contains(new DeviceFacts.SetRef("ip prefix-list", "SITE-A")));
    assertThat(exportSite.clauses().get(0).lines().get(0), startsWith("route-map EXPORT-SITE"));
  }

  @Test
  public void policyInUseWithoutCompiledFormFailsAgreement() {
    SnapshotBuilder.Built built = build(Fixtures.frrOspfBgp());
    Configuration r1 = built.configurations().get("r1");
    r1.setRoutingPolicies(
        r1.getRoutingPolicies().entrySet().stream()
            .filter(e -> !e.getKey().equals("EXPORT-SITE"))
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Comparator.naturalOrder(), Map.Entry::getKey, Map.Entry::getValue)));
    Batfish bf = _engine.batfish(built.id(), null);

    Lifter.Lifted lifted =
        Lifter.lift(
            "r1",
            r1,
            InputComposer.compose(built.request().devices().get(0)).text(),
            "configs/r1.cfg",
            bf.loadConvertConfigurationAnswerElementOrReparse(EngineBatfish.snapshot(built.id())));

    assertThat(lifted.holes(), hasSize(1));
    assertThat(lifted.holes().get(0).kind(), equalTo("agreement"));
    assertThat(lifted.holes().get(0).text(), containsString("EXPORT-SITE"));
  }

  @Test
  public void vtyshPreambleAndBareExitsAreStripped() {
    String composed =
        InputComposer.compose(
                new Device(
                    "r1",
                    Dialect.FRR_LINUX,
                    Fixtures.text("frr-ospf-bgp/r1.frr"),
                    Fixtures.r1Interfaces()))
            .text();

    assertThat(composed, not(containsString("Building configuration")));
    assertThat(composed, not(containsString("\nexit\n")));
    assertThat(composed, containsString(" exit-address-family\n"));
    assertThat(composed, startsWith("r1\n# This file describes the network interfaces\n"));
  }
}
