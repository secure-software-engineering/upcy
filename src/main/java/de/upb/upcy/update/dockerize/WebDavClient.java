package de.upb.upcy.update.dockerize;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.surefire.shared.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebDavClient implements IClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(WebDavClient.class);

  private final CloseableHttpClient httpClient;
  private final String host;
  /** Timeout in seconds */
  private final int timeout = 5;

  WebDavClient(String host, String user, String pass) {

    CredentialsProvider provider = new BasicCredentialsProvider();
    UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user, pass);
    provider.setCredentials(AuthScope.ANY, credentials);

    this.httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
    this.host = host;
    LOGGER.info("Created WebDavClient");
  }

  @Override
  public void uploadFile(File upFile) throws IOException {
    if (upFile.exists()) {

      LOGGER.info("[Worker] Uploading file: {} of size {}", upFile, upFile.length() / 1024);

      HttpPut httpPut = new HttpPut(URI.create(this.host + "/" + upFile.getName()));

      ByteArrayEntity entity = new ByteArrayEntity(Files.readAllBytes(upFile.toPath()));
      httpPut.setEntity(entity);
      RequestConfig config =
          RequestConfig.custom()
              .setConnectTimeout(timeout * 1000)
              .setConnectionRequestTimeout(timeout * 1000)
              .setSocketTimeout(timeout * 1000)
              .build();

      httpPut.setConfig(config);
      HttpResponse response = httpClient.execute(httpPut);
      LOGGER.debug("HTTP Response: {}", response);
    } else {
      LOGGER.error("Could not upload file: {}", upFile);
    }
  }

  @Override
  public void downloadFile(String upFile, Path target) throws IOException {
    LOGGER.info("[Worker] Downloading file: {}", upFile);
    HttpGet httpget = new HttpGet(this.host + "/" + upFile);
    RequestConfig config =
        RequestConfig.custom()
            .setConnectTimeout(timeout * 1000)
            .setConnectionRequestTimeout(timeout * 1000)
            .setSocketTimeout(timeout * 1000)
            .build();

    httpget.setConfig(config);
    HttpResponse response = httpClient.execute(httpget);

    InputStream source = response.getEntity().getContent();
    FileUtils.copyInputStreamToFile(source, target.toFile());

    LOGGER.debug("HTTP Response: {}", response);
  }

  @Override
  public void close() {
    try {
      this.httpClient.close();
    } catch (IOException e) {
      //
    }
  }
}
