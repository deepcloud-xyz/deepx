package net.deepcloud.deepx.api;

import net.deepcloud.deepx.common.*;
import net.deepcloud.deepx.container.DeepXContainerId;
import org.apache.hadoop.ipc.VersionedProtocol;
import org.apache.hadoop.mapred.InputSplit;

public interface ApplicationContainerProtocol extends VersionedProtocol {

  public static final long versionID = 1L;

  void reportReservedPort(String host, int port, String role, int index);

  void reportLightGbmIpPort(DeepXContainerId containerId, String lightGbmIpPort);

  String getLightGbmIpPortStr();

  void reportLightLDAIpPort(DeepXContainerId containerId, String lightLDAIpPort);

  String getLightLDAIpPortStr();

  String getClusterDef();

  HeartbeatResponse heartbeat(DeepXContainerId containerId, HeartbeatRequest heartbeatRequest);

  InputInfo[] getInputSplit(DeepXContainerId containerId);

  InputSplit[] getStreamInputSplit(DeepXContainerId containerId);

  OutputInfo[] getOutputLocation();

  void reportTensorBoardURL(String url);

  void reportMapedTaskID(DeepXContainerId containerId, String taskId);

  void reportCpuMetrics(DeepXContainerId containerId, String cpuMetrics);

  Long interResultTimeStamp();

  int isApplicationCompleted();

}
