package de.upb.upcy.update;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class Gav {
  public String group;
  public String artifact;
  public String version;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;

    if (o == null || getClass() != o.getClass()) return false;

    Gav gav = (Gav) o;

    return new EqualsBuilder()
        .append(group, gav.group)
        .append(artifact, gav.artifact)
        .append(version, gav.version)
        .isEquals();
  }

  @Override
  public String toString() {
    return group + ":" + artifact + ":" + version;
  }

  @Override
  public int hashCode() {
    return new HashCodeBuilder(17, 37).append(group).append(artifact).append(version).toHashCode();
  }
}
