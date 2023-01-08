package de.upb.upcy.update.recommendation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NodeMatchUtilTest {

  @Test
  public void toGav() {
    String cpEntry =
        "/Users/myUser/.m2/repository/commons-net/commons-net/3.8.0/commons-net-3.8.0.jar";
    final String s = new NodeMatchUtil(null).toGav(cpEntry);
    assertEquals("commons-net:commons-net:3.8.0", s);
  }

  @Test
  public void toGav2() {
    String cpEntry = "/Users/myUser/.m2/repository/net/lingala/zip4j/zip4j/2.6.1/zip4j-2.6.1.jar";
    final String s = new NodeMatchUtil(null).toGav(cpEntry);
    assertEquals("net.lingala.zip4j:zip4j:2.6.1", s);
  }
}
