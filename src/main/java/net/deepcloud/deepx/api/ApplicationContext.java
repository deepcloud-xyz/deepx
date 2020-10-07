package net.deepcloud.deepx.api;

import net.deepcloud.deepx.common.InputInfo;
import net.deepcloud.deepx.common.Message;
import net.deepcloud.deepx.common.OutputInfo;
import net.deepcloud.deepx.container.DeepXContainerId;
import net.deepcloud.deepx.common.DeepXContainerStatus;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.mapred.InputSplit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public interface ApplicationContext {

  ApplicationId getApplicationID();

  int getWorkerNum();

  int getPsNum();

  int getWorkerMemory();

  int getChiefWorkerMemory();

  int getEvaluatorWorkerMemory();

  int getPsMemory();

  int getWorkerVCores();

  int getPsVCores();

  List<Container> getWorkerContainers();

  List<Container> getPsContainers();

  DeepXContainerStatus getContainerStatus(DeepXContainerId containerId);

  List<InputInfo> getInputs(DeepXContainerId containerId);

  List<InputSplit> getStreamInputs(DeepXContainerId containerId);

  List<OutputInfo> getOutputs();

  LinkedBlockingQueue<Message> getMessageQueue();

  String getTensorBoardUrl();

  Map<DeepXContainerId, String> getReporterProgress();

  Map<DeepXContainerId, String> getContainersAppStartTime();

  Map<DeepXContainerId, String> getContainersAppFinishTime();

  Map<DeepXContainerId, String> getMapedTaskID();

  Map<DeepXContainerId, ConcurrentHashMap<String, LinkedBlockingDeque<Object>>> getContainersCpuMetrics();

  Map<DeepXContainerId, ConcurrentHashMap<String, List<Double>>> getContainersCpuStatistics();

  int getSavingModelStatus();

  int getSavingModelTotalNum();

  Boolean getStartSavingStatus();

  void startSavingModelStatus(Boolean flag);

  Boolean getLastSavingStatus();

  List<Long> getModelSavingList();

  String getTfEvaluatorId();

  String getChiefWorkerId();

  Boolean getChiefWorker();

}
