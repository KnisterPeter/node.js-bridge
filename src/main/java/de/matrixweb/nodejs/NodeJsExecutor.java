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

  private final Worker worker;

  private Thread thread;

  private String name;

  /**
   * @throws NodeJsException
   */
  public NodeJsExecutor() throws NodeJsException {
    this.worker = new Worker();
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
   * @deprecated Use {@link #setModule(ClassLoader, String)} instead
   */
  @Deprecated
  public void addModule(final ClassLoader cl, final String path)
      throws IOException, NodeJsException {
    setModule(cl, path);
  }

  /**
   * Sets the npm-module folder to the bridge to be called from Java.
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
  public void setModule(final ClassLoader cl, final String path)
      throws IOException, NodeJsException {
    setModule(cl, path, null);
  }

  /**
   * Sets the npm-module folder to the bridge to be called from Java.
   * 
   * @param cl
   *          The {@link ClassLoader} to search the module from (required to be
   *          OSGi capable)
   * @param path
   *          The path or name of the npm-module to install relative to the
   *          class-path root
   * @param script
   *          The bridge script between java and node
   * @throws IOException
   *           Thrown if the installation of the npm-module fails
   * @throws NodeJsException
   */
  public void setModule(final ClassLoader cl, final String path,
      final String script) throws IOException, NodeJsException {
    if (this.name != null) {
      throw new NodeJsException("Module already set");
    }
    this.name = path;
    final Enumeration<URL> urls = cl.getResources(path);
    while (urls.hasMoreElements()) {
      this.worker.copyModuleToWorkingDirectory(urls.nextElement());
    }
    if (script != null) {
      this.worker.copyScriptToWorkingDirectory(cl.getResource(script));
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
    if (this.thread == null) {
      if (this.name == null) {
        throw new NodeJsException("No module installed");
      }
      this.thread = new Thread(this.worker, this.name);
      this.thread.setDaemon(false);
      this.thread.start();
    }
    return this.worker.executeTask(new Worker.Task(vfs, infile, options))
        .getResult();
  }

  /**
   * Must be called when stopping the node-bridge to cleanup temporary
   * resources.
   */
  public void dispose() {
    this.worker.dispose();
    if (this.thread != null) {
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

}
