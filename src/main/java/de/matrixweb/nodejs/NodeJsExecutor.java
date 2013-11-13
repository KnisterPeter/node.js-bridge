package de.matrixweb.nodejs;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;

import de.matrixweb.vfs.VFS;

/**
 * @author markusw
 */
public class NodeJsExecutor {

  private final Worker worker = new Worker();

  private final Thread thread;

  /**
   * Creates a new node.js bridge and setup the working directory.
   * 
   * @throws NodeJsException
   */
  public NodeJsExecutor() throws NodeJsException {
    this.thread = new Thread(this.worker);
    this.thread.setDaemon(false);
    this.thread.start();
  }

  /**
   * Adds a npm-module folder to the bridge to be called from Java.
   * 
   * @param cl
   *          The {@link ClassLoader} to search the module from (required to be
   *          OSGi capable)
   * @param path
   *          The path or name of the npm-module to install relative to the
   *          class-path root
   * @throws IOException
   *           Thrown if the installation of the npm-module fails
   * @throws NodeJsException
   */
  public void addModule(final ClassLoader cl, final String path)
      throws IOException, NodeJsException {
    final Enumeration<URL> urls = cl.getResources(path);
    while (urls.hasMoreElements()) {
      this.worker.copyModuleToWorkingDirectory(urls.nextElement());
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
      final Map<String, String> options) throws IOException {
    return this.worker.executeTask(new Worker.Task(vfs, infile, options))
        .getResult();
  }

  /**
   * Must be called when stopping the node-bridge to cleanup temporary
   * resources.
   */
  public void dispose() {
    this.worker.dispose();
    this.thread.interrupt();
    while (this.thread.isAlive()) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        // Ignore this
      }
    }
  }

}
