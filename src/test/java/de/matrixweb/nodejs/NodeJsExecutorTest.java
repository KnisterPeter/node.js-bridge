package de.matrixweb.nodejs;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import de.matrixweb.vfs.VFS;
import de.matrixweb.vfs.VFSUtils;

import static org.junit.Assert.*;

import static org.hamcrest.CoreMatchers.*;

/**
 * @author marwol
 */
public class NodeJsExecutorTest {

  /**
   * @throws IOException
   */
  @Test(expected = NodeJsException.class)
  public void test() throws IOException {
    final NodeJsExecutor exec = new NodeJsExecutor();
    try {
      final VFS vfs = new VFS();
      try {
        VFSUtils.write(vfs.find("/some.file"), "content");

        final Map<String, String> options = Collections.emptyMap();
        final String path = exec.run(vfs, "/some.file", options);

        assertThat(path, is(nullValue()));
      } finally {
        vfs.dispose();
      }
    } finally {
      exec.dispose();
    }
  }

  /**
   * @throws IOException
   */
  @Test(expected = NodeJsException.class)
  public void testBrokenIndex() throws IOException {
    final NodeJsExecutor exec = new NodeJsExecutor();
    try {
      final VFS vfs = new VFS();
      try {
        exec.setModule(getClass().getClassLoader(), "test-mod");
        exec.run(vfs, null, null);
      } finally {
        vfs.dispose();
      }
    } finally {
      exec.dispose();
    }
  }

}
