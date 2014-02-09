package de.matrixweb.nodejs;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.matrixweb.vfs.VFS;

/**
 * @author markusw
 */
public class NodeJsExecutor {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(NodeJsExecutor.class);

  private final String version = "0.10.24";

  private final ObjectMapper om = new ObjectMapper();

  private File workingDir;

  /**
   * Sets the npm-module folder to the bridge to be called from Java.
   * 
   * @param clazz
   *          The calling {@link Class} to search the module from (required to
   *          be OSGi capable)
   * @param path
   *          The path or name of the npm-module to install relative to the
   *          class-path root
   * @throws IOException
   *           Thrown if the installation of the npm-module fails
   * @throws NodeJsException
   */
  public void setModule(final Class<?> clazz, final String path)
      throws IOException, NodeJsException {
    setModule(clazz, path, null);
  }

  /**
   * Sets the npm-module folder to the bridge to be called from Java.
   * 
   * @param clazz
   *          The calling {@link Class} to search the module from (required to
   *          be OSGi capable)
   * @param path
   *          The path or name of the npm-module to install relative to the
   *          class-path root
   * @param script
   *          The bridge script between java and node
   * @throws IOException
   *           Thrown if the installation of the npm-module fails
   * @throws NodeJsException
   */
  public void setModule(final Class<?> clazz, final String path,
      final String script) throws IOException, NodeJsException {
    if (this.workingDir != null) {
      throw new NodeJsException("Module already set");
    }

    try {
      setupBinary();
    } catch (final NodeJsException e) {
      if (this.workingDir != null) {
        cleanupBinary();
      }
      throw e;
    }

    final Enumeration<URL> urls = clazz.getClassLoader().getResources(path);
    while (urls.hasMoreElements()) {
      copyModuleToWorkingDirectory(urls.nextElement(), clazz);
    }
    if (script != null) {
      copyScriptToWorkingDirectory(clazz.getClassLoader().getResource(script),
          clazz);
    }
  }

  private final void setupBinary() throws NodeJsException {
    try {
      this.workingDir = File.createTempFile("nodejs-v" + this.version, ".dir");
      this.workingDir.delete();
      this.workingDir.mkdirs();
      extractBinary(this.workingDir);
    } catch (final IOException e) {
      throw new NodeJsException("Unable to setup the node folder", e);
    }
  }

  private final void cleanupBinary() {
    if (this.workingDir != null) {
      LOGGER.info("Shutdown nodejs (removing {})", this.workingDir);
      try {
        FileUtils.deleteDirectory(this.workingDir);
      } catch (final IOException e) {
        LOGGER.warn("Failed to delete node.js process directory", e);
      }
    }
  }

  private final void extractBinary(final File target) throws IOException {
    final File node = new File(target, getPlatformExecutable());
    copyFile("/v" + this.version + "/" + getPlatformPath() + "/"
        + getPlatformExecutable(), node);
    node.setExecutable(true, true);
    copyFile("/v" + this.version + "/ipc.js", new File(target, "ipc.js"));
  }

  private final String getPlatformPath() {
    final StringBuilder sb = new StringBuilder();
    if (SystemUtils.IS_OS_WINDOWS) {
      sb.append("win");
    } else if (SystemUtils.IS_OS_MAC_OSX) {
      sb.append("macos");
    } else if (SystemUtils.IS_OS_LINUX) {
      sb.append("linux");
    }
    if (SystemUtils.OS_ARCH.contains("64")) {
      sb.append("-x86_64");
    } else {
      sb.append("-x86");
    }
    return sb.toString();
  }

  private final String getPlatformExecutable() {
    if (SystemUtils.IS_OS_WINDOWS) {
      return "node.exe";
    }
    return "node";
  }

  private final void copyFile(final String inputFile, final File outputFile)
      throws IOException {
    InputStream in = null;
    try {
      in = getClass().getResourceAsStream(inputFile);
      if (in == null) {
        throw new FileNotFoundException(inputFile);
      }
      FileUtils.copyInputStreamToFile(in, outputFile);
    } finally {
      if (in != null) {
        IOUtils.closeQuietly(in);
      }
    }
  }

  void copyScriptToWorkingDirectory(final URL url, final Class<?> clazz)
      throws IOException, NodeJsException {
    copyModuleToWorkingDirectory(url, clazz);
    new File(this.workingDir, new File(url.getPath()).getName())
        .renameTo(new File(this.workingDir, "index.js"));
  }

  void copyModuleToWorkingDirectory(final URL url, final Class<?> clazz)
      throws IOException, NodeJsException {
    try {
      if ("file".equals(url.getProtocol())) {
        copyModuleFromFolder(url);
      } else if ("jar".equals(url.getProtocol())) {
        copyModuleFromJar(url);
      } else if ("bundle".equals(url.getProtocol())) {
        copyModuleFromBundle(url, clazz);
      } else {
        throw new NodeJsException("Unsupported url schema: " + url);
      }
    } catch (final URISyntaxException e) {
      throw new IOException("Invalid uri syntax", e);
    }
  }

  private void copyModuleFromJar(final URL url) throws IOException {
    String str = url.toString();
    final String path = str.substring(str.indexOf('!') + 2);
    str = str.substring("jar:file:".length(), str.indexOf('!'));
    final JarFile jar = new JarFile(str);
    try {
      final Enumeration<JarEntry> e = jar.entries();
      while (e.hasMoreElements()) {
        final JarEntry entry = e.nextElement();
        if (entry.getName().startsWith(path) && !entry.isDirectory()) {
          String name = entry.getName().substring(path.length());
          if (name.length() == 0) {
            name = entry.getName();
          }
          final File target = new File(this.workingDir, name);

          target.getParentFile().mkdirs();
          final InputStream in = jar.getInputStream(entry);
          FileUtils.copyInputStreamToFile(in, target);
        }
      }
    } finally {
      jar.close();
    }
  }

  private void copyModuleFromFolder(final URL url) throws URISyntaxException,
      IOException {
    final File file = new File(url.toURI());
    if (file.isDirectory()) {
      FileUtils.copyDirectory(file, this.workingDir);
    } else {
      FileUtils.copyFileToDirectory(file, this.workingDir);
    }
  }

  private void copyModuleFromBundle(final URL url, final Class<?> clazz)
      throws URISyntaxException, IOException {
    LOGGER.info("Searching calling bundle for {}", url.getPath());
    final Bundle bundle = FrameworkUtil.getBundle(clazz);
    final Enumeration<URL> urls = bundle.findEntries(url.getPath(), null, true);
    if (urls != null) {
      while (urls.hasMoreElements()) {
        final URL resourceUrl = urls.nextElement();
        final File target = new File(this.workingDir, resourceUrl.getPath()
            .substring(url.getPath().length()));
        target.getParentFile().mkdirs();
        if (resourceUrl.getPath().endsWith("/")) {
          target.mkdir();
        } else {
          copyUrlFile(resourceUrl, target);
        }
      }
    } else {
      copyUrlFile(bundle.getEntry(url.getPath()),
          new File(this.workingDir, url.getPath()));
    }
  }

  private void copyUrlFile(final URL url, final File target) throws IOException {
    final InputStream in = url.openStream();
    try {
      final OutputStream out = new FileOutputStream(target);
      try {
        IOUtils.copy(in, out);
      } finally {
        IOUtils.closeQuietly(out);
      }
    } finally {
      IOUtils.closeQuietly(in);
    }
  }

  /**
   * Executes the installed npm-module.
   * 
   * The given VFS is given node as input files to operate on.
   * 
   * @param vfs
   *          The {@link VFS} to operate on.
   * @param infile
   * @param options
   *          A map of options given to the node process
   * @return Returns the node.js processed result
   * @throws IOException
   */
  public String run(final VFS vfs, final String infile,
      final Map<String, Object> options) throws IOException {
    if (this.workingDir == null) {
      throw new NodeJsException("Module not set");
    }

    final File temp = File.createTempFile("node-resource", ".dir");
    try {
      temp.delete();
      if (!temp.mkdirs()) {
        throw new NodeJsException("Failed to create temp folder " + temp);
      }
      final File infolder = new File(temp, "input");
      if (!infolder.mkdirs()) {
        throw new NodeJsException("Failed to create temp folder " + infolder);
      }
      final File outfolder = new File(temp, "output");
      if (!outfolder.mkdirs()) {
        throw new NodeJsException("Failed to create temp folder " + outfolder);
      }

      vfs.exportFS(infolder);

      final String resultPath = callNode(infile, infolder, outfolder, options);
      vfs.stack();
      vfs.importFS(outfolder);
      return resultPath;
    } finally {
      FileUtils.deleteDirectory(temp);
    }
  }

  private String callNode(final String infile, final File infolder,
      final File outfolder, final Map<String, Object> options)
      throws IOException {
    String resultPath = null;

    final Map<String, Object> command = new HashMap<String, Object>();
    command.put("cwd", this.workingDir.getAbsolutePath());
    command.put("indir", infolder.getAbsolutePath());
    if (infile != null) {
      command
          .put("file", infile.startsWith("/") ? infile.substring(1) : infile);
    }
    command.put("outdir", outfolder.getAbsolutePath());
    command.put("options", options);

    String commandArg = this.om.writeValueAsString(command);
    if (SystemUtils.IS_OS_WINDOWS) {
      commandArg = '"' + commandArg.replaceAll("\"", "\\\\\"") + '"';
    }

    LOGGER.info("Execute node with json arg: {}", commandArg);
    final ProcessBuilder builder = new ProcessBuilder(new File(this.workingDir,
        getPlatformExecutable()).getAbsolutePath(), "ipc.js", commandArg)
        .directory(this.workingDir);
    builder.environment().put("NODE_PATH", ".");
    try {
      final Process process = builder.start();
      process.waitFor();
      final String result = waitForResponse(process);
      resultPath = handleResponse(result);
    } catch (final RuntimeException e) {
      throw e;
    } catch (final InterruptedException e) {
      throw new NodeJsException("node.js process interrupted", e);
    }
    return resultPath;
  }

  @SuppressWarnings("unchecked")
  private String handleResponse(final String response) throws IOException {
    String resultPath = null;

    try {
      LOGGER.info("node.js output:\n"
          + response.replaceAll("(?m)^[^/][^/].*?$", "")
              .replaceAll("(?m)^//", "").replaceAll("\n+", "\n"));
      final String trimmedOutput = response.replaceAll("(?m)^//.*?$", "")
          .trim();
      final Map<String, Object> map = trimmedOutput.length() == 0 ? Collections
          .emptyMap() : this.om.readValue(trimmedOutput, Map.class);
      if (map.containsKey("output")) {
        final StringBuilder sb = new StringBuilder();
        for (final Map<String, Object> entry : (List<Map<String, Object>>) map
            .get("output")) {
          if ("INFO".equals(entry.get("level"))) {
            sb.append("INFO  ");
          } else if ("ERROR".equals(entry.get("level"))) {
            sb.append("ERROR ");
          }
          sb.append(entry.get("message").toString()).append('\n');
        }
        LOGGER.info("node.js output:\n"
            + sb.toString().replaceAll("\\\\'", "###").replaceAll("'", "")
                .replaceAll("###", "'"));
      }
      if (map.containsKey("error")) {
        LOGGER.error(map.get("error").toString());
        throw new NodeJsException(map.get("error").toString());
      }

      if (map.containsKey("result")) {
        resultPath = map.get("result").toString();
      }
    } catch (final JsonParseException e) {
      LOGGER.error(response.replaceAll("(?m)^//.*?$", "").replaceAll("\n+",
          "\n"));
      throw new NodeJsException(response, e);
    }

    return resultPath;
  }

  private String waitForResponse(final Process process) throws IOException {
    final StringBuilder sb = new StringBuilder();
    sb.append(IOUtils.toString(process.getInputStream())).append('\n')
        .append(IOUtils.toString(process.getErrorStream()));
    return sb.toString();
  }

  /**
   * 
   */
  public void dispose() {
    cleanupBinary();
  }

}
