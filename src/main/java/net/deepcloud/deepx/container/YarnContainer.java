package net.deepcloud.deepx.container;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import net.deepcloud.deepx.util.Utilities;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class YarnContainer implements IContainerLaunch {

  private static final Log LOG = LogFactory.getLog(YarnContainer.class);
  private DeepXContainerId containerId;
  private Process deepxProcess;

  public YarnContainer(DeepXContainerId containerId) {
    this.containerId = containerId;
  }

  @Override
  public Process exec(String command, String[] envp, Map<String, String> envs, File dir) throws IOException {
    Runtime rt = Runtime.getRuntime();
    deepxProcess = rt.exec(command, envp, dir);
    return deepxProcess;
  }

  @Override
  public boolean isAlive() {
    if (deepxProcess != null) {
      Utilities.isProcessAlive(deepxProcess);
    }
    return false;
  }
}
