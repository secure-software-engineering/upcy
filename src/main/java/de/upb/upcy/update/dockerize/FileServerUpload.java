package de.upb.upcy.update.dockerize;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.surefire.shared.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileServerUpload {
  private static final Logger LOGGER = LoggerFactory.getLogger(FileServerUpload.class);

  private final CloseableHttpClient httpClient;
  private final String host;
  /** Timeout in seconds */
  private final int timeout = 5;

  public FileServerUpload(String host, String user, String pass) {

    CredentialsProvider provider = new BasicCredentialsProvider();
    UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user, pass);
    provider.setCredentials(AuthScope.ANY, credentials);

    this.httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build();
    this.host = host;
  }

  public void uploadFileWebDav(File upFile) throws IOException {
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

  public void getFileWebDav(String upFile, Path target) throws IOException {
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

  public void uploadZipAndUnzip(File upFile) throws IOException {
    if (upFile.exists()) {
      LOGGER.info("[Worker] Uploading file: {}", upFile);

      HttpPost post = new HttpPost(this.host);
      MultipartEntityBuilder builder = MultipartEntityBuilder.create();
      builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
      builder.addBinaryBody("file", upFile, ContentType.DEFAULT_BINARY, upFile.getName());
      StringBody stringBody1 = new StringBody("true", ContentType.MULTIPART_FORM_DATA);

      builder.addPart("unzip", stringBody1);
      HttpEntity entity = builder.build();
      post.setEntity(entity);
      HttpResponse response = httpClient.execute(post);
      LOGGER.debug("HTTP Response: {}", response);
    } else {
      LOGGER.error("Could not find csv file: {}", upFile);
    }
  }

  public void close() {
    try {
      this.httpClient.close();
    } catch (IOException e) {
      //
    }
  }
}
