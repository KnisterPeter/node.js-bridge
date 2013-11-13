package de.matrixweb.nodejs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.matrixweb.vfs.VFS;

/**
 * @author markusw
 */
class Worker implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(Worker.class);

  private final ArrayBlockingQueue<Task> in = new ArrayBlockingQueue<Worker.Task>(
      1);

  private final ArrayBlockingQueue<Task> out = new ArrayBlockingQueue<Worker.Task>(
      1);

  private boolean running = true;

  private final String version = "0.10.18";

  private final ObjectMapper om = new ObjectMapper();

  private Process process;

  private File workingDir;

  int port = 0;

  // private Socket socket;

  /**
   * @throws NodeJsException
   */
  public Worker() throws NodeJsException {
    try {
      setupBinary();
    } catch (final NodeJsException e) {
      if (this.workingDir != null) {
        cleanupBinary();
      }
      throw e;
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
    try {
      FileUtils.deleteDirectory(this.workingDir);
    } catch (final IOException e) {
      LOGGER.warn("Failed to delete node.js process directory", e);
    }
  }

  private final void extractBinary(final File target) throws IOException {
    final File node = new File(target, getPlatformExecutable());
    copyFile("/v" + this.version + "/" + getPlatformPath() + "/"
        + getPlatformExecutable(), node);
    node.setExecutable(true, true);
    copyFile("/v" + this.version + "/ipc.js", new File(target, "ipc.js"));
    copyFile("/v" + this.version + "/ipc2.js", new File(target, "ipc2.js"));
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

  void copyModuleToWorkingDirectory(final URL url) throws IOException,
      NodeJsException {
    try {
      if ("file".equals(url.getProtocol())) {
        copyModuleFromFolder(url);
      } else if ("jar".equals(url.getProtocol())) {
        copyModuleFromJar(url);
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
          final File target = new File(this.workingDir, entry.getName()
              .substring(path.length()));
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

  Task executeTask(final Task task) throws NodeJsException {
    try {
      this.in.put(task);
      return this.out.take();
    } catch (final InterruptedException e) {
      throw new NodeJsException("Failed to execute task", e);
    }
  }

  /**
   * @see java.lang.Runnable#run()
   */
  @Override
  public void run() {
    while (this.running) {
      try {
        final Task task = this.in.take();
        try {
          task.result = execute(task.vfs, task.infile, task.options);
        } catch (final IOException e) {
          task.exception = e;
        } catch (final Exception e) {
          task.exception = new NodeJsException("Unexpected excption", e);
        }
        this.out.put(task);
      } catch (final InterruptedException e) {
        // Ignore this
      }
    }
    if (this.process != null) {
      this.process.destroy();
      this.process = null;
    }
    cleanupBinary();
  }

  private String execute(final VFS vfs, final String infile,
      final Map<String, String> options) throws NodeJsException, IOException {
    startNodeIfRequired();
    final File temp = File.createTempFile("node-resource", ".dir");
    try {
      temp.delete();
      temp.mkdirs();
      final File infolder = new File(temp, "input");
      infolder.mkdirs();
      final File outfolder = new File(temp, "output");
      outfolder.mkdirs();

      vfs.exportFS(infolder);

      try {
        final String resultPath = callNode(infile, infolder, outfolder, options);
        vfs.stack();
        vfs.importFS(outfolder);
        return resultPath;
      } catch (final InternalNodeJsException e) {
        return null;
      }
    } finally {
      FileUtils.deleteDirectory(temp);
    }
  }

  private String callNode(final String infile, final File infolder,
      final File outfolder, final Map<String, String> options)
      throws IOException, JsonGenerationException, JsonMappingException,
      JsonParseException, InternalNodeJsException {
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

    final Socket socket = new Socket("localhost", this.port);
    try {
      socket.getOutputStream().write(
          (this.om.writeValueAsString(command) + '\n').getBytes("UTF-8"));
      try {
        final String result = waitForResponse(socket);
        resultPath = handleResponse(result);
      } catch (final RuntimeException e) {
        throw e;
      }
      return resultPath;
    } finally {
      socket.close();
    }
  }

  @SuppressWarnings("unchecked")
  private String handleResponse(final String response) throws IOException {
    String resultPath = null;

    try {
      final Map<String, Object> map = this.om.readValue(response, Map.class);
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
        throw new NodeJsException(map.get("error").toString());
      }

      if (map.containsKey("result")) {
        resultPath = map.get("result").toString();
      }
    } catch (final JsonParseException e) {
      throw new NodeJsException(response);
    }

    return resultPath;
  }

  private void startNodeIfRequired() throws IOException, NodeJsException {
    try {
      if (this.process != null) {
        this.process.exitValue();
      }
      try {
        final ProcessBuilder builder = new ProcessBuilder(new File(
            this.workingDir, getPlatformExecutable()).getAbsolutePath(),
            "ipc2.js").directory(this.workingDir);
        builder.environment().put("NODE_PATH", ".");
        this.process = builder.start();
      } catch (final IOException e) {
        throw new NodeJsException("Unable to start node.js process", e);
      }

      if (this.process.getErrorStream().available() == 0) {
        this.port = Integer.parseInt(new BufferedReader(new InputStreamReader(
            this.process.getInputStream(), "UTF-8")).readLine());
      } else {
        checkNodeProcess();
        throw new NodeJsException("Failed to start node.js ipc");
      }
    } catch (final IllegalThreadStateException e) {
      // Just ignore and continue
    }
  }

  private String waitForResponse(final Socket socket) throws IOException {
    boolean pIn = false;
    boolean pEr = false;
    boolean sIn = false;
    while (!(pIn || pEr || sIn)) {
      try {
        Thread.sleep(25);
      } catch (final InterruptedException e) {
        // Ignore this
      }
      pIn = this.process.getInputStream().available() > 0;
      pEr = this.process.getErrorStream().available() > 0;
      sIn = socket != null ? socket.getInputStream().available() > 0 : false;
    }
    if (pIn) {
      return IOUtils.toString(this.process.getInputStream(), "UTF-8");
    } else if (pEr) {
      return IOUtils.toString(this.process.getErrorStream(), "UTF-8");
    }
    return new BufferedReader(new InputStreamReader(socket.getInputStream(),
        "UTF-8")).readLine();
  }

  private void checkNodeProcess() {
    try {
      this.process.exitValue();
      try {
        if (this.process.getInputStream().available() > 0) {
          LOGGER.info(IOUtils.toString(this.process.getInputStream()));
        }
        if (this.process.getErrorStream().available() > 0) {
          final String stderr = IOUtils.toString(this.process.getErrorStream());
          final Map<String, Object> map = this.om.readValue(stderr, Map.class);

          LOGGER.error("");
        }
      } catch (final IOException e) {
        LOGGER.error("Failed to read process output", e);
      }
    } catch (final IllegalThreadStateException e) {
      // Process is running
    }
  }

  void dispose() {
    this.running = false;
  }

  public static class Task {

    VFS vfs;

    String infile;

    Map<String, String> options;

    String result;

    IOException exception;

    /**
     * @param vfs
     * @param infile
     * @param options
     */
    public Task(final VFS vfs, final String infile,
        final Map<String, String> options) {
      this.vfs = vfs;
      this.infile = infile;
      this.options = options;
    }

    /**
     * @return the result
     * @throws IOException
     */
    public String getResult() throws IOException {
      if (this.exception != null) {
        throw this.exception;
      }
      return this.result;
    }

  }

  private static class InternalNodeJsException extends NodeJsException {

    private static final long serialVersionUID = -1023579709002742028L;

  }

}
