package de.upb.upcy.update.dockerize;

import junit.framework.TestCase;

public class IClientTest extends TestCase {

  public void testCreateClient() {
    final IClient client = IClient.createClient("file:/home", "", "");
    assertTrue(client instanceof LocalClient);
  }
}
