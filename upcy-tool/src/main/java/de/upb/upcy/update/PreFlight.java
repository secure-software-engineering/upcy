package de.upb.upcy.update;

import de.upb.maven.ecosystem.persistence.dao.Neo4JConnector;
import de.upb.upcy.base.sigtest.db.MongoDBHandler;
import java.io.IOException;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author adann */
public final class PreFlight {
  private static final Logger LOGGER = LoggerFactory.getLogger(PreFlight.class);

  private static boolean reachableMongo() {
    final String mongoHostFromEnvironment = MongoDBHandler.getMongoHostFromEnvironment();
    LOGGER.info("Check if MongoDB is up and running {}:{}", mongoHostFromEnvironment, 27017);
    try (Socket ignored = new Socket(mongoHostFromEnvironment, 27017)) {
      LOGGER.info("MongoDB is reachable");
      return true;
    } catch (IOException ignored) {
      LOGGER.error("Cannot reach MongoDB {}", MongoDBHandler.getMongoHostFromEnvironment());
      return false;
    }
  }

  private static boolean reachableNeo4j() {
    final String neo4jURL = Neo4JConnector.getNeo4jURL();
    final String cleardURL = neo4jURL.replace("bolt://", "");
    final String host = cleardURL.split(":")[0];
    final String port = cleardURL.split(":")[1];
    LOGGER.info("Check if Neo4j is up and running {}:{}", host, port);
    try (Socket ignored = new Socket(host, Integer.parseInt(port))) {
      LOGGER.info("Neo4j is reachable");
      return true;
    } catch (IOException ignored) {
      LOGGER.error("Cannot reach Neo4j: {}", host);
      return false;
    }
  }

  public static boolean preFlightCheck() {
    if (!reachableMongo()) {
      return false;
    }
    if (!reachableNeo4j()) {
      return false;
    }
    return true;
  }
}
