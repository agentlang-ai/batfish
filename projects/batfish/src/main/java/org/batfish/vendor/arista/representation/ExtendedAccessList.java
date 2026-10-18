package org.batfish.vendor.arista.representation;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nonnull;

public class ExtendedAccessList implements Serializable {

  private final @Nonnull SortedMap<Long, ExtendedAccessListLine> _lines;
  private final @Nonnull String _name;
  private StandardAccessList _parent;

  public ExtendedAccessList(@Nonnull String id) {
    _name = id;
    _lines = new TreeMap<>();
  }

  public void addLine(@Nonnull ExtendedAccessListLine line) {
    addLine(_lines.isEmpty() ? 10 : _lines.lastKey() + 10, line);
  }

  public void addLine(long sequence, @Nonnull ExtendedAccessListLine line) {
    _lines.put(sequence, line);
  }

  public void removeLine(long sequence) {
    _lines.remove(sequence);
  }

  public @Nonnull List<ExtendedAccessListLine> getLines() {
    return ImmutableList.copyOf(_lines.values());
  }

  public @Nonnull String getName() {
    return _name;
  }

  public StandardAccessList getParent() {
    return _parent;
  }

  public void setParent(StandardAccessList parent) {
    _parent = parent;
  }

  @Override
  public String toString() {
    StringBuilder output = new StringBuilder(super.toString() + "\n" + "Identifier: " + _name);
    for (ExtendedAccessListLine line : _lines.values()) {
      output.append("\n").append(line);
    }
    return output.toString();
  }
}
