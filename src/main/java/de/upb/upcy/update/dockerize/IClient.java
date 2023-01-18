package de.upb.upcy.update.dockerize;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public interface IClient {

  void uploadFile(File upFile) throws IOException;

  void downloadFile(String upFile, Path target) throws IOException;

  void close();

  public static IClient createClient(String host, String user, String pass) {
    // Todo make nice in the future
    if (host.startsWith("http://") || host.startsWith("https://")) {
      return new WebDavClient(host, user, pass);
    } else if (host.startsWith("file:/")) {
      return new LocalClient(host);
    }
    return null;
  }
}
