package net.deepcloud.deepx.AM;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.deepcloud.deepx.api.*;
import net.deepcloud.deepx.common.*;
import net.deepcloud.deepx.common.exceptions.ContainerRuntimeException;
import net.deepcloud.deepx.common.exceptions.DeepXExecException;
import net.deepcloud.deepx.conf.DeepXConfiguration;
import net.deepcloud.deepx.container.DeepXContainerId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hadoop.mapred.InputSplit;

import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

public class ApplicationContainerListener extends AbstractService implements ApplicationContainerProtocol,
    ContainerListener {

  private static final Log LOG = LogFactory.getLog(ApplicationContainerListener.class);

  private final ApplicationContext applicationContext;

  private Server server;

  private final Map<DeepXContainerId, String> containerId2Role;

  private final Map<DeepXContainerId, DeepXContainerStatus> containerId2Status;

  private final Map<String, List<ContainerHostPair>> clusterDef;

  private final Map<DeepXContainerId, String> lightGBMIpPortMap;

  private final Map<DeepXContainerId, String> lightLDAIpPortMap;

  private final Boolean single;

  private final Boolean tfDistributionStrategy;

  private final Boolean tfEvaluator;

  private final Map<DeepXContainerId, String> reporterProgress;

  private final Map<DeepXContainerId, String> mapedTaskID;

  private final Map<DeepXContainerId, String> containersAppStartTimeMap;

  private final Map<DeepXContainerId, String> containersAppFinishTimeMap;

  private final Map<DeepXContainerId, ConcurrentHashMap<String, LinkedBlockingDeque<Object>>> containersCpuMetrics;

  private final Map<DeepXContainerId, ConcurrentHashMap<String, ContainerMetricsStatisticsTuple>> containersCpuStatistics;

  private String clusterDefStr;

  private String lightGBMIpPortStr;

  private String lightLDAIpPortStr;

  private final Clock clock;

  private final ConcurrentMap<DeepXContainerId, LastTime> runningContainers;

  private Thread containerTimeoutMonitor;

  private int containerTimeOut;

  private int localResourceTimeOut;

  private int monitorInterval;

  private boolean isDeepXTrainFinished;

  private String tensorboardUrl;

  private boolean isSaveInnerModel;

  private Long interResultTimeStamp;

  private final Map<DeepXContainerId, InnerModelSavedPair> containerId2InnerModel;

  private String deepxAppType;

  private int isAMFinished;

  public ApplicationContainerListener(ApplicationContext applicationContext, Configuration conf) {
    super(ApplicationContainerListener.class.getSimpleName());
    this.setConfig(conf);
    this.containerId2Role = new ConcurrentHashMap<>();
    this.containerId2Status = new ConcurrentHashMap<>();
    this.reporterProgress = new ConcurrentHashMap<>();
    this.mapedTaskID = new ConcurrentHashMap<>();
    this.containersAppStartTimeMap = new ConcurrentHashMap<>();
    this.containersAppFinishTimeMap = new ConcurrentHashMap<>();
    this.single = conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE);
    this.clusterDef = new ConcurrentHashMap<>();
    this.clusterDef.put(DeepXConstants.WORKER, Collections.synchronizedList(new ArrayList<ContainerHostPair>()));
    this.clusterDef.put(DeepXConstants.PS, Collections.synchronizedList(new ArrayList<ContainerHostPair>()));
    this.clusterDef.put(DeepXConstants.EVALUATOR, Collections.synchronizedList(new ArrayList<ContainerHostPair>()));
    this.clusterDefStr = null;
    this.tfDistributionStrategy = conf.getBoolean(DeepXConfiguration.DEEPX_TF_DISTRIBUTION_STRATEGY, DeepXConfiguration.DEFAULT_DEEPX_TF_DISTRIBUTION_STRATEGY);
    this.tfEvaluator = conf.getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR);
    this.lightGBMIpPortMap = new ConcurrentHashMap<>();
    this.lightGBMIpPortStr = null;
    this.lightLDAIpPortMap = new ConcurrentHashMap<>();
    this.lightLDAIpPortStr = null;
    this.applicationContext = applicationContext;
    this.clock = new SystemClock();
    this.runningContainers = new ConcurrentHashMap<>();
    this.containerTimeOut = conf.getInt(DeepXConfiguration.DEEPX_TASK_TIMEOUT, DeepXConfiguration.DEFAULT_DEEPX_TASK_TIMEOUT);
    this.localResourceTimeOut = conf.getInt(DeepXConfiguration.DEEPX_LOCALRESOURCE_TIMEOUT, DeepXConfiguration.DEFAULT_DEEPX_LOCALRESOURCE_TIMEOUT);
    this.monitorInterval = conf.getInt(DeepXConfiguration.DEEPX_TASK_TIMEOUT_CHECK_INTERVAL_MS, DeepXConfiguration.DEFAULT_DEEPX_TASK_TIMEOUT_CHECK_INTERVAL_MS);
    this.isDeepXTrainFinished = false;
    this.tensorboardUrl = null;
    this.isSaveInnerModel = false;
    this.interResultTimeStamp = Long.MIN_VALUE;
    this.containerId2InnerModel = new ConcurrentHashMap<>();
    this.containersCpuMetrics = new ConcurrentHashMap<>();
    this.containersCpuStatistics = new ConcurrentHashMap<>();
    if (System.getenv().containsKey(DeepXConstants.Environment.DEEPX_APP_TYPE.toString())) {
      deepxAppType = System.getenv(DeepXConstants.Environment.DEEPX_APP_TYPE.toString()).toUpperCase();
    } else {
      deepxAppType = "DEEPX";
    }
    this.isAMFinished = -1;
  }

  @Override
  public void start() {
    LOG.info("Starting application containers handler server");
    RPC.Builder builder = new RPC.Builder(getConfig());
    builder.setProtocol(ApplicationContainerProtocol.class);
    builder.setInstance(this);
    builder.setBindAddress("0.0.0.0");
    builder.setPort(0);
    try {
      server = builder.build();
    } catch (Exception e) {
      LOG.error("Error starting application containers handler server!", e);
      e.printStackTrace();
      return;
    }
    server.start();

    containerTimeoutMonitor = new Thread(new TimeoutMonitor());
    containerTimeoutMonitor.setName("Container-timeout-monitor");
    containerTimeoutMonitor.setDaemon(true);
    containerTimeoutMonitor.start();
    LOG.info("Container timeout monitor thread had started");
  }

  public String getTensorboardUrl() {
    return this.tensorboardUrl;
  }

  public Map<DeepXContainerId, String> getReporterProgress() {
    return this.reporterProgress;
  }

  public Map<DeepXContainerId, String> getContainersAppStartTime() {
    return this.containersAppStartTimeMap;
  }

  public Map<DeepXContainerId, String> getContainersAppFinishTime() {
    return this.containersAppFinishTimeMap;
  }

  public Map<DeepXContainerId, String> getMapedTaskID() {
    return this.mapedTaskID;
  }

  public Map<DeepXContainerId, ConcurrentHashMap<String, LinkedBlockingDeque<Object>>> getContainersCpuMetrics() {
    return this.containersCpuMetrics;
  }

  public Map<DeepXContainerId, ConcurrentHashMap<String, List<Double>>> getContainersCpuStatistics(){
    Map<DeepXContainerId, ConcurrentHashMap<String, List<Double>>> cpuStatistics = new ConcurrentHashMap<>();
    for(DeepXContainerId id: this.containersCpuStatistics.keySet()){
      Map<String, ContainerMetricsStatisticsTuple> statisticsTuple = this.containersCpuStatistics.get(id);
      ConcurrentHashMap<String, List<Double>> statisticsValue = new ConcurrentHashMap<>();
      for (String str: statisticsTuple.keySet()){
        statisticsValue.put(str, statisticsTuple.get(str).getStatisticsInfo());
      }
      cpuStatistics.put(id, statisticsValue);
    }
    return cpuStatistics;
  }

  public int getServerPort() {
    return server.getPort();
  }

  public void setTrainFinished() {
    isDeepXTrainFinished = true;
  }

  public void setAMFinished(int exitcode) {
    isAMFinished = exitcode;
  }

  public void setSaveInnerModel(Boolean isSaveInnerModel) {
    LOG.debug("last saved status:" + this.isSaveInnerModel + "; new saved status:" + isSaveInnerModel);
    if (this.isSaveInnerModel != isSaveInnerModel) {
      this.isSaveInnerModel = isSaveInnerModel;
      if (this.isSaveInnerModel) {
        this.interResultTimeStamp = System.currentTimeMillis();
        try {
          Configuration conf = this.getConfig();
          for (OutputInfo output : this.applicationContext.getOutputs()) {
            Path innerResult = new Path(output.getDfsLocation()
                + conf.get(DeepXConfiguration.DEEPX_INTERREAULST_DIR, DeepXConfiguration.DEFAULT_DEEPX_INTERRESULT_DIR)
                + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date(this.interResultTimeStamp)));
            FileSystem fs = innerResult.getFileSystem(conf);
            fs.mkdirs(innerResult);
            fs.close();
            LOG.info("interResult path:" + innerResult);
          }
        } catch (IOException e) {
          LOG.error("create the interResult path error: " + e);
        }
      }
    }
  }

  public int getInnerSavingContainerNum() {
    return containerId2InnerModel.size();
  }

  public DeepXContainerStatus getContainerStatus(DeepXContainerId deepxContainerId) {
    if (containerId2Status.containsKey(deepxContainerId)) {
      return containerId2Status.get(deepxContainerId);
    }
    return null;
  }

  @Override
  public void registerContainer(DeepXContainerId containerId, String role) {
    containerId2Status.put(containerId, DeepXContainerStatus.UNDEFINED);
    containerId2Role.put(containerId, role);
    reporterProgress.put(containerId, "");
    containersAppStartTimeMap.put(containerId, "");
    containersAppFinishTimeMap.put(containerId, "");
    containersCpuMetrics.put(containerId, new ConcurrentHashMap<String, LinkedBlockingDeque<Object>>());
    containersCpuStatistics.put(containerId, new ConcurrentHashMap<String, ContainerMetricsStatisticsTuple>());
    if (role.equals(DeepXConstants.WORKER) || (role.equals(DeepXConstants.PS) && (deepxAppType.equals("TENSORFLOW") || deepxAppType.equals("LIGHTLDA")))) {
      containerId2InnerModel.put(containerId, new InnerModelSavedPair());
    }
    runningContainers.put(containerId, new LastTime(clock.getTime()));
  }

  @Override
  public boolean isAllPsContainersFinished() {
    if (containerId2Status.isEmpty()) {
      return false;
    }
    for (Entry<DeepXContainerId, DeepXContainerStatus> e : containerId2Status.entrySet()) {
      if (containerId2Role.get(e.getKey()).equals(DeepXConstants.PS)) {
        if (e.getValue().equals(DeepXContainerStatus.UNDEFINED)
            || e.getValue().equals(DeepXContainerStatus.INITIALIZING)
            || e.getValue().equals(DeepXContainerStatus.RUNNING)) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public boolean isTrainCompleted() {
    if (containerId2Status.isEmpty()) {
      return false;
    }

    boolean isCompleted = true;
    Double failedNum = 0.0;

    for (Entry<DeepXContainerId, DeepXContainerStatus> e : containerId2Status.entrySet()) {
      if (e.getValue().equals(DeepXContainerStatus.FAILED)) {
        //LOG.error("Container " + e.getKey().toString() + " run failed!");
        failedNum += 1;
        if (this.getConfig().getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR) && e.getKey().toString().equals(applicationContext.getTfEvaluatorId())) {
          failedNum -= 1;
        }
      } else if (containerId2Role.get(e.getKey()).equals(DeepXConstants.WORKER)) {
        if (e.getValue().equals(DeepXContainerStatus.UNDEFINED)
            || e.getValue().equals(DeepXContainerStatus.INITIALIZING)
            || e.getValue().equals(DeepXContainerStatus.RUNNING)) {
          isCompleted = false;
        }
      }
    }

    if (!this.getConfig().getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE)) {
      if (failedNum > 0) {
        return true;
      }
    } else if ("DISTXGBOOST".equals(deepxAppType)) {
      if (failedNum > 0) {
        return true;
      }
    } else if ("DISTLIGHTGBM".equals(deepxAppType)) {
      if (failedNum > 0) {
        return true;
      }
    } else if ("XFLOW".equals(deepxAppType)) {
      if (failedNum > 0) {
        return true;
      }
    } else if ("MPI".equals(deepxAppType)) {
      if (failedNum > 0) {
        return true;
      }
    } else {
      Double jobFailedNum = containerId2Status.size() * this.getConfig().getDouble(DeepXConfiguration.DEEPX_CONTAINER_MAX_FAILURES_RATE, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_FAILURES_RATE);
      if (failedNum >= jobFailedNum) {
        return true;
      }
    }
    return isCompleted;
  }

  @Override
  public boolean isAllWorkerContainersSucceeded() {
    if (containerId2Status.isEmpty()) {
      return false;
    }
    Double failedNum = 0.0;
    for (Entry<DeepXContainerId, DeepXContainerStatus> e : containerId2Status.entrySet()) {
      if (!e.getValue().equals(DeepXContainerStatus.SUCCEEDED)) {
        failedNum += 1;
        if (this.getConfig().getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR) && e.getKey().toString().equals(applicationContext.getTfEvaluatorId())) {
          failedNum -= 1;
        }
      }
    }
    if (!this.getConfig().getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE)) {
      if (failedNum > 0) {
        return false;
      }
    } else {
      Double jobFailedNum = containerId2Status.size() * this.getConfig().getDouble(DeepXConfiguration.DEEPX_CONTAINER_MAX_FAILURES_RATE, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_FAILURES_RATE);
      if (failedNum >= jobFailedNum) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int isApplicationCompleted() {
    return isAMFinished;
  }

  @Override
  public boolean isAllContainerStarted() throws DeepXExecException {
    Iterator<Entry<DeepXContainerId, DeepXContainerStatus>> i = containerId2Status.entrySet()
        .iterator();
    if (containerId2Status.isEmpty()) {
      return false;
    }
    while (i.hasNext()) {
      Entry<DeepXContainerId, DeepXContainerStatus> e = i.next();
      if (e.getValue().equals(DeepXContainerStatus.FAILED)) {
        throw new ContainerRuntimeException("DeepX Container " + e.getKey().toString() + " run failed!");
      } else if (e.getValue().equals(DeepXContainerStatus.UNDEFINED)
          || e.getValue().equals(DeepXContainerStatus.INITIALIZING)) {
        return false;
      }
    }
    return true;
  }


  @Override
  public int interResultCompletedNum(Long lastInnerModelStr) {
    if (containerId2InnerModel.isEmpty()) {
      return -1;
    }

    int completedNum = 0;
    for (Entry<DeepXContainerId, InnerModelSavedPair> e : containerId2InnerModel.entrySet()) {
      if (e.getValue().getInnerModelTimeStamp().equals(lastInnerModelStr) && e.getValue().getModelSavedStatus()) {
        completedNum++;
      }
    }
    LOG.debug("current interResult saved completed num:" + completedNum);
    return completedNum;
  }

  @Override
  public void reportReservedPort(String host, int port, String role, int index) {
    if (this.clusterDef.keySet().contains(role)) {
      this.clusterDef.get(role).add(new ContainerHostPair(index, host + ":" + port));
      LOG.info("Received reserved port report from " + host + " role is " +
          role + " reserved port is " + port);
    } else {
      LOG.warn("Unknow role " + role + " reported from " + host);
    }
  }

  @Override
  public void reportLightGbmIpPort(DeepXContainerId containerId, String lightGbmIpPort) {
    this.lightGBMIpPortMap.put(containerId, lightGbmIpPort);
    LOG.info("From container " + containerId.toString() + "Received reported lightGBM ip port: " + lightGbmIpPort);
  }

  @Override
  public synchronized String getLightGbmIpPortStr() {
    if (this.lightGBMIpPortMap.size() == applicationContext.getWorkerNum()) {
      LOG.info("Sending lightGBM ip port list \"" + new Gson().toJson(lightGBMIpPortMap) + "\"to container");
      this.lightGBMIpPortStr = new Gson().toJson(lightGBMIpPortMap);
    }
    return this.lightGBMIpPortStr;
  }

  @Override
  public void reportLightLDAIpPort(DeepXContainerId containerId, String lightLDAIpPort) {
    this.lightLDAIpPortMap.put(containerId, lightLDAIpPort);
    LOG.info("From container " + containerId.toString() + " Received reported lightLDA ip port: " + lightLDAIpPort);
  }

  @Override
  public synchronized String getLightLDAIpPortStr() {
    if (this.lightLDAIpPortMap.size() == applicationContext.getPsNum()) {
      LOG.info("Sending lightLDA ip port list \"" + new Gson().toJson(lightLDAIpPortMap) + "\"to container");
      this.lightLDAIpPortStr = new Gson().toJson(lightLDAIpPortMap);
    }
    return this.lightLDAIpPortStr;
  }

  @Override
  public void reportTensorBoardURL(String url) {
    this.tensorboardUrl = url;
    LOG.info("Received reported board url:" + url);
  }

  @Override
  public void reportMapedTaskID(DeepXContainerId containerId, String taskid) {
    this.mapedTaskID.put(containerId, taskid);
    LOG.info("STREAM containerId is:" + containerId.toString() + " taskid is:" + taskid);
  }

  @Override
  public void reportCpuMetrics(DeepXContainerId containerId, String cpuMetrics) {
    if (this.containersCpuMetrics.get(containerId).size() == 0) {
      Gson gson = new GsonBuilder()
          .registerTypeAdapter(
              new TypeToken<ConcurrentHashMap<String, Object>>() {
              }.getType(),
              new JsonDeserializer<ConcurrentHashMap<String, Object>>() {
                @Override
                public ConcurrentHashMap<String, Object> deserialize(
                    JsonElement json, Type typeOfT,
                    JsonDeserializationContext context) throws JsonParseException {

                  ConcurrentHashMap<String, Object> treeMap = new ConcurrentHashMap<>();
                  JsonObject jsonObject = json.getAsJsonObject();
                  Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();
                  for (Map.Entry<String, JsonElement> entry : entrySet) {
                    treeMap.put(entry.getKey(), entry.getValue());
                  }
                  return treeMap;
                }
              }).create();

      Type type = new TypeToken<ConcurrentHashMap<String, Object>>() {
      }.getType();
      ConcurrentHashMap<String, Object> map = gson.fromJson(cpuMetrics, type);
      for (String str : map.keySet()) {
        LinkedBlockingDeque<Object> queue = new LinkedBlockingDeque<>();
        queue.add(map.get(str));
        this.containersCpuMetrics.get(containerId).put(str, queue);
        this.containersCpuStatistics.get(containerId).put(str, new ContainerMetricsStatisticsTuple(Double.parseDouble(new Gson().fromJson((JsonArray)map.get(str), ArrayList.class).get(1).toString())));
      }
    } else {
      Gson gson = new GsonBuilder()
          .registerTypeAdapter(
              new TypeToken<ConcurrentHashMap<String, Object>>() {
              }.getType(),
              new JsonDeserializer<ConcurrentHashMap<String, Object>>() {
                @Override
                public ConcurrentHashMap<String, Object> deserialize(
                    JsonElement json, Type typeOfT,
                    JsonDeserializationContext context) throws JsonParseException {

                  ConcurrentHashMap<String, Object> treeMap = new ConcurrentHashMap<>();
                  JsonObject jsonObject = json.getAsJsonObject();
                  Set<Map.Entry<String, JsonElement>> entrySet = jsonObject.entrySet();
                  for (Map.Entry<String, JsonElement> entry : entrySet) {
                    treeMap.put(entry.getKey(), entry.getValue());
                  }
                  return treeMap;
                }
              }).create();

      Type type = new TypeToken<ConcurrentHashMap<String, Object>>() {
      }.getType();
      ConcurrentHashMap<String, Object> map = gson.fromJson(cpuMetrics, type);
      for (String str : map.keySet()) {
        if (this.containersCpuMetrics.get(containerId).keySet().contains(str)) {
          if (this.containersCpuMetrics.get(containerId).get(str).size() < 1800) {
            this.containersCpuMetrics.get(containerId).get(str).add(map.get(str));
          } else {
            this.containersCpuMetrics.get(containerId).get(str).poll();
            this.containersCpuMetrics.get(containerId).get(str).add(map.get(str));
          }
          this.containersCpuStatistics.get(containerId).get(str).update(Double.parseDouble(new Gson().fromJson((JsonArray)map.get(str), ArrayList.class).get(1).toString()));
        } else {
          LinkedBlockingDeque<Object> queue = new LinkedBlockingDeque<>();
          queue.add(map.get(str));
          this.containersCpuMetrics.get(containerId).put(str, queue);
          this.containersCpuStatistics.get(containerId).put(str, new ContainerMetricsStatisticsTuple(Double.parseDouble(new Gson().fromJson((JsonArray)map.get(str), ArrayList.class).get(1).toString())));
        }
      }
    }
  }

  @Override
  public synchronized Long interResultTimeStamp() {
    return this.interResultTimeStamp;
  }

  @Override
  public synchronized String getClusterDef() {
    if (this.clusterDef.get(DeepXConstants.WORKER.toString()).size() == applicationContext.getWorkerNum()
        && ((!tfDistributionStrategy && this.clusterDef.get(DeepXConstants.PS.toString()).size() == applicationContext.getPsNum())
        || (tfDistributionStrategy
        && (applicationContext.getPsNum() == 0 || (applicationContext.getPsNum() > 0 && this.clusterDef.get(DeepXConstants.PS.toString()).size() == applicationContext.getPsNum()))
        && (!tfEvaluator || (tfEvaluator && this.clusterDef.get(DeepXConstants.EVALUATOR.toString()).size() == 1))))) {
      if (this.clusterDefStr == null) {
        Collections.sort(this.clusterDef.get(DeepXConstants.PS.toString()), new compairIndex());
        Collections.sort(this.clusterDef.get(DeepXConstants.WORKER.toString()), new compairIndex());
        List workerList = new ArrayList<String>();
        for (int i = 0; i < applicationContext.getWorkerNum(); i++) {
          workerList.add(this.clusterDef.get(DeepXConstants.WORKER.toString()).get(i).getHost());
        }
        Map<String, List<String>> clusterMessage = new HashMap<>();
        clusterMessage.put(DeepXConstants.WORKER, workerList);
        if (applicationContext.getPsNum() > 0) {
          List psList = new ArrayList<String>();
          for (int i = 0; i < applicationContext.getPsNum(); i++) {
            psList.add(this.clusterDef.get(DeepXConstants.PS.toString()).get(i).getHost());
          }
          clusterMessage.put(DeepXConstants.PS, psList);
        }
        if (tfDistributionStrategy && tfEvaluator) {
          List evaluatorList = new ArrayList<String>();
          evaluatorList.add(this.clusterDef.get(DeepXConstants.EVALUATOR.toString()).get(0).getHost());
          clusterMessage.put(DeepXConstants.EVALUATOR, evaluatorList);
        }
        LOG.info("Sending cluster def \"" + new Gson().toJson(clusterMessage) + "\"to container");
        this.clusterDefStr = new Gson().toJson(clusterMessage);
      }
    }
    return this.clusterDefStr;
  }

  @Override
  public HeartbeatResponse heartbeat(DeepXContainerId containerId, HeartbeatRequest heartbeatRequest) {
    LastTime lastTime = runningContainers.get(containerId);
    if (lastTime != null) {
      lastTime.setLastTime(clock.getTime());
    }

    DeepXContainerStatus currentContainerStatus = heartbeatRequest.getDeepXContainerStatus();
    LOG.debug("Received heartbeat from container " + containerId.toString() + ", status is " + currentContainerStatus.toString());
    if (containerId2Status.get(containerId) == null || containerId2Status.get(containerId) != currentContainerStatus) {
      try {
        LOG.info("Update container " + containerId.toString() + " status to " + currentContainerStatus);
        containerId2Status.put(containerId, currentContainerStatus);
        if (currentContainerStatus.equals(DeepXContainerStatus.SUCCEEDED) || currentContainerStatus.equals(DeepXContainerStatus.FAILED)) {
          LOG.info("container " + containerId.toString() + " is " + currentContainerStatus + ", now remove from running containers");
          runningContainers.remove(containerId);
          if (containerId2InnerModel.containsKey(containerId)) {
            containerId2InnerModel.remove(containerId);
          }
        }
      } catch (Exception e) {
        LOG.error("Update container " + containerId.toString() + " status failed, ", e);
      }
    }

    if (containerId2Role.get(containerId).equals(DeepXConstants.WORKER)) {
      String localProgressLog = heartbeatRequest.getProgressLog();
      if (!localProgressLog.equals("")) {
        this.reporterProgress.put(containerId, localProgressLog);
        try {
          LOG.debug("container " + containerId + " " + localProgressLog);
        } catch (Exception e) {
          LOG.error("log progress error:" + e);
        }
      }
    }
    String localContainersStartTime = heartbeatRequest.getContainersStartTime();
    if (!localContainersStartTime.equals("")) {
      this.containersAppStartTimeMap.put(containerId, localContainersStartTime);
    }
    String localContainersFinishTime = heartbeatRequest.getContainersFinishTime();
    if (!localContainersFinishTime.equals("")) {
      this.containersAppFinishTimeMap.put(containerId, localContainersFinishTime);
    }

    if (this.isSaveInnerModel) {
      if (containerId2InnerModel.containsKey(containerId)) {
        if (!containerId2InnerModel.get(containerId).getInnerModelTimeStamp().equals(this.interResultTimeStamp)) {
          try {
            LOG.info("Update container " + containerId.toString() + " interResult to " + this.interResultTimeStamp + ", waiting ...");
            containerId2InnerModel.put(containerId, new InnerModelSavedPair(this.interResultTimeStamp, false));
          } catch (Exception e) {
            LOG.error("Update container " + containerId.toString() + " interResult failed, ", e);
          }
        } else {
          if (heartbeatRequest.getInnerModelSavedStatus()) {
            if (!containerId2InnerModel.get(containerId).getModelSavedStatus()) {
              LOG.info("container " + containerId.toString() + "saves the interResult " + this.interResultTimeStamp + " finished.");
              containerId2InnerModel.put(containerId, new InnerModelSavedPair(this.interResultTimeStamp, true));
            }
          }
        }
      }
    }
    return new HeartbeatResponse(isDeepXTrainFinished, this.interResultTimeStamp);
  }

  @Override
  public InputInfo[] getInputSplit(DeepXContainerId containerId) {
    int inputInfoSize = applicationContext.getInputs(containerId).size();
    return applicationContext.getInputs(containerId).toArray(new InputInfo[inputInfoSize]);
  }

  @Override
  public InputSplit[] getStreamInputSplit(DeepXContainerId containerId) {
    int inputSplitSize = applicationContext.getStreamInputs(containerId).size();
    return applicationContext.getStreamInputs(containerId).toArray(new InputSplit[inputSplitSize]);
  }

  @Override
  public OutputInfo[] getOutputLocation() {
    return applicationContext.getOutputs().toArray(new OutputInfo[0]);
  }

  @Override
  public long getProtocolVersion(String protocol, long clientVersion) throws IOException {
    return ApplicationContainerProtocol.versionID;
  }

  @Override
  public ProtocolSignature getProtocolSignature(
      String protocol, long clientVersion, int clientMethodsHash) throws IOException {
    return ProtocolSignature.getProtocolSignature(this, protocol,
        clientVersion, clientMethodsHash);
  }

  private static class LastTime {
    private long lastTime;

    public LastTime(long time) {
      setLastTime(time);
    }

    public synchronized void setLastTime(long time) {
      lastTime = time;
    }

    public synchronized long getLastTime() {
      return lastTime;
    }

  }

  private class TimeoutMonitor implements Runnable {

    @Override
    public void run() {
      while (!Thread.currentThread().isInterrupted()) {
        long currentTime = clock.getTime();
        Set<Entry<DeepXContainerId, LastTime>> entrySet = runningContainers.entrySet();
        for (Entry<DeepXContainerId, LastTime> entry : entrySet) {
          if (containerId2Status.get(entry.getKey()).equals(DeepXContainerStatus.UNDEFINED)) {
            if (currentTime > (entry.getValue().getLastTime() + localResourceTimeOut)) {
              LOG.info("Container " + entry.getKey().toString() + " local resource timed out after "
                  + localResourceTimeOut / 1000 + " seconds");
              HeartbeatRequest heartbeatRequest = new HeartbeatRequest();
              heartbeatRequest.setDeepXContainerStatus(DeepXContainerStatus.FAILED);
              heartbeat(entry.getKey(), heartbeatRequest);
            }
          } else {
            if (currentTime > (entry.getValue().getLastTime() + containerTimeOut)) {
              LOG.info("Container " + entry.getKey().toString() + " timed out after "
                  + containerTimeOut / 1000 + " seconds");
              HeartbeatRequest heartbeatRequest = new HeartbeatRequest();
              heartbeatRequest.setDeepXContainerStatus(DeepXContainerStatus.FAILED);
              heartbeat(entry.getKey(), heartbeatRequest);
            }
          }
        }
        try {
          Thread.sleep(monitorInterval);
        } catch (InterruptedException e) {
          LOG.warn("ContainerHeartbeatHandler thread interrupted");
          break;
        }
      }
    }
  }

  private class ContainerHostPair {
    private int index;
    private String host;

    public ContainerHostPair(int index, String host) {
      setId(index);
      setHost(host);
    }

    public void setId(int index) {
      this.index = index;
    }

    public void setHost(String host) {
      this.host = host;
    }

    public int getId() {
      return this.index;
    }

    public String getHost() {
      return this.host;
    }
  }

  private class compairIndex implements Comparator<ContainerHostPair> {
    @Override
    public int compare(ContainerHostPair a, ContainerHostPair b) {
      Integer p1 = a.getId();
      Integer p2 = b.getId();
      if (p1 < p2)
        return -1;
      else if (p1 == p2)
        return 0;
      else
        return 1;
    }
  }

  private class InnerModelSavedPair {
    private Long interResultTimeStamp = Long.MIN_VALUE;
    private Boolean savedStatus = false;

    public InnerModelSavedPair() {
    }

    public InnerModelSavedPair(Long interResultTimeStamp, Boolean savedStatus) {
      this.interResultTimeStamp = interResultTimeStamp;
      this.savedStatus = savedStatus;
    }

    public Long getInnerModelTimeStamp() {
      return this.interResultTimeStamp;
    }

    public Boolean getModelSavedStatus() {
      return this.savedStatus;
    }

  }

  private class ContainerMetricsStatisticsTuple{
    private Double totalUsed = 0.0;
    private Double maxUsed = 0.0;
    private AtomicLong count = new AtomicLong(0);

    public ContainerMetricsStatisticsTuple(){
    }

    public ContainerMetricsStatisticsTuple(Double resourceUsed){
      totalUsed = resourceUsed;
      maxUsed = resourceUsed;
      count.incrementAndGet();
    }

    public void update(Double resourceUsed){
      totalUsed += resourceUsed;
      count.incrementAndGet();
      if(resourceUsed > maxUsed){
        maxUsed = resourceUsed;
      }
    }

    public List<Double> getStatisticsInfo(){
      List<Double> statisticsInfo = new ArrayList<>();
      statisticsInfo.add(totalUsed / count.get());
      statisticsInfo.add(maxUsed);
      return statisticsInfo;
    }

    public String toString(){
      return totalUsed + "\t"+ count.get() + "\t" + maxUsed;
    }
  }

}
