package de.upb.upcy.update.dockerize;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.maven.surefire.shared.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author adann */
public class LocalClient implements IClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalClient.class);

  private final Path rootDir;

  LocalClient(String rootPath) {
    this.rootDir = Paths.get(URI.create(rootPath));
    if (!Files.exists(rootDir) && !Files.isDirectory(rootDir)) {
      throw new IllegalArgumentException(String.format("No valid folder %s", rootPath));
    }

    LOGGER.info("Created LocalClient");
  }

  @Override
  public void uploadFile(File upFile) throws IOException {
    FileUtils.copyFileToDirectory(upFile, rootDir.toFile());
  }

  @Override
  public void downloadFile(String upFile, Path target) throws IOException {
    final Path srcFile = rootDir.resolve(upFile);
    FileUtils.copyFile(srcFile.toFile(), target.toFile());
  }

  @Override
  public void close() {}
}
