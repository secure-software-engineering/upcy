package de.upb.upb.osv;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class OsvProceeding {

  private final String rootPath;
  private final Instant filterAfter;
  private final String dataPath;
  public final String aggregatedDataFile;
  private static final String OSV_DATA_URL = "https://storage.googleapis.com/osv-vulnerabilities/Maven/all.zip";
  private String coralFile;

  public OsvProceeding(String osvDataFolder, Instant filterAfter) {
    this.rootPath = osvDataFolder;
    this.filterAfter = filterAfter;
    dataPath = rootPath + "/maven";
    this.aggregatedDataFile = rootPath + "/aggregated_data.json";

  }

  public void initOsvData(boolean update) {

    if (update == false) {
      File dataFile = new File(aggregatedDataFile);
      if (dataFile.exists()) {
        return;
      }
      throw new IllegalArgumentException("Could not find aggregated data file OSV");
    }

    downloadOsvDatabase();
  }

  public String createAggregateDataFile4Goblin() {
    Map<String, JSONArray> aggregatedData = new HashMap<>();
    try {
      File osvFolder = new File(dataPath);
      if (osvFolder.isDirectory()) {
        File[] files = osvFolder.listFiles();
        System.out.println("Prepare osv aggregated json");
        for (File file : files) {
          JSONParser parser = new JSONParser();
          JSONObject jsonObject = (JSONObject) parser.parse(new FileReader(file));
          JSONObject cveObject = new JSONObject();
          String published = jsonObject.get("published") == null ? "UNKNOWN"
              : jsonObject.get("published").toString();
          // filter
          Instant parse = Instant.parse(published);
          if (parse.isAfter(filterAfter)) {
            continue;
          }

          JSONArray aliases = jsonObject.get("aliases") == null ? new JSONArray()
              : (JSONArray) jsonObject.get("aliases");
          String cveName = aliases.size() == 0 ? "UNKNOWN" : aliases.get(0).toString();
          JSONObject database_specific =
              jsonObject.get("database_specific") == null ? new JSONObject()
                  : (JSONObject) jsonObject.get("database_specific");
          String severity = database_specific.get("severity") == null ? "UNKNOWN"
              : database_specific.get("severity").toString();
          String cwe_ids = database_specific.get("cwe_ids") == null ? "UNKNOWN"
              : database_specific.get("cwe_ids").toString();
          cveObject.put("name", cveName.replaceAll("[\"]", ""));
          cveObject.put("severity", severity.replaceAll("[\"]", ""));
          cveObject.put("cwe_ids", cwe_ids.replaceAll("[\"]", ""));
          JSONArray affectedPackages = (JSONArray) jsonObject.get("affected");
          for (Object obj : affectedPackages) {
            JSONObject affected = (JSONObject) obj;
            JSONObject pkg = (JSONObject) affected.get("package");
            String packageName = (String) pkg.get("name");
            JSONArray versions = affected.get("versions") == null ? new JSONArray()
                : (JSONArray) affected.get("versions");
            for (Object versionObj : versions) {
              String version = versionObj.toString();
              String key = packageName + ":" + version;
              if (!aggregatedData.containsKey(key)) {
                aggregatedData.put(key, new JSONArray());
              }
              aggregatedData.get(key).add(cveObject);
            }
          }
        }
      }
      System.out.println("Export osv aggregated json");
      JSONObject outputObject = new JSONObject(aggregatedData);
      try (FileWriter file = new FileWriter(aggregatedDataFile)) {
        file.write(outputObject.toJSONString());
        file.flush();
      }
    } catch (IOException | ParseException e) {
      e.printStackTrace();
    }
    return aggregatedDataFile;
  }

  public File downloadOsvDatabase() {
    System.out.println("Download osv dataset");
    // Create folder if not exist
    File dir = new File(dataPath);
    File rootDir = new File(rootPath);
    if (rootDir.exists()) {
      rootDir.delete();
    }
    dir.mkdirs();
    try {
      URL url = new URL(OSV_DATA_URL);
      try (InputStream in = url.openStream(); ZipInputStream zipIn = new ZipInputStream(in)) {

        ZipEntry entry;
        byte[] buffer = new byte[1024];

        while ((entry = zipIn.getNextEntry()) != null) {
          String filePath = dataPath + File.separator + entry.getName();
          if (!entry.isDirectory()) {
            try (BufferedOutputStream bos = new BufferedOutputStream(
                new FileOutputStream(filePath))) {
              int read;
              while ((read = zipIn.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
              }
            }
          } else {
            File subDir = new File(filePath);
            subDir.mkdir();
          }

          zipIn.closeEntry();
        }
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    } catch (MalformedURLException e) {
      e.printStackTrace();
      return null;
    }
    return new File(dataPath);
  }


  public Path createInput4Coral() throws IOException {
    List<String> cveEntires = new ArrayList<>();

    String header = ":START_ID(Vulnerability),:END_ID(Version),:TYPE";
    cveEntires.add(header);

    try {
      File osvFolder = new File(dataPath);
      if (osvFolder.isDirectory()) {
        File[] files = osvFolder.listFiles();
        System.out.println("Prepare osv for coral");
        for (File file : files) {
          JSONParser parser = new JSONParser();
          JSONObject jsonObject = (JSONObject) parser.parse(new FileReader(file));
          JSONObject cveObject = new JSONObject();
          String published = jsonObject.get("published") == null ? "UNKNOWN"
              : jsonObject.get("published").toString();
          // filter
          Instant parse = Instant.parse(published);
          if (parse.isAfter(filterAfter)) {
            continue;
          }

          JSONArray aliases = jsonObject.get("aliases") == null ? new JSONArray()
              : (JSONArray) jsonObject.get("aliases");
          String cveName =
              aliases.size() == 0 ? jsonObject.get("id").toString() : aliases.get(0).toString();
          cveName = cveName.replaceAll("[\"]", "");
          JSONArray affectedPackages = (JSONArray) jsonObject.get("affected");
          for (Object obj : affectedPackages) {
            JSONObject affected = (JSONObject) obj;
            JSONObject pkg = (JSONObject) affected.get("package");
            String packageName = (String) pkg.get("name");
            JSONArray versions = affected.get("versions") == null ? new JSONArray()
                : (JSONArray) affected.get("versions");
            for (Object versionObj : versions) {
              String version = versionObj.toString();
              String key = packageName + ":" + version;
              String cveEntryForFile = cveName + "," + packageName + ":" + version + ",AFFECTS";
              cveEntires.add(cveEntryForFile);
            }
          }
        }
      }
      System.out.println("Export osv for coral");
      Files.write(Paths.get(coralFile), cveEntires);

    } catch (IOException | ParseException e) {
      e.printStackTrace();
    }
    return Paths.get(coralFile);
  }


}

