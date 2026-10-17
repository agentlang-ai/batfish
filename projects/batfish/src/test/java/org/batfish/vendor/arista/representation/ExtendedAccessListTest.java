package org.batfish.vendor.arista.representation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.LineAction;
import org.junit.Test;

public class ExtendedAccessListTest {
  private static ExtendedAccessListLine line(String name, LineAction action) {
    return new StandardAccessListActionLine(1, action, name, IpWildcard.ANY)
        .toExtendedAccessListLine()
        .orElseThrow();
  }

  @Test
  public void testSequenceInsertionAndReplacement() {
    ExtendedAccessList acl = new ExtendedAccessList("HTTPS");
    ExtendedAccessListLine permit = line("permit", LineAction.PERMIT);
    ExtendedAccessListLine deny = line("deny", LineAction.DENY);
    ExtendedAccessListLine replacement = line("replacement", LineAction.PERMIT);
    acl.addLine(10, permit);
    acl.addLine(5, deny);
    assertThat(acl.getLines(), contains(deny, permit));
    acl.addLine(5, replacement);
    assertThat(acl.getLines(), contains(replacement, permit));
    acl.addLine(deny);
    assertThat(acl.getLines(), contains(replacement, permit, deny));
    acl.removeLine(5);
    acl.removeLine(999);
    assertThat(acl.getLines(), contains(permit, deny));
  }
}
