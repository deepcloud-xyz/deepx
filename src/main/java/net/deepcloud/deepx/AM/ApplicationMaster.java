package net.deepcloud.deepx.AM;

import com.google.gson.Gson;
import net.deepcloud.deepx.api.ApplicationContext;
import net.deepcloud.deepx.api.DeepXConstants;
import net.deepcloud.deepx.common.*;
import net.deepcloud.deepx.common.exceptions.DeepXExecException;
import net.deepcloud.deepx.conf.DeepXConfiguration;
import net.deepcloud.deepx.container.DeepXContainer;
import net.deepcloud.deepx.container.DeepXContainerId;
import net.deepcloud.deepx.util.Utilities;
import net.deepcloud.deepx.webapp.AMParams;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.service.CompositeService;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.RoundingMode;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class ApplicationMaster extends CompositeService {

  private static final Log LOG = LogFactory.getLog(ApplicationMaster.class);
  private final Configuration conf;
  private Map<String, String> envs;
  private AMRMClientAsync<ContainerRequest> amrmAsync;
  private NMClientAsync nmAsync;
  private ApplicationAttemptId applicationAttemptID;
  private String applicationMasterHostname;
  private String applicationMasterTrackingUrl;
  private String applicationHistoryUrl;
  private int workerMemory;
  private int workerVCores;
  private int workerNum;
  private int chiefWorkerMemory;
  private int evaluatorWorkerMemory;
  private int psMemory;
  private int psVCores;
  private int psNum;
  private Boolean single;
  private int appPriority;
  // location of AppMaster.jar on HDFS
  private Path appJarRemoteLocation;
  // location of job.xml on HDFS
  private Path appConfRemoteLocation;
  // location of files on HDFS
  private String appFilesRemoteLocation;
  // location of lib jars on HDFS
  private String appLibJarsRemoteLocation;
  // location of cacheFiles on HDFS
  private String appCacheFilesRemoteLocation;
  // location of cacheArchive on HDFS
  private String appCacheArchivesRemoteLocation;
  private String deepxCommand;
  private String dmlcPsRootUri;
  private int dmlcPsRootPort;
  private String dmlcTrackerUri;
  private int dmlcTrackerPort;
  private String deepxAppType;
  private List<Container> acquiredWorkerContainers;
  private List<Container> acquiredPsContainers;
  private List<Container> acquiredChiefWorkerContainers;
  private List<Container> acquiredEvaluatorWorkerContainers;
  private final LinkedBlockingQueue<Message> applicationMessageQueue;
  private final List<OutputInfo> outputInfos;
  private ConcurrentHashMap<String, List<FileStatus>> input2FileStatus;
  private ConcurrentHashMap<DeepXContainerId, List<InputInfo>> containerId2InputInfo;
  private InputSplit[] inputFileSplits;
  private ConcurrentHashMap<DeepXContainerId, List<InputSplit>> containerId2InputSplit;
  // An RPC Service listening the container status
  private ApplicationContainerListener containerListener;
  private int statusUpdateInterval;
  private final ApplicationContext applicationContext;
  private RMCallbackHandler rmCallbackHandler;
  private ContainerRequest workerContainerRequest;
  private ContainerRequest psContainerRequest;
  private ContainerRequest chiefWorkerContainerRequest;
  private ContainerRequest evaluatorWorkerContainerRequest;
  private Map<String, LocalResource> containerLocalResource;
  private ApplicationWebService webService;
  private ApplicationMessageService messageService;

  private Boolean startSavingModel;
  private Boolean lastSavingStatus;
  private List<Long> savingModelList;

  private Thread cleanApplication;
  private String[] hostLocals;
  private Set<String> containerHostnames;

  private String mpiExecDir;
  private Process mpiExecProcess;
  private String mpiContainerCommand;
  private StringBuilder reLinkFiles;
  private int mpiExitCode;

  private Boolean chiefWorker;
  private String chiefWorkerContainerId;

  private Boolean tfEvaluator;
  private String tfEvaluatorContainerId;
  private StringBuilder inputPath;

  private int outputIndex;

  private int reservePortBegin = 0;
  private int reservePortEnd = 0;

  private String deepxContainerType;

  /**
   * Constructor, connect to Resource Manager
   *
   * @throws IOException
   */
  private ApplicationMaster() {
    super(ApplicationMaster.class.getName());

    conf = new DeepXConfiguration();
    conf.addResource(new Path(DeepXConstants.DEEPX_JOB_CONFIGURATION));
    System.setProperty(DeepXConstants.Environment.HADOOP_USER_NAME.toString(), conf.get("hadoop.job.ugi").split(",")[0]);
    outputInfos = new ArrayList<>();
    input2FileStatus = new ConcurrentHashMap<>();
    containerId2InputInfo = new ConcurrentHashMap<>();
    inputFileSplits = null;
    containerId2InputSplit = new ConcurrentHashMap<>();
    statusUpdateInterval = conf.getInt(DeepXConfiguration.DEEPX_STATUS_UPDATE_INTERVAL, DeepXConfiguration.DEFAULT_DEEPX_STATUS_PULL_INTERVAL);
    applicationAttemptID = Records.newRecord(ApplicationAttemptId.class);
    applicationMessageQueue = new LinkedBlockingQueue<>(
        conf.getInt(DeepXConfiguration.DEEPX_MESSAGES_LEN_MAX, DeepXConfiguration.DEFAULT_DEEPX_MESSAGES_LEN_MAX));
    containerLocalResource = new HashMap<>();
    applicationContext = new RunningAppContext();

    envs = System.getenv();
    workerMemory = conf.getInt(DeepXConfiguration.DEEPX_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    workerVCores = conf.getInt(DeepXConfiguration.DEEPX_WORKER_VCORES, DeepXConfiguration.DEFAULT_DEEPX_WORKER_VCORES);
    workerNum = conf.getInt(DeepXConfiguration.DEEPX_WORKER_NUM, DeepXConfiguration.DEFAULT_DEEPX_WORKER_NUM);
    chiefWorkerMemory = conf.getInt(DeepXConfiguration.DEEPX_CHIEF_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    evaluatorWorkerMemory = conf.getInt(DeepXConfiguration.DEEPX_EVALUATOR_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    if (chiefWorkerMemory != workerMemory) {
      chiefWorker = true;
    } else {
      chiefWorker = false;
    }
    psMemory = conf.getInt(DeepXConfiguration.DEEPX_PS_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_PS_MEMORY);
    psVCores = conf.getInt(DeepXConfiguration.DEEPX_PS_VCORES, DeepXConfiguration.DEFAULT_DEEPX_PS_VCORES);
    psNum = conf.getInt(DeepXConfiguration.DEEPX_PS_NUM, DeepXConfiguration.DEFAULT_DEEPX_PS_NUM);
    single = conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE);
    appPriority = conf.getInt(DeepXConfiguration.DEEPX_APP_PRIORITY, DeepXConfiguration.DEFAULT_DEEPX_APP_PRIORITY);
    deepxContainerType = conf.get(DeepXConfiguration.DEEPX_CONTAINER_TYPE, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_TYPE);
    acquiredWorkerContainers = new ArrayList<>();
    acquiredPsContainers = new ArrayList<>();
    acquiredChiefWorkerContainers = new ArrayList<>();
    acquiredEvaluatorWorkerContainers = new ArrayList<>();
    dmlcPsRootUri = null;
    dmlcPsRootPort = 0;
    dmlcTrackerUri = null;
    dmlcTrackerPort = 0;
    containerHostnames = null;
    hostLocals = null;
    reLinkFiles = new StringBuilder();
    tfEvaluator = conf.getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR);
    tfEvaluatorContainerId = "";
    chiefWorkerContainerId = "";
    inputPath = new StringBuilder();
    outputIndex = -1;
    this.reservePortBegin = this.conf.getInt(DeepXConfiguration.DEEPX_RESERVE_PORT_BEGIN,
        DeepXConfiguration.DEFAULT_DEEPX_RESERVE_PORT_BEGIN);
    this.reservePortEnd = this.conf.getInt(DeepXConfiguration.DEEPX_RESERVE_PORT_END,
        DeepXConfiguration.DEFAULT_DEEPX_RESERVE_PORT_END);

    if (envs.containsKey(ApplicationConstants.Environment.CONTAINER_ID.toString())) {
      ContainerId containerId = ConverterUtils
          .toContainerId(envs.get(ApplicationConstants.Environment.CONTAINER_ID.toString()));
      applicationAttemptID = containerId.getApplicationAttemptId();
    } else {
      throw new IllegalArgumentException(
          "Application Attempt Id is not available in environment");
    }

    LOG.info("Application appId="
        + applicationAttemptID.getApplicationId().getId()
        + ", clustertimestamp="
        + applicationAttemptID.getApplicationId().getClusterTimestamp()
        + ", attemptId=" + applicationAttemptID.getAttemptId());

    if (applicationAttemptID.getAttemptId() > 1 && (conf.getInt(DeepXConfiguration.DEEPX_APP_MAX_ATTEMPTS, DeepXConfiguration.DEFAULT_DEEPX_APP_MAX_ATTEMPTS) > 1)) {
      int maxMem = Integer.valueOf(envs.get(DeepXConstants.Environment.DEEPX_CONTAINER_MAX_MEMORY.toString()));
      LOG.info("maxMem : " + maxMem);
      workerMemory = workerMemory + (applicationAttemptID.getAttemptId() - 1) * (int) Math.ceil(workerMemory * conf.getDouble(DeepXConfiguration.DEEPX_WORKER_MEM_AUTO_SCALE, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEM_AUTO_SCALE));
      LOG.info("Auto Scale the Worker Memory from " + conf.getInt(DeepXConfiguration.DEEPX_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY) + " to " + workerMemory);
      if (workerMemory > maxMem) {
        workerMemory = maxMem;
      }
      if (psNum > 0) {
        psMemory = psMemory + (applicationAttemptID.getAttemptId() - 1) * (int) Math.ceil(psMemory * conf.getDouble(DeepXConfiguration.DEEPX_PS_MEM_AUTO_SCALE, DeepXConfiguration.DEFAULT_DEEPX_PS_MEM_AUTO_SCALE));
        LOG.info("Auto Scale the Ps Memory from " + conf.getInt(DeepXConfiguration.DEEPX_PS_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_PS_MEMORY) + " to " + psMemory);
        if (psMemory > maxMem) {
          psMemory = maxMem;
        }
      }
    }

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_FILES_LOCATION.toString())) {
      appFilesRemoteLocation = envs.get(DeepXConstants.Environment.DEEPX_FILES_LOCATION.toString());
      LOG.info("Application files location: " + appFilesRemoteLocation);
    }

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_LIBJARS_LOCATION.toString())) {
      appLibJarsRemoteLocation = envs.get(DeepXConstants.Environment.DEEPX_LIBJARS_LOCATION.toString());
      LOG.info("Application lib Jars location: " + appLibJarsRemoteLocation);
    }

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_CACHE_FILE_LOCATION.toString())) {
      appCacheFilesRemoteLocation = envs.get(DeepXConstants.Environment.DEEPX_CACHE_FILE_LOCATION.toString());
      LOG.info("Application cacheFiles location: " + appCacheFilesRemoteLocation);
    }

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_CACHE_ARCHIVE_LOCATION.toString())) {
      appCacheArchivesRemoteLocation = envs.get(DeepXConstants.Environment.DEEPX_CACHE_ARCHIVE_LOCATION.toString());
      LOG.info("Application cacheArchive location: " + appCacheArchivesRemoteLocation);
    }

    assert (envs.containsKey(DeepXConstants.Environment.APP_JAR_LOCATION.toString()));
    appJarRemoteLocation = new Path(envs.get(DeepXConstants.Environment.APP_JAR_LOCATION.toString()));
    LOG.info("Application jar location: " + appJarRemoteLocation);

    assert (envs.containsKey(DeepXConstants.Environment.DEEPX_JOB_CONF_LOCATION.toString()));
    appConfRemoteLocation = new Path(envs.get(DeepXConstants.Environment.DEEPX_JOB_CONF_LOCATION.toString()));
    LOG.info("Application conf location: " + appConfRemoteLocation);

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_EXEC_CMD.toString())) {
      deepxCommand = envs.get(DeepXConstants.Environment.DEEPX_EXEC_CMD.toString());
      LOG.info("DeepX exec command: " + deepxCommand);
    }

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_APP_TYPE.toString())) {
      deepxAppType = envs.get(DeepXConstants.Environment.DEEPX_APP_TYPE.toString()).toUpperCase();
      LOG.info("DeepX app type: " + deepxAppType);
    } else {
      deepxAppType = DeepXConfiguration.DEFAULT_DEEPX_APP_TYPE.toUpperCase();
      LOG.info("DeepX app type: " + deepxAppType);
    }

    if (deepxAppType.equals("MPI")) {
      Path pwd = new Path(envs.get("PWD"));
      mpiExecDir = pwd.getParent().toString();
      LOG.info("MPI exec path: " + mpiExecDir);
    }

    if (envs.containsKey(ApplicationConstants.Environment.NM_HOST.toString())) {
      applicationMasterHostname = envs.get(ApplicationConstants.Environment.NM_HOST.toString());
    }

    this.messageService = new ApplicationMessageService(this.applicationContext, conf);
    this.webService = new ApplicationWebService(this.applicationContext, conf);
    this.containerListener = new ApplicationContainerListener(applicationContext, conf);

    this.startSavingModel = false;
    this.lastSavingStatus = false;
    this.savingModelList = new ArrayList<>();
  }

  private void init() {
    appendMessage(new Message(LogType.STDERR, "ApplicationMaster starting services"));

    this.rmCallbackHandler = new RMCallbackHandler();
    this.amrmAsync = AMRMClientAsync.createAMRMClientAsync(1000, rmCallbackHandler);
    this.amrmAsync.init(conf);

    NMCallbackHandler nmAsyncHandler = new NMCallbackHandler();
    this.nmAsync = NMClientAsync.createNMClientAsync(nmAsyncHandler);
    this.nmAsync.init(conf);

    addService(this.amrmAsync);
    addService(this.nmAsync);
    addService(this.messageService);
    addService(this.webService);
    addService(this.containerListener);
    try {
      super.serviceStart();
    } catch (Exception e) {
      throw new RuntimeException("Error start application services!", e);
    }

    applicationMasterTrackingUrl = applicationMasterHostname + ":" + this.webService.getHttpPort();
    applicationHistoryUrl = conf.get(DeepXConfiguration.DEEPX_HISTORY_WEBAPP_ADDRESS,
        DeepXConfiguration.DEFAULT_DEEPX_HISTORY_WEBAPP_ADDRESS) + "/jobhistory/job/"
        + applicationAttemptID.getApplicationId();
    LOG.info("master tracking url:" + applicationMasterTrackingUrl);
    LOG.info("history url: " + applicationHistoryUrl);

    cleanApplication = new Thread(new Runnable() {
      @Override
      public void run() {
        System.clearProperty(DeepXConstants.Environment.HADOOP_USER_NAME.toString());
        YarnConfiguration deepxConf = new YarnConfiguration();
        if (deepxConf.getBoolean(DeepXConfiguration.DEEPX_CLEANUP_ENABLE, DeepXConfiguration.DEFAULT_DEEPX_CLEANUP_ENABLE)) {
          Path stagingDir = new Path(envs.get(DeepXConstants.Environment.DEEPX_STAGING_LOCATION.toString()));
          try {
            stagingDir.getFileSystem(deepxConf).delete(stagingDir);
            LOG.info("Deleting the staging file successed.");
          } catch (Exception e) {
            LOG.error("Deleting the staging file Error." + e);
          }
        }

        try {
          FsPermission LOG_FILE_PERMISSION = FsPermission.createImmutable((short) 0777);
          Path logdir = new Path(conf.get(DeepXConfiguration.DEEPX_HISTORY_LOG_DIR,
              DeepXConfiguration.DEFAULT_DEEPX_HISTORY_LOG_DIR) + "/" + applicationAttemptID.getApplicationId().toString()
              + "/" + applicationAttemptID.getApplicationId().toString());
          Path jobLogPath = new Path(deepxConf.get("fs.defaultFS"), logdir);
          LOG.info("jobLogPath:" + jobLogPath.toString());
          LOG.info("Start write the log to " + jobLogPath.toString());
          FileSystem fs = FileSystem.get(deepxConf);
          FSDataOutputStream out = fs.create(jobLogPath);
          fs.setPermission(jobLogPath, new FsPermission(LOG_FILE_PERMISSION));
          if (conf.getBoolean(DeepXConfiguration.DEEPX_HOST_LOCAL_ENABLE, DeepXConfiguration.DEFAULT_DEEPX_HOST_LOCAL_ENABLE)) {
            Path hostLocaldir = new Path(conf.get(DeepXConfiguration.DEEPX_HISTORY_LOG_DIR,
                DeepXConfiguration.DEFAULT_DEEPX_HISTORY_LOG_DIR) + "/" + conf.get("hadoop.job.ugi").split(",")[0]
                + "/" + envs.get(DeepXConstants.Environment.DEEPX_APP_NAME.toString()));
            Path hostLocalPath = new Path(deepxConf.get("fs.defaultFS"), hostLocaldir);
            try {
              FSDataOutputStream hostLocalOut = fs.create(hostLocalPath);
              fs.setPermission(hostLocalPath, new FsPermission(LOG_FILE_PERMISSION));
              hostLocalOut.writeBytes(containerHostnames.toString().substring(1, containerHostnames.toString().length() - 1));
              hostLocalOut.close();
              LOG.info("host local enable is true, write " + hostLocalPath.toString() + " success");
            } catch (Exception e) {
              LOG.info("write host local file error, " + e);
            }
          }

          Map<String, Object> logMessage = new HashMap<>();
          logMessage.put(AMParams.APP_TYPE, deepxAppType);

          String tensorboardInfo = "-";
          if (conf.getBoolean(DeepXConfiguration.DEEPX_TF_BOARD_ENABLE, DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_ENABLE)) {
            Path boardLogPath;
            if (conf.get(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR, DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_LOG_DIR).indexOf("hdfs://") == -1) {
              if (conf.get(DeepXConfiguration.DEEPX_TF_BOARD_HISTORY_DIR, DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_HISTORY_DIR).equals(deepxConf.get(DeepXConfiguration.DEEPX_TF_BOARD_HISTORY_DIR, DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_HISTORY_DIR))) {
                boardLogPath = new Path(deepxConf.get("fs.defaultFS"), conf.get(DeepXConfiguration.DEEPX_TF_BOARD_HISTORY_DIR,
                    DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_HISTORY_DIR) + "/" + applicationAttemptID.getApplicationId().toString());
              } else {
                boardLogPath = new Path(conf.get("fs.defaultFS"), conf.get(DeepXConfiguration.DEEPX_TF_BOARD_HISTORY_DIR,
                    DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_HISTORY_DIR));
              }
            } else {
              boardLogPath = new Path(conf.get(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR));
            }
            tensorboardInfo = boardLogPath.toString();
          }
          logMessage.put(AMParams.BOARD_INFO, tensorboardInfo);

          String userName = StringUtils.split(conf.get("hadoop.job.ugi"), ',')[0];
          List<Container> workerContainers = applicationContext.getWorkerContainers();
          List<Container> psContainers = applicationContext.getPsContainers();
          Map<DeepXContainerId, String> reporterProgress = applicationContext.getReporterProgress();
          Map<DeepXContainerId, String> containersAppStartTime = applicationContext.getContainersAppStartTime();
          Map<DeepXContainerId, String> containersAppFinishTime = applicationContext.getContainersAppFinishTime();
          for (Container container : workerContainers) {
            Map<String, String> containerMessage = new HashMap<>();
            containerMessage.put(AMParams.CONTAINER_HTTP_ADDRESS, container.getNodeHttpAddress());
            if (tfEvaluator && container.getId().toString().equals(tfEvaluatorContainerId)) {
              containerMessage.put(AMParams.CONTAINER_ROLE, DeepXConstants.EVALUATOR);
            } else if (chiefWorker && container.getId().toString().equals(chiefWorkerContainerId)) {
              containerMessage.put(AMParams.CONTAINER_ROLE, DeepXConstants.CHIEF);
            } else {
              containerMessage.put(AMParams.CONTAINER_ROLE, DeepXConstants.WORKER);
            }
            DeepXContainerStatus status = applicationContext.getContainerStatus(new DeepXContainerId(container.getId()));
            if (status != null) {
              containerMessage.put(AMParams.CONTAINER_STATUS, status.toString());
            } else {
              containerMessage.put(AMParams.CONTAINER_STATUS, "-");
            }
            if (containersAppStartTime.get(new DeepXContainerId(container.getId())) != null && !containersAppStartTime.get(new DeepXContainerId(container.getId())).equals("")) {
              String localStartTime = containersAppStartTime.get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_START_TIME, localStartTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_START_TIME, "N/A");
            }
            if (containersAppFinishTime.get(new DeepXContainerId(container.getId())) != null && !containersAppFinishTime.get(new DeepXContainerId(container.getId())).equals("")) {
              String localFinishTime = containersAppFinishTime.get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, localFinishTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, "N/A");
            }

            if (applicationContext.getContainersCpuMetrics().get(new DeepXContainerId(container.getId())) != null) {
              ConcurrentHashMap<String, LinkedBlockingDeque<Object>> cpuMetrics = applicationContext.getContainersCpuMetrics().get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_CPU_METRICS, new Gson().toJson(cpuMetrics));
            }

            if (applicationContext.getContainersCpuStatistics().get(new DeepXContainerId(container.getId())) != null) {
              ConcurrentHashMap<String, List<Double>> cpuStatistics = applicationContext.getContainersCpuStatistics().get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_CPU_STATISTICS, new Gson().toJson(cpuStatistics));
              if (cpuStatistics.size() != 0) {
                Double cpuMemUsagedMax = cpuStatistics.get("CPUMEM").get(1);
                int currentWorkerMemory = workerMemory;
                if (chiefWorker && container.getId().toString().equals(chiefWorkerContainerId)) {
                  currentWorkerMemory = chiefWorkerMemory;
                }
                if (tfEvaluator && container.getId().toString().equals(tfEvaluatorContainerId)) {
                  currentWorkerMemory = evaluatorWorkerMemory;
                }
                if (status != null && status.toString().equalsIgnoreCase("SUCCEEDED") && cpuMemUsagedMax * 1024.0 / currentWorkerMemory < conf.getDouble(DeepXConfiguration.DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION)) {
                  containerMessage.put(AMParams.CONTAINER_CPU_USAGE_WARN_MEM, "true");
                } else {
                  containerMessage.put(AMParams.CONTAINER_CPU_USAGE_WARN_MEM, "false");
                }
              }
            }

            if (reporterProgress.get(new DeepXContainerId(container.getId())) != null && !reporterProgress.get(new DeepXContainerId(container.getId())).equals("")) {
              String progressLog = reporterProgress.get(new DeepXContainerId(container.getId()));
              String[] progress = progressLog.toString().split(":");
              if (progress.length != 2) {
                containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
              } else {
                try {
                  Float percentProgress = Float.parseFloat(progress[1]);
                  if (percentProgress < 0.0 || percentProgress > 1.0) {
                    containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
                  } else {
                    DecimalFormat df = new DecimalFormat("0.00");
                    df.setRoundingMode(RoundingMode.HALF_UP);
                    containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, df.format((Float.parseFloat(progress[1]) * 100)) + "%");
                  }
                } catch (Exception e) {
                  containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "progress log format error");
                }
              }
            } else {
              containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "0.00%");
            }
            containerMessage.put(AMParams.CONTAINER_LOG_ADDRESS, String.format("http://%s/node/containerlogs/%s/%s",
                container.getNodeHttpAddress(),
                container.getId().toString(),
                userName));
            logMessage.put(container.getId().toString(), containerMessage);
          }

          for (Container container : psContainers) {
            Map<String, String> containerMessage = new HashMap<>();
            containerMessage.put(AMParams.CONTAINER_HTTP_ADDRESS, container.getNodeHttpAddress());
            if (deepxAppType.equals("TENSORFLOW")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "ps");
            } else if (deepxAppType.equals("MXNET")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "server");
            } else if (deepxAppType.equals("LIGHTLDA")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "server");
            } else if (deepxAppType.equals("XFLOW")) {
              containerMessage.put(AMParams.CONTAINER_ROLE, "server");
            }

            DeepXContainerStatus status = applicationContext.getContainerStatus(new DeepXContainerId(container.getId()));
            if (status != null) {
              containerMessage.put(AMParams.CONTAINER_STATUS, status.toString());
            } else {
              containerMessage.put(AMParams.CONTAINER_STATUS, "-");
            }

            if (containersAppStartTime.get(new DeepXContainerId(container.getId())) != null && !containersAppStartTime.get(new DeepXContainerId(container.getId())).equals("")) {
              String localStartTime = containersAppStartTime.get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_START_TIME, localStartTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_START_TIME, "N/A");
            }
            if (containersAppFinishTime.get(new DeepXContainerId(container.getId())) != null && !containersAppFinishTime.get(new DeepXContainerId(container.getId())).equals("")) {
              String localFinishTime = containersAppFinishTime.get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, localFinishTime);
            } else {
              containerMessage.put(AMParams.CONTAINER_FINISH_TIME, "N/A");
            }

            if (applicationContext.getContainersCpuMetrics().get(new DeepXContainerId(container.getId())) != null) {
              ConcurrentHashMap<String, LinkedBlockingDeque<Object>> cpuMetrics = applicationContext.getContainersCpuMetrics().get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_CPU_METRICS, new Gson().toJson(cpuMetrics));
            }

            if (applicationContext.getContainersCpuStatistics().get(new DeepXContainerId(container.getId())) != null) {
              ConcurrentHashMap<String, List<Double>> cpuStatistics = applicationContext.getContainersCpuStatistics().get(new DeepXContainerId(container.getId()));
              containerMessage.put(AMParams.CONTAINER_CPU_STATISTICS, new Gson().toJson(cpuStatistics));
              if (cpuStatistics.size() != 0) {
                Double cpuMemUsagedMax = cpuStatistics.get("CPUMEM").get(1);
                if (status != null && status.toString().equalsIgnoreCase("SUCCEEDED") && cpuMemUsagedMax * 1024.0 / workerMemory < conf.getDouble(DeepXConfiguration.DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION)) {
                  containerMessage.put(AMParams.CONTAINER_CPU_USAGE_WARN_MEM, "true");
                } else {
                  containerMessage.put(AMParams.CONTAINER_CPU_USAGE_WARN_MEM, "false");
                }
              }
            }

            containerMessage.put(AMParams.CONTAINER_REPORTER_PROGRESS, "0.00%");
            containerMessage.put(AMParams.CONTAINER_LOG_ADDRESS, String.format("http://%s/node/containerlogs/%s/%s",
                container.getNodeHttpAddress(),
                container.getId().toString(),
                userName));
            logMessage.put(container.getId().toString(), containerMessage);
          }

          List<String> savedTimeStamp = new ArrayList<>();
          List<String> outputList = new ArrayList<>();
          if (applicationContext.getOutputs().size() == 0) {
            outputList.add("-");
            savedTimeStamp.add("-");
          } else {
            for (OutputInfo output : applicationContext.getOutputs()) {
              outputList.add(output.getDfsLocation());
            }
            if (applicationContext.getModelSavingList().size() == 0) {
              savedTimeStamp.add("-");
            } else {
              for (int i = applicationContext.getModelSavingList().size(); i > 0; i--) {
                savedTimeStamp.add(String.valueOf(applicationContext.getModelSavingList().get(i - 1)));
              }
            }
          }
          logMessage.put(AMParams.TIMESTAMP_LIST, savedTimeStamp);
          logMessage.put(AMParams.OUTPUT_PATH, outputList);
          logMessage.put(AMParams.WORKER_NUMBER, String.valueOf(workerNum));
          logMessage.put(AMParams.PS_NUMBER, String.valueOf(psNum));
          logMessage.put(AMParams.WORKER_VCORES, String.valueOf(workerVCores));
          logMessage.put(AMParams.PS_VCORES, String.valueOf(psVCores));
          logMessage.put(AMParams.WORKER_MEMORY, String.format("%.2f", workerMemory / 1024.0));
          logMessage.put(AMParams.PS_MEMORY, String.format("%.2f", psMemory / 1024.0));
          if (chiefWorker) {
            logMessage.put(AMParams.CHIEF_WORKER_MEMORY, String.format("%.2f", chiefWorkerMemory / 1024.0));
          }
          if (tfEvaluator) {
            logMessage.put(AMParams.EVALUATOR_WORKER_MEMORY, String.format("%.2f", evaluatorWorkerMemory / 1024.0));
          }

          out.writeBytes(new Gson().toJson(logMessage));
          out.close();
          fs.close();
          LOG.info("Writing the history log file successed.");
        } catch (Exception e) {
          LOG.error("Writing the history log file Error." + e);
        }
      }
    });
    Runtime.getRuntime().addShutdownHook(cleanApplication);
  }

  private void buildInputFileStatus() {
    String deepxInputs = envs.get(DeepXConstants.Environment.DEEPX_INPUTS.toString());
    if (StringUtils.isBlank(deepxInputs)) {
      LOG.info("Application has no inputs");
      return;
    }

    String[] inputs = StringUtils.split(deepxInputs, "|");
    if (inputs != null && inputs.length > 0) {
      for (String input : inputs) {
        String[] inputPathTuple = StringUtils.split(input, "#");
        if (inputPathTuple.length < 2) {
          throw new RuntimeException("Error input path format " + deepxInputs);
        }
        List<FileStatus> fileStatus = new ArrayList<>();
        String inputPathRemote = inputPathTuple[0];
        if (!StringUtils.isBlank(inputPathRemote)) {
          try {
            for (String singlePath : StringUtils.split(inputPathRemote, ",")) {
              Path inputPathTotal = new Path(singlePath);
              FileSystem inputFs = inputPathTotal.getFileSystem(conf);
              FileStatus[] inputStatus = inputFs.globStatus(inputPathTotal);
              for (Path inputPath : FileUtil.stat2Paths(inputStatus)) {
                inputPath = inputFs.makeQualified(inputPath);
                List<FileStatus> downLoadFile = Utilities.listStatusRecursively(inputPath,
                    inputFs, null, Integer.MAX_VALUE);
                fileStatus.addAll(downLoadFile);
              }
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
          input2FileStatus.put(inputPathTuple[1], fileStatus);
          this.inputPath.append(inputPathTuple[1]).append(",");
          if (fileStatus.size() > 0) {
            if (!tfEvaluator) {
              if (fileStatus.size() < workerNum) {
                workerNum = fileStatus.size();
                LOG.warn("File count in  " + inputPathRemote + "  " + fileStatus.size() +
                    " less than the worker count " + workerNum);
              }
            } else {
              if (fileStatus.size() < (workerNum - 1)) {
                workerNum = fileStatus.size() + 1;
                LOG.warn("File count in  " + inputPathRemote + "  " + fileStatus.size() +
                    " less than the worker count " + workerNum + " including that the last worker is the evaluator.");
              }
            }
          }
        } else {
          throw new RuntimeException("Error input path format " + deepxInputs);
        }
      }
    }
  }

  public void buildInputStreamFileStatus() throws IOException {
    String deepxInputs = envs.get(DeepXConstants.Environment.DEEPX_INPUTS.toString());
    if (StringUtils.isBlank(deepxInputs)) {
      LOG.info("Application has no inputs");
      return;
    }

    String[] inputPathTuple = StringUtils.split(deepxInputs, "#");
    if (inputPathTuple.length < 2) {
      throw new RuntimeException("Error input path format " + deepxInputs);
    }
    String inputPathRemote = inputPathTuple[0];
    if (!StringUtils.isBlank(inputPathRemote)) {
      JobConf jobConf = new JobConf(conf);
      jobConf.set(DeepXConstants.STREAM_INPUT_DIR, inputPathRemote);
      InputFormat inputFormat = ReflectionUtils.newInstance(conf.getClass(DeepXConfiguration.DEEPX_INPUTF0RMAT_CLASS, DeepXConfiguration.DEFAULT_DEEPX_INPUTF0RMAT_CLASS, InputFormat.class),
          jobConf);
      inputFileSplits = inputFormat.getSplits(jobConf, 1);
    } else {
      throw new RuntimeException("Error input path format " + deepxInputs);
    }
  }

  @SuppressWarnings("deprecation")
  private void allocateInputSplits() {

    for (Container container : acquiredWorkerContainers) {
      LOG.info("Initializing " + container.getId().toString() + " input splits");
      containerId2InputInfo.putIfAbsent(new DeepXContainerId(container.getId()), new ArrayList<InputInfo>());
    }
    Set<String> fileKeys = input2FileStatus.keySet();
    int splitWorkerNum = workerNum;
    if (tfEvaluator) {
      splitWorkerNum--;
      LOG.info("Note that current TensorFlow job has the evaluator type. Not allocate the input to the last container.");
    }
    for (String fileName : fileKeys) {
      List<FileStatus> files = input2FileStatus.get(fileName);
      List<Path> paths = Utilities.convertStatusToPath(files);
      ConcurrentHashMap<DeepXContainerId, ConcurrentHashMap<String, InputInfo>> containersFiles = new ConcurrentHashMap<>();
      for (int i = 0, len = paths.size(); i < len; i++) {
        Integer index = i % splitWorkerNum;
        ConcurrentHashMap<String, InputInfo> mapSplit;
        DeepXContainerId containerId = new DeepXContainerId(acquiredWorkerContainers.get(index).getId());
        if (containersFiles.containsKey(containerId)) {
          mapSplit = containersFiles.get(containerId);
        } else {
          mapSplit = new ConcurrentHashMap<>();
          containersFiles.put(containerId, mapSplit);
        }
        if (mapSplit.containsKey(fileName)) {
          mapSplit.get(fileName).addPath(paths.get(i));
        } else {
          InputInfo inputInfo = new InputInfo();
          inputInfo.setAliasName(fileName);
          List<Path> ps = new ArrayList<>();
          ps.add(paths.get(i));
          inputInfo.setPaths(ps);
          mapSplit.put(fileName, inputInfo);
        }
      }
      Set<DeepXContainerId> containerIdSet = containersFiles.keySet();
      for (DeepXContainerId containerId : containerIdSet) {
        containerId2InputInfo.get(containerId).add(containersFiles.get(containerId).get(fileName));
        LOG.info("put " + fileName + " to " + containerId.toString());
      }
    }
    LOG.info("inputInfo " + new Gson().toJson(containerId2InputInfo));
  }

  private void allocateInputStreamSplits() {

    for (Container container : acquiredWorkerContainers) {
      LOG.info("Initializing " + container.getId().toString() + " input splits");
      containerId2InputSplit.putIfAbsent(new DeepXContainerId(container.getId()), new ArrayList<InputSplit>());
    }
    int splitWorkerNum = workerNum;
    if (tfEvaluator) {
      splitWorkerNum--;
      LOG.info("Note that current TensorFlow job has the evaluator type. Not allocate the input to the last container.");
    }
    if (conf.getBoolean(DeepXConfiguration.DEEPX_INPUT_STREAM_SHUFFLE, DeepXConfiguration.DEFAULT_DEEPX_INPUT_STREAM_SHUFFLE)) {
      LOG.info("DEEPX_INPUT_STREAM_SHUFFLE is true");
      for (int i = 0, len = inputFileSplits.length; i < len; i++) {
        Integer index = i % splitWorkerNum;
        DeepXContainerId containerId = new DeepXContainerId(acquiredWorkerContainers.get(index).getId());
        containerId2InputSplit.get(containerId).add(inputFileSplits[i]);
        LOG.info("put split " + (i + 1) + " to " + containerId.toString());
      }
    } else {
      LOG.info("DEEPX_INPUT_STREAM_SHUFFLE is false");
      int nsplit = inputFileSplits.length / splitWorkerNum;
      int msplit = inputFileSplits.length % splitWorkerNum;
      int count = 0;
      for (int i = 0; i < splitWorkerNum; i++) {
        DeepXContainerId containerId = new DeepXContainerId(acquiredWorkerContainers.get(i).getId());
        for (int j = 0; j < nsplit; j++) {
          containerId2InputSplit.get(containerId).add(inputFileSplits[count++]);
          LOG.info("put split " + count + " to " + containerId.toString());
        }
        if (msplit > 0) {
          containerId2InputSplit.get(containerId).add(inputFileSplits[count++]);
          LOG.info("put split " + count + " to " + containerId.toString());
          msplit--;
        }
      }
    }
  }

  private void buildOutputLocations() {
    String deepxOutputs = envs.get(DeepXConstants.Environment.DEEPX_OUTPUTS.toString());
    if (StringUtils.isBlank(deepxOutputs)) {
      return;
    }
    String[] outputs = StringUtils.split(deepxOutputs, "|");
    if (outputs != null && outputs.length > 0) {
      for (String output : outputs) {
        String outputPathTuple[] = StringUtils.split(output, "#");
        if (outputPathTuple.length < 2) {
          throw new RuntimeException("Error input path format " + deepxOutputs);
        }
        String pathRemote = outputPathTuple[0];
        OutputInfo outputInfo = new OutputInfo();
        outputInfo.setDfsLocation(pathRemote);
        String pathLocal;
        if ("MPI".equals(deepxAppType)) {
          pathLocal = mpiExecDir + File.separator + outputPathTuple[1];
        } else {
          pathLocal = outputPathTuple[1];
        }
        outputInfo.setLocalLocation(pathLocal);
        outputInfos.add(outputInfo);
        LOG.info("Application output " + pathRemote + "#" + pathLocal);
      }
    } else {
      throw new RuntimeException("Error output path format " + deepxOutputs);
    }
  }

  private void registerApplicationMaster() {
    try {
      amrmAsync.registerApplicationMaster(this.messageService.getServerAddress().getHostName(),
          this.messageService.getServerAddress().getPort(), applicationMasterTrackingUrl);
    } catch (Exception e) {
      throw new RuntimeException("Registering application master failed,", e);
    }
  }

  private void buildContainerRequest(String[] hostLocals) {
    if (conf.getBoolean(DeepXConfiguration.DEEPX_HOST_LOCAL_ENABLE, DeepXConfiguration.DEFAULT_DEEPX_HOST_LOCAL_ENABLE)) {
      DeepXConfiguration xlConf = new DeepXConfiguration();
      String hostLocaldir = xlConf.get("fs.defaultFS") + conf.get(DeepXConfiguration.DEEPX_HISTORY_LOG_DIR,
          DeepXConfiguration.DEFAULT_DEEPX_HISTORY_LOG_DIR) + "/" + conf.get("hadoop.job.ugi").split(",")[0]
          + "/" + envs.get(DeepXConstants.Environment.DEEPX_APP_NAME.toString());
      Path hostLocalPath = new Path(hostLocaldir);
      String line;
      try {
        if (hostLocalPath.getFileSystem(xlConf).exists(hostLocalPath)) {
          FSDataInputStream in = hostLocalPath.getFileSystem(xlConf).open(hostLocalPath);
          BufferedReader br = new BufferedReader(new InputStreamReader(in));
          line = br.readLine();
          hostLocals = line.split(",");
          LOG.info("now in buildContainerRequest, host local is: " + Arrays.toString(hostLocals));
          in.close();
        }
      } catch (IOException e) {
        LOG.info("open and read the host local from " + hostLocalPath + " error, " + e);
      }
    }

    String workerNodeLabelExpression = conf.get(DeepXConfiguration.DEEPX_WORKER_NODELABELEXPRESSION);
    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(appPriority);
    Resource workerCapability = Records.newRecord(Resource.class);
    workerCapability.setMemory(workerMemory);
    workerCapability.setVirtualCores(workerVCores);
    if (workerNodeLabelExpression != null && workerNodeLabelExpression.trim() != "") {
      try {
        workerContainerRequest = ContainerRequest.class.getConstructor(Resource.class, String[].class, String[].class, Priority.class, boolean.class, String.class).newInstance(workerCapability, hostLocals, null, priority, true, workerNodeLabelExpression);
      } catch (Exception e) {
        workerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
        LOG.warn("Set worker node label expression error:" + e);
      }
    } else {
      workerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
    }
    LOG.info("Create worker container request: " + workerContainerRequest.toString());


    if ("TENSORFLOW".equals(deepxAppType) && workerNum > 1) {
      if (chiefWorker) {
        Resource chiefWorkerCapability = Records.newRecord(Resource.class);
        chiefWorkerCapability.setMemory(chiefWorkerMemory);
        chiefWorkerCapability.setVirtualCores(workerVCores);
        if (workerNodeLabelExpression != null && workerNodeLabelExpression.trim() != "") {
          try {
            chiefWorkerContainerRequest = ContainerRequest.class.getConstructor(Resource.class, String[].class, String[].class, Priority.class, boolean.class, String.class).newInstance(workerCapability, hostLocals, null, priority, true, workerNodeLabelExpression);
          } catch (Exception e) {
            chiefWorkerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
            LOG.warn("Set chief worker node label expression error:" + e);
          }
        } else {
          chiefWorkerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
        }
        LOG.info("Create chief worker container request: " + chiefWorkerContainerRequest.toString());
      }

      if (tfEvaluator) {
        Resource evaluatorWorkerCapability = Records.newRecord(Resource.class);
        evaluatorWorkerCapability.setMemory(evaluatorWorkerMemory);
        evaluatorWorkerCapability.setVirtualCores(workerVCores);
        if (workerNodeLabelExpression != null && workerNodeLabelExpression.trim() != "") {
          try {
            evaluatorWorkerContainerRequest = ContainerRequest.class.getConstructor(Resource.class, String[].class, String[].class, Priority.class, boolean.class, String.class).newInstance(workerCapability, hostLocals, null, priority, true, workerNodeLabelExpression);
          } catch (Exception e) {
            evaluatorWorkerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
            LOG.warn("Set evaluator worker node label expression error:" + e);
          }
        } else {
          evaluatorWorkerContainerRequest = new ContainerRequest(workerCapability, hostLocals, null, priority);
        }
        LOG.info("Create evaluator worker container request: " + evaluatorWorkerContainerRequest.toString());
      }
    }

    if (psNum > 0) {
      String psNodeLabelExpression = conf.get(DeepXConfiguration.DEEPX_PS_NODELABELEXPRESSION);
      Resource psCapability = Records.newRecord(Resource.class);
      psCapability.setMemory(psMemory);
      psCapability.setVirtualCores(psVCores);
      if (psNodeLabelExpression != null && psNodeLabelExpression.trim() != "") {
        try {
          psContainerRequest = ContainerRequest.class.getConstructor(Resource.class, String[].class, String[].class, Priority.class, boolean.class, String.class).newInstance(psCapability, hostLocals, null, priority, true, psNodeLabelExpression);
        } catch (Exception e) {
          psContainerRequest = new ContainerRequest(psCapability, hostLocals, null, priority);
          LOG.warn("Set ps node label expression error:" + e);
        }
      } else {
        psContainerRequest = new ContainerRequest(psCapability, hostLocals, null, priority);
      }
      LOG.info("Create ps container request: " + psContainerRequest.toString());
    }
  }

  private void buildContainerLocalResource() {
    URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
    LOG.info("default URI is " + defaultUri.toString());
    containerLocalResource = new HashMap<>();
    try {
      containerLocalResource.put(DeepXConstants.DEEPX_APPLICATION_JAR,
          Utilities.createApplicationResource(appJarRemoteLocation.getFileSystem(conf),
              appJarRemoteLocation,
              LocalResourceType.FILE));
      containerLocalResource.put(DeepXConstants.DEEPX_JOB_CONFIGURATION,
          Utilities.createApplicationResource(appConfRemoteLocation.getFileSystem(conf),
              appConfRemoteLocation,
              LocalResourceType.FILE));
      if (deepxAppType.equals("MPI")) {
        reLinkFiles.append(DeepXConstants.DEEPX_JOB_CONFIGURATION).append(",");
      }

      if (appCacheFilesRemoteLocation != null) {
        String[] cacheFiles = StringUtils.split(appCacheFilesRemoteLocation, ",");
        for (String path : cacheFiles) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheFile path format " + appCacheFilesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();
          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }
          LOG.info("Cache file remote path is " + pathRemote + " and alias name is " + aliasName);
          containerLocalResource.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.FILE));
          if (deepxAppType.equals("MPI")) {
            reLinkFiles.append(aliasName).append(",");
          }
        }
      }

      if (appCacheArchivesRemoteLocation != null) {
        String[] cacheArchives = StringUtils.split(appCacheArchivesRemoteLocation, ",");
        for (String path : cacheArchives) {
          Path pathRemote;
          String aliasName;
          if (path.contains("#")) {
            String[] paths = StringUtils.split(path, "#");
            if (paths.length != 2) {
              throw new RuntimeException("Error cacheArchive path format " + appCacheArchivesRemoteLocation);
            }
            pathRemote = new Path(paths[0]);
            aliasName = paths[1];
          } else {
            pathRemote = new Path(path);
            aliasName = pathRemote.getName();
          }
          URI pathRemoteUri = pathRemote.toUri();
          if (pathRemoteUri.getScheme() == null || pathRemoteUri.getHost() == null) {
            pathRemote = new Path(defaultUri.toString(), pathRemote.toString());
          }
          LOG.info("Cache archive remote path is " + pathRemote + " and alias name is " + aliasName);
          containerLocalResource.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.ARCHIVE));
          if (deepxAppType.equals("MPI")) {
            reLinkFiles.append(aliasName).append(",");
          }
        }
      }

      if (appFilesRemoteLocation != null) {
        String[] deepxFiles = StringUtils.split(appFilesRemoteLocation, ",");
        for (String file : deepxFiles) {
          Path path = new Path(file);
          containerLocalResource.put(path.getName(),
              Utilities.createApplicationResource(path.getFileSystem(conf),
                  path,
                  LocalResourceType.FILE));
          if (deepxAppType.equals("MPI")) {
            reLinkFiles.append(path.getName()).append(",");
          }
        }
      }

      if (appLibJarsRemoteLocation != null) {
        String[] jarFiles = StringUtils.split(appLibJarsRemoteLocation, ",");
        for (String file : jarFiles) {
          Path path = new Path(file);
          containerLocalResource.put(path.getName(),
              Utilities.createApplicationResource(path.getFileSystem(conf),
                  path,
                  LocalResourceType.FILE));
          if (deepxAppType.equals("MPI")) {
            reLinkFiles.append(path.getName()).append(",");
          }
        }
      }

    } catch (IOException e) {
      throw new RuntimeException("Error while build container local resource", e);
    }
  }

  private Map<String, String> buildContainerEnv(String role) {
    LOG.info("Setting environments for the Container");
    Map<String, String> containerEnv = new HashMap<>();
    containerEnv.put(DeepXConstants.Environment.HADOOP_USER_NAME.toString(), conf.get("hadoop.job.ugi").split(",")[0]);
    containerEnv.put(DeepXConstants.Environment.DEEPX_TF_ROLE.toString(), role);
    containerEnv.put(DeepXConstants.Environment.DEEPX_EXEC_CMD.toString(), deepxCommand);
    containerEnv.put(DeepXConstants.Environment.DEEPX_APP_TYPE.toString(), deepxAppType);
    if (outputIndex >= 0) {
      containerEnv.put(DeepXConstants.Environment.DEEPX_OUTPUTS_WORKER_INDEX.toString(), String.valueOf(outputIndex));
    }
    if (this.inputPath.length() > 0) {
      containerEnv.put(DeepXConstants.Environment.DEEPX_INPUT_PATH.toString(), this.inputPath.substring(0, inputPath.length() - 1));
    }
    if (deepxAppType.equals("MXNET") && !single) {
      containerEnv.put(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString(), String.valueOf(workerNum));
      containerEnv.put(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString(), String.valueOf(psNum));
      containerEnv.put("DMLC_PS_ROOT_URI", dmlcPsRootUri);
      containerEnv.put("DMLC_PS_ROOT_PORT", String.valueOf(dmlcPsRootPort));
    }

    if (deepxAppType.equals("DISTXGBOOST")) {
      containerEnv.put("DMLC_NUM_WORKER", String.valueOf(workerNum));
      containerEnv.put("DMLC_TRACKER_URI", dmlcTrackerUri);
      containerEnv.put("DMLC_TRACKER_PORT", String.valueOf(dmlcTrackerPort));
    }

    if (deepxAppType.equals("DISTLIGHTGBM")) {
      containerEnv.put(DeepXConstants.Environment.DEEPX_LIGHTGBM_WORKER_NUM.toString(), String.valueOf(workerNum));
    }

    if (deepxAppType.equals("LIGHTLDA")) {
      containerEnv.put(DeepXConstants.Environment.DEEPX_LIGHTLDA_WORKER_NUM.toString(), String.valueOf(workerNum));
      containerEnv.put(DeepXConstants.Environment.DEEPX_LIGHTLDA_PS_NUM.toString(), String.valueOf(psNum));
    }

    if (deepxAppType.equals("XFLOW")){
      containerEnv.put(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString(), String.valueOf(workerNum));
      containerEnv.put(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString(), String.valueOf(psNum));
      containerEnv.put("DMLC_PS_ROOT_URI", dmlcPsRootUri);
      containerEnv.put("DMLC_PS_ROOT_PORT", String.valueOf(dmlcPsRootPort));
    }

    containerEnv.put("CLASSPATH", System.getenv("CLASSPATH"));
    containerEnv.put(DeepXConstants.Environment.APP_ATTEMPTID.toString(), applicationAttemptID.toString());
    containerEnv.put(DeepXConstants.Environment.APP_ID.toString(), applicationAttemptID.getApplicationId().toString());

    containerEnv.put(DeepXConstants.Environment.APPMASTER_HOST.toString(),
        System.getenv(ApplicationConstants.Environment.NM_HOST.toString()));
    containerEnv.put(DeepXConstants.Environment.APPMASTER_PORT.toString(),
        String.valueOf(containerListener.getServerPort()));
    containerEnv.put("PATH", System.getenv("PATH") + ":" + System.getenv(DeepXConstants.Environment.USER_PATH.toString()));
    containerEnv.put("LD_LIBRARY_PATH", System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv(DeepXConstants.Environment.USER_LD_LIBRARY_PATH.toString()));

    if (deepxAppType.equals("MPI")) {
      if (!mpiExecDir.equals("")) {
        containerEnv.put(DeepXConstants.Environment.MPI_EXEC_DIR.toString(), mpiExecDir);
      }
      if (reLinkFiles.length() > 0) {
        containerEnv.put(DeepXConstants.Environment.MPI_FILES_LINKS.toString(), reLinkFiles.substring(0, reLinkFiles.length() - 1));
      }
    }

    LOG.debug("env:" + containerEnv.toString());
    Set<String> envStr = containerEnv.keySet();
    for (String anEnvStr : envStr) {
      LOG.debug("env:" + anEnvStr);
    }
    if (conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV) != null) {
      String[] containerUserEnv = StringUtils.split(conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV), "|");
      if (containerUserEnv.length > 0) {
        for (String envPair : containerUserEnv) {
          String[] env = StringUtils.split(envPair, "=");
          if (env.length != 2) {
            LOG.error(envPair + " is not the correct.");
          } else {
            Utilities.addPathToEnvironment(containerEnv, env[0], env[1]);
          }
        }
      }
    }
    if (deepxContainerType.equalsIgnoreCase("DOCKER")) {
      String dockeImage = conf.get(DeepXConfiguration.DEEPX_DOCKER_IMAGE);
      int containerMemory = conf.getInt(DeepXConfiguration.DEEPX_WORKER_MEMORY,
          DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
      int containerCpu = conf.getInt(DeepXConfiguration.DEEPX_WORKER_VCORES,
          DeepXConfiguration.DEFAULT_DEEPX_WORKER_VCORES);
      containerEnv.put("DOCKER_CONTAINER_MEMORY", containerMemory + "");
      containerEnv.put("DOCKER_CONTAINER_CPU", containerCpu + "");
      if (dockeImage == null || dockeImage.equals("")) {
        throw new RuntimeException("Docker need image!");
      }
    }
    return containerEnv;
  }

  private List<String> buildContainerLaunchCommand(int containerMemory) {
    List<String> containerLaunchcommands = new ArrayList<>();
    LOG.info("Setting up container command");
    Vector<CharSequence> vargs = new Vector<>(10);
    vargs.add("${JAVA_HOME}" + "/bin/java");
    vargs.add("-Xmx" + containerMemory + "m");
    vargs.add("-Xms" + containerMemory + "m");
    String javaOpts = conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRA_JAVA_OPTS, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_JAVA_OPTS_EXCEPT_MEMORY);
    if (!StringUtils.isBlank(javaOpts)) {
      vargs.add(javaOpts);
    }
    vargs.add(DeepXContainer.class.getName());
    vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDOUT);
    vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/" + ApplicationConstants.STDERR);

    StringBuilder containerCmd = new StringBuilder();
    for (CharSequence str : vargs) {
      containerCmd.append(str).append(" ");
    }
    containerLaunchcommands.add(containerCmd.toString());
    LOG.info("Container launch command: " + containerLaunchcommands.toString());
    return containerLaunchcommands;
  }

  /**
   * Application Master launch "mpiexec" process locally for mpi app
   */
  private void launchMpiExec() throws IOException {
    LOG.info("Launching mpiexec in Application Master");
    StringBuilder commandBuilder = new StringBuilder();
    StringBuilder ldLibraryPath = new StringBuilder();

    String mpiExtraLdLibraryPath = conf.get(DeepXConfiguration.DEEPX_MPI_EXTRA_LD_LIBRARY_PATH);
    if (mpiExtraLdLibraryPath != null && mpiExtraLdLibraryPath.trim() != "") {
      ldLibraryPath.append(mpiExtraLdLibraryPath);
      LOG.info("add " + ldLibraryPath + " to LD_LIBRARY_PATH");
    }

    String mpiInstallDir = conf.get(DeepXConfiguration.DEEPX_MPI_INSTALL_DIR, DeepXConfiguration.DEFAULT_DEEPX_MPI_INSTALL_DIR);
    commandBuilder.append(mpiInstallDir).append(File.separator).append("bin").append(File.separator);
    ldLibraryPath.append(":").append(mpiInstallDir).append(File.separator).append("lib");

    commandBuilder.append("mpiexec --host ");
    ldLibraryPath.append(":").append(System.getenv("LD_LIBRARY_PATH"));
    for (Container container : acquiredWorkerContainers) {
      commandBuilder.append(container.getNodeId().getHost()).append(",");
    }
    commandBuilder.deleteCharAt(commandBuilder.length() - 1);
    commandBuilder.append(" ").append(deepxCommand);

    String[] envs = new String[]{
        "PATH=" + System.getenv("PATH"),
        "PWD=" + mpiExecDir,
        "LD_LIBRARY_PATH=" + ldLibraryPath.toString()
    };

    File mpiExec = new File(mpiExecDir);
    LOG.info("Executing mpi exec command: " + commandBuilder.toString());
    Runtime rt = Runtime.getRuntime();

    StringTokenizer tokenizer = new StringTokenizer(commandBuilder.toString());
    String[] commandArray = new String[tokenizer.countTokens()];
    for (int i = 0; tokenizer.hasMoreTokens(); i++) {
      commandArray[i] = tokenizer.nextToken();
    }
    LOG.info("Mpi exec Process run in: " + mpiExec.toString());
    mpiExecProcess = rt.exec(commandArray, envs, mpiExec);

    Thread stdinThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader reader;
          reader = new BufferedReader(new InputStreamReader(mpiExecProcess.getInputStream()));
          String mpiExecOutput;
          while ((mpiExecOutput = reader.readLine()) != null) {
            if (mpiExecOutput.startsWith("command")) {
              LOG.info("Container mpi Command " + mpiExecOutput);
              appendMessage(new Message(LogType.STDERR, mpiExecOutput));
              mpiContainerCommand = mpiExecOutput.replaceFirst("command:", "").replaceFirst("--daemonize", "");
            } else {
              LOG.info(mpiExecOutput);
              appendMessage(new Message(LogType.STDOUT, mpiExecOutput));
            }
          }
        } catch (Exception e) {
          LOG.warn("Error in mpi exec process stdinThread");
        }
      }
    });
    stdinThread.start();

    Thread stderrThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          BufferedReader reader;
          reader = new BufferedReader(new InputStreamReader(mpiExecProcess.getErrorStream()));
          String mpiExecStderr;
          while ((mpiExecStderr = reader.readLine()) != null) {
            LOG.info(mpiExecStderr);
            appendMessage(new Message(LogType.STDERR, mpiExecStderr));
          }
        } catch (Exception e) {
          LOG.warn("Error in mpi exec process stderrThread");
        }
      }
    });
    stderrThread.start();
  }

  /**
   * Async Method telling NMClientAsync to launch specific container
   *
   * @param container the container which should be launched
   * @return is launched success
   */
  @SuppressWarnings("deprecation")
  private void launchContainer(Map<String, LocalResource> containerLocalResource,
                               Map<String, String> containerEnv,
                               List<String> containerLaunchcommands,
                               Container container, int index) throws IOException {
    LOG.info("Setting up launch context for containerID="
        + container.getId());
    if(deepxAppType.equals("MPI")) {
      String containerMpiCommand = mpiContainerCommand.replace("<template>",
          String.valueOf(index)).replaceAll("\"", "#");
      containerEnv.put(DeepXConstants.Environment.CONTAINER_COMMAND.toString(), containerMpiCommand);
      LOG.info("Container mpi command is:" + containerMpiCommand);
    }
    containerEnv.put(DeepXConstants.Environment.DEEPX_TF_INDEX.toString(), String.valueOf(index));
    ContainerLaunchContext ctx = ContainerLaunchContext.newInstance(
        containerLocalResource, containerEnv, containerLaunchcommands, null, null, null);

    try {
      nmAsync.startContainerAsync(container, ctx);
    } catch (Exception e) {
      throw new RuntimeException("Launching container " + container.getId() + " failed!");
    }
  }

  private void appendMessage(String message, boolean logEnable) {
    if (logEnable) {
      LOG.info(message);
    }
    appendMessage(new Message(LogType.STDERR, message));
  }

  private void appendMessage(Message message) {
    if (applicationMessageQueue.size() >= conf.getInt(DeepXConfiguration.DEEPX_MESSAGES_LEN_MAX, DeepXConfiguration.DEFAULT_DEEPX_MESSAGES_LEN_MAX)) {
      applicationMessageQueue.poll();
    }
    if (!applicationMessageQueue.offer(message)) {
      LOG.warn("Message queue is full, this message will be ignored");
    }
  }

  private void unregisterApp(FinalApplicationStatus finalStatus, String diagnostics) {
    try {
      amrmAsync.unregisterApplicationMaster(finalStatus, diagnostics,
          applicationHistoryUrl);
      amrmAsync.stop();
    } catch (Exception e) {
      LOG.error("Error while unregister Application", e);
    }
  }

  public Configuration getConf() {
    return conf;
  }

  @SuppressWarnings("deprecation")
  private boolean run() throws IOException, NoSuchAlgorithmException {
    LOG.info("ApplicationMaster Starting ...");

    registerApplicationMaster();
    if (conf.get(DeepXConfiguration.DEEPX_INPUT_STRATEGY, DeepXConfiguration.DEFAULT_DEEPX_INPUT_STRATEGY).equals("STREAM")) {
      buildInputStreamFileStatus();
    } else {
      buildInputFileStatus();
    }

    if ("TENSORFLOW".equals(deepxAppType) || "MXNET".equals(deepxAppType) || "LIGHTLDA".equals(deepxAppType) || "XFLOW".equals(deepxAppType)) {
      this.appendMessage("DeepX application needs " + workerNum + " worker and "
          + psNum + " ps  containers in fact", true);
    } else {
      this.appendMessage("DeepX application needs " + workerNum + " worker container in fact", true);
    }

    buildContainerRequest(hostLocals);

    int requestWorkerNum = workerNum;
    if ("TENSORFLOW".equals(deepxAppType)) {
      if (chiefWorker)
        requestWorkerNum--;
      if (tfEvaluator)
        requestWorkerNum--;
    }

    rmCallbackHandler.setNeededPsContainersCount(psNum);
    rmCallbackHandler.setNeededWorkerContainersCount(requestWorkerNum);
    rmCallbackHandler.setDeepXAppType(deepxAppType);
    if (deepxAppType.equals("MPI")) {
      rmCallbackHandler.addBlackHost(applicationMasterHostname);
    }

    int allocateInterval = conf.getInt(DeepXConfiguration.DEEPX_ALLOCATE_INTERVAL, DeepXConfiguration.DEFAULT_DEEPX_ALLOCATE_INTERVAL);
    amrmAsync.setHeartbeatInterval(allocateInterval);

    for (int i = 0; i < psNum; i++) {
      amrmAsync.addContainerRequest(psContainerRequest);
    }

    if (psNum > 0) {
      LOG.info("Try to allocate " + psNum + " ps/server containers");
    }

    Boolean startAllocatedContainer = false;
    Long startAllocatedTimeStamp = Long.MIN_VALUE;
    int psCancelCount = 0;
    String failMessage = "Container waiting except the allocated expiry time. Maybe the Cluster available resources are not satisfied the user need. Please resubmit !";
    while (rmCallbackHandler.getAllocatedPsContainerNumber() < psNum) {
      List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
      List<String> blackHosts = rmCallbackHandler.getBlackHosts();
      try {
        Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
        updateBlacklist.invoke(amrmAsync, blackHosts, null);
      } catch (NoSuchMethodException e) {
        LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
      } catch (InvocationTargetException e) {
        LOG.error("InvocationTargetException : " + e);
      } catch (IllegalAccessException e) {
        LOG.error("IllegalAccessException : " + e);
      }
      synchronized (cancelContainers) {
        if (cancelContainers.size() != 0) {
          for (Container container : cancelContainers) {
            LOG.info("Canceling container: " + container.getId().toString());
            amrmAsync.releaseAssignedContainer(container.getId());
            amrmAsync.addContainerRequest(psContainerRequest);
            psCancelCount ++;
          }
          cancelContainers.clear();
        }
      }
      if (rmCallbackHandler.getAllocatedPsContainerNumber() > 0 && !startAllocatedContainer) {
        startAllocatedContainer = true;
        startAllocatedTimeStamp = System.currentTimeMillis();
      }
      if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
        this.appendMessage(failMessage, true);
        this.appendMessage("Unregister  Application", true);
        unregisterApp(FinalApplicationStatus.FAILED, failMessage);
        return false;
      }
      Utilities.sleep(allocateInterval);
    }

    acquiredPsContainers = rmCallbackHandler.getAcquiredPsContainer();
    if (psNum > 0) {
      int totalNumAllocatedPs = rmCallbackHandler.getAllocatedPsContainerNumber();
      if (totalNumAllocatedPs > psNum) {
        while (acquiredPsContainers.size() > psNum) {
          Container releaseContainer = acquiredPsContainers.remove(acquiredPsContainers.size() - 1);
          amrmAsync.releaseAssignedContainer(releaseContainer.getId());
          LOG.info("Release container " + releaseContainer.getId().toString());
        }
      }
      LOG.info("Total " + acquiredPsContainers.size() + " ps containers has allocated.");
    }

    rmCallbackHandler.setWorkerContainersAllocating();

    for (int i = 0; i < requestWorkerNum; i++) {
      amrmAsync.addContainerRequest(workerContainerRequest);
    }

    LOG.info("Try to allocate " + requestWorkerNum + " worker containers");
    int workerCancelCount = 0;
    while (rmCallbackHandler.getAllocatedWorkerContainerNumber() < requestWorkerNum) {
      List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
      List<String> blackHosts = rmCallbackHandler.getBlackHosts();
      try {
        Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
        updateBlacklist.invoke(amrmAsync, blackHosts, null);
      } catch (NoSuchMethodException e) {
        LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
      } catch (InvocationTargetException e) {
        LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " InvocationTargetException Error : " + e);
      } catch (IllegalAccessException e) {
        LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " IllegalAccessException Error : " + e);
      }
      synchronized (cancelContainers) {
        if (cancelContainers.size() != 0) {
          for (Container container : cancelContainers) {
            LOG.info("Canceling container: " + container.getId().toString());
            amrmAsync.releaseAssignedContainer(container.getId());
            amrmAsync.addContainerRequest(workerContainerRequest);
            workerCancelCount++;
          }
          cancelContainers.clear();
        }
      }
      if (rmCallbackHandler.getAllocatedWorkerContainerNumber() > 0 && !startAllocatedContainer) {
        startAllocatedContainer = true;
        startAllocatedTimeStamp = System.currentTimeMillis();
      }
      if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
        this.appendMessage(failMessage, true);
        this.appendMessage("Unregister  Application", true);
        unregisterApp(FinalApplicationStatus.FAILED, failMessage);
        return false;
      }
      Utilities.sleep(allocateInterval);
    }

    acquiredWorkerContainers = rmCallbackHandler.getAcquiredWorkerContainer();

    int totalNumAllocatedWorkers = rmCallbackHandler.getAllocatedWorkerContainerNumber();
    if (totalNumAllocatedWorkers > requestWorkerNum) {
      while (acquiredWorkerContainers.size() > requestWorkerNum) {
        Container releaseContainer = acquiredWorkerContainers.remove(0);
        amrmAsync.releaseAssignedContainer(releaseContainer.getId());
        LOG.info("Release container " + releaseContainer.getId().toString());
      }
    }
    LOG.info("Total " + acquiredWorkerContainers.size() + " worker containers has allocated.");
    for (int i = 0; i < psNum + psCancelCount; i++) {
      amrmAsync.removeContainerRequest(psContainerRequest);
    }
    for (int i = 0; i < requestWorkerNum + workerCancelCount; i++) {
      amrmAsync.removeContainerRequest(workerContainerRequest);
    }

    List<Container> cancelContainersTotal = rmCallbackHandler.getCancelContainer();
    synchronized (cancelContainersTotal) {
      if (cancelContainersTotal.size() != 0) {
        for (Container container : cancelContainersTotal) {
          LOG.info("Canceling container: " + container.getId().toString());
          amrmAsync.releaseAssignedContainer(container.getId());
        }
        cancelContainersTotal.clear();
      }
    }

    if(requestWorkerNum != workerNum){
      int chiefWorkerCancelCount = 0;
      int evaluatorCancelCount = 0;
      if(chiefWorker) {
        LOG.info("Try to allocate chief worker containers");
        rmCallbackHandler.setChiefWorkerContainersAllocating();
        amrmAsync.addContainerRequest(chiefWorkerContainerRequest);
        chiefWorkerCancelCount ++;
        while (rmCallbackHandler.getAcquiredChiefWorkerContainers().size() < 1) {
          List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
          List<String> blackHosts = rmCallbackHandler.getBlackHosts();
          try {
            Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
            updateBlacklist.invoke(amrmAsync, blackHosts, null);
          } catch (NoSuchMethodException e) {
            LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
          } catch (InvocationTargetException e) {
            LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " InvocationTargetException Error : " + e);
          } catch (IllegalAccessException e) {
            LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " IllegalAccessException Error : " + e);
          }
          synchronized (cancelContainers) {
            if (cancelContainers.size() != 0) {
              for (Container container : cancelContainers) {
                LOG.info("Canceling container: " + container.getId().toString());
                amrmAsync.releaseAssignedContainer(container.getId());
                amrmAsync.addContainerRequest(chiefWorkerContainerRequest);
                chiefWorkerCancelCount++;
              }
              cancelContainers.clear();
            }
          }
          if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
            this.appendMessage(failMessage, true);
            this.appendMessage("Unregister  Application", true);
            unregisterApp(FinalApplicationStatus.FAILED, failMessage);
            return false;
          }
          Utilities.sleep(allocateInterval);
        }

        acquiredChiefWorkerContainers = rmCallbackHandler.getAcquiredChiefWorkerContainers();
        synchronized (acquiredChiefWorkerContainers) {
          if (acquiredChiefWorkerContainers.size() > 1) {
            while (acquiredChiefWorkerContainers.size() > 1) {
              Container releaseContainer = acquiredChiefWorkerContainers.remove(0);
              amrmAsync.releaseAssignedContainer(releaseContainer.getId());
              LOG.info("Release chief container " + releaseContainer.getId().toString());
            }
          }
        }
        LOG.info("Total " + acquiredChiefWorkerContainers.size() + " chief worker containers has allocated.");
        acquiredWorkerContainers.add(0, acquiredChiefWorkerContainers.get(0));
      }

      if (tfEvaluator) {
        LOG.info("Try to allocate evaluator worker containers");
        rmCallbackHandler.setEvaluatorWorkerContainersAllocating();
        amrmAsync.addContainerRequest(evaluatorWorkerContainerRequest);
        evaluatorCancelCount ++;
        while (rmCallbackHandler.getAcquiredEvaluatorWorkerContainers().size() < 1) {
          List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
          List<String> blackHosts = rmCallbackHandler.getBlackHosts();
          try {
            Method updateBlacklist = amrmAsync.getClass().getMethod("updateBlacklist", List.class, List.class);
            updateBlacklist.invoke(amrmAsync, blackHosts, null);
          } catch (NoSuchMethodException e) {
            LOG.debug("current hadoop version don't have the method updateBlacklist of Class " + amrmAsync.getClass().toString() + ". For More Detail:" + e);
          } catch (InvocationTargetException e) {
            LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " InvocationTargetException Error : " + e);
          } catch (IllegalAccessException e) {
            LOG.error("invoke the method updateBlacklist of Class " + amrmAsync.getClass().toString() + " IllegalAccessException Error : " + e);
          }
          synchronized (cancelContainers) {
            if (cancelContainers.size() != 0) {
              for (Container container : cancelContainers) {
                LOG.info("Canceling container: " + container.getId().toString());
                amrmAsync.releaseAssignedContainer(container.getId());
                amrmAsync.addContainerRequest(evaluatorWorkerContainerRequest);
                evaluatorCancelCount++;
              }
              cancelContainers.clear();
            }
          }
          if (startAllocatedContainer && (System.currentTimeMillis() - startAllocatedTimeStamp) > conf.getInt(YarnConfiguration.RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS, YarnConfiguration.DEFAULT_RM_CONTAINER_ALLOC_EXPIRY_INTERVAL_MS)) {
            this.appendMessage(failMessage, true);
            this.appendMessage("Unregister  Application", true);
            unregisterApp(FinalApplicationStatus.FAILED, failMessage);
            return false;
          }
          Utilities.sleep(allocateInterval);
        }

        acquiredEvaluatorWorkerContainers = rmCallbackHandler.getAcquiredEvaluatorWorkerContainers();
        synchronized (acquiredEvaluatorWorkerContainers) {
          if (acquiredEvaluatorWorkerContainers.size() > 1) {
            while (acquiredEvaluatorWorkerContainers.size() > 1) {
              Container releaseContainer = acquiredEvaluatorWorkerContainers.remove(0);
              amrmAsync.releaseAssignedContainer(releaseContainer.getId());
              LOG.info("Release evaluator container " + releaseContainer.getId().toString());
            }
          }
        }
        LOG.info("Total " + acquiredEvaluatorWorkerContainers.size() + " evaluator worker containers has allocated.");
        acquiredWorkerContainers.add(acquiredEvaluatorWorkerContainers.get(0));
      }

      for (int i = 0; i < chiefWorkerCancelCount; i++) {
        amrmAsync.removeContainerRequest(chiefWorkerContainerRequest);
      }
      for (int i = 0; i < evaluatorCancelCount; i++) {
        amrmAsync.removeContainerRequest(evaluatorWorkerContainerRequest);
      }
    }

    List<Container> cancelContainers = rmCallbackHandler.getCancelContainer();
    synchronized (cancelContainers) {
      if (cancelContainers.size() != 0) {
        for (Container container : cancelContainers) {
          LOG.info("Canceling unnecessary container: " + container.getId().toString());
          amrmAsync.releaseAssignedContainer(container.getId());
        }
        cancelContainers.clear();
      }
    }

    if (conf.getBoolean(DeepXConfiguration.DEEPX_HOST_LOCAL_ENABLE, DeepXConfiguration.DEFAULT_DEEPX_HOST_LOCAL_ENABLE)) {
      containerHostnames = new HashSet<>();
      if (acquiredPsContainers.size() > 0) {
        for (Container container : acquiredPsContainers) {
          containerHostnames.add(container.getNodeId().getHost());
        }
      }
      if (acquiredWorkerContainers.size() > 0) {
        for (Container container : acquiredWorkerContainers) {
          containerHostnames.add(container.getNodeId().getHost());
        }
      }
      LOG.info("host local enable is true, host list is: " + containerHostnames.toString());
    }

    //launch mxnet scheduler
    if (deepxAppType.equals("MXNET") && !single) {
      LOG.info("Setting environments for the MXNet scheduler");
      dmlcPsRootUri = applicationMasterHostname;
      Socket schedulerReservedSocket = new Socket();
      try {
        Utilities.getReservePort(schedulerReservedSocket, InetAddress.getByName(applicationMasterHostname).getHostAddress(), reservePortBegin, reservePortEnd);
      } catch (IOException e) {
        LOG.error("Can not get available port");
      }
      dmlcPsRootPort = schedulerReservedSocket.getLocalPort();
      List<String> schedulerEnv = new ArrayList<>(20);
      Map<String, String> userEnv = new HashMap<>();
      if (conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV) != null) {
        String[] env = StringUtils.split(conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV), "|");
        for (String envPair : env) {
          String[] userEnvPair = StringUtils.split(envPair, "=");
          if (userEnvPair.length != 2) {
            LOG.error(envPair + " is not correct");
          } else {
            schedulerEnv.add(envPair);
            userEnv.put(userEnvPair[0], userEnvPair[1]);
          }
        }
      }
      if (userEnv.containsKey("PATH")) {
        schedulerEnv.add("PATH=" + userEnv.get("PATH") + System.getProperty("path.separator") + System.getenv("PATH"));
      } else {
        schedulerEnv.add("PATH=" + System.getenv("PATH"));
      }
      schedulerEnv.add("JAVA_HOME=" + System.getenv("JAVA_HOME"));
      schedulerEnv.add("HADOOP_HOME=" + System.getenv("HADOOP_HOME"));
      schedulerEnv.add("HADOOP_HDFS_HOME=" + System.getenv("HADOOP_HDFS_HOME"));
      if (userEnv.containsKey("LD_LIBRARY_PATH")) {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + userEnv.get("LD_LIBRARY_PATH") + System.getProperty("path.separator") + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      } else {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      }
      if (userEnv.containsKey("CLASSPATH")) {
        schedulerEnv.add("CLASSPATH=" + "./:" + userEnv.get("CLASSPATH") + System.getProperty("path.separator") + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      } else {
        schedulerEnv.add("CLASSPATH=" + "./:" + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      }
      schedulerEnv.add("DMLC_ROLE=scheduler");
      schedulerEnv.add("DMLC_PS_ROOT_URI=" + dmlcPsRootUri);
      schedulerEnv.add("DMLC_PS_ROOT_PORT=" + dmlcPsRootPort);
      schedulerEnv.add(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString() + "=" + workerNum);
      schedulerEnv.add(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString() + "=" + psNum);
      schedulerEnv.add("PYTHONUNBUFFERED=1");

      LOG.info("Executing command:" + deepxCommand);
      LOG.info("DMLC_PS_ROOT_URI is " + dmlcPsRootUri);
      LOG.info("DMLC_PS_ROOT_PORT is " + dmlcPsRootPort);
      LOG.info(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString() + "=" + workerNum);
      LOG.info(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString() + "=" + psNum);

      try {
        Runtime rt = Runtime.getRuntime();
        schedulerReservedSocket.close();
        final Process mxnetSchedulerProcess = rt.exec(deepxCommand, schedulerEnv.toArray(new String[schedulerEnv.size()]));
        LOG.info("Starting thread to redirect stdout of MXNet scheduler process");
        Thread mxnetSchedulerRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(mxnetSchedulerProcess.getInputStream()));
              String mxnetSchedulerStdoutLog;
              while ((mxnetSchedulerStdoutLog = reader.readLine()) != null) {
                LOG.info(mxnetSchedulerStdoutLog);
              }
            } catch (Exception e) {
              LOG.warn("Exception in thread mxnetSchedulerRedirectThread");
              e.printStackTrace();
            }
          }
        });
        mxnetSchedulerRedirectThread.start();

        LOG.info("Starting thread to redirect stderr of MXNet scheduler process");
        Thread boardStderrRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(mxnetSchedulerProcess.getErrorStream()));
              String mxnetSchedulerStderrLog;
              while ((mxnetSchedulerStderrLog = reader.readLine()) != null) {
                LOG.debug(mxnetSchedulerStderrLog);
              }
            } catch (Exception e) {
              LOG.warn("Error in thread mxnetSchedulerStderrRedirectThread");
              e.printStackTrace();
            }
          }
        });
        boardStderrRedirectThread.start();
      } catch (Exception e) {
        LOG.error("start MXNet scheduler error " + e);
      }

    }

    //launch dist xgboost scheduler
    if (deepxAppType.equals("DISTXGBOOST")) {
      LOG.info("Seting environments for the dist xgboost scheduler");
      dmlcTrackerUri = applicationMasterHostname;
      Socket schedulerReservedSocket = new Socket();
      try {
        Utilities.getReservePort(schedulerReservedSocket, InetAddress.getByName(applicationMasterHostname).getHostAddress(), reservePortBegin, reservePortEnd);
      } catch (IOException e) {
        LOG.error("Can not get available port");
      }
      dmlcTrackerPort = schedulerReservedSocket.getLocalPort();
      List<String> schedulerEnv = new ArrayList<>(20);
      Map<String, String> userEnv = new HashMap<>();
      if (conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV) != null) {
        String[] env = StringUtils.split(conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV), "|");
        for (String envPair : env) {
          String[] userEnvPair = StringUtils.split(envPair, "=");
          if (userEnvPair.length != 2) {
            LOG.error(envPair + " is not correct");
          } else {
            schedulerEnv.add(envPair);
            userEnv.put(userEnvPair[0], userEnvPair[1]);
          }
        }
      }
      if (userEnv.containsKey("PATH")) {
        schedulerEnv.add("PATH=" + userEnv.get("PATH") + System.getProperty("path.separator") + System.getenv("PATH"));
      } else {
        schedulerEnv.add("PATH=" + System.getenv("PATH"));
      }
      schedulerEnv.add("JAVA_HOME=" + System.getenv("JAVA_HOME"));
      schedulerEnv.add("HADOOP_HOME=" + System.getenv("HADOOP_HOME"));
      schedulerEnv.add("HADOOP_HDFS_HOME=" + System.getenv("HADOOP_HDFS_HOME"));
      if (userEnv.containsKey("LD_LIBRARY_PATH")) {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + userEnv.get("LD_LIBRARY_PATH") + System.getProperty("path.separator") + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      } else {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      }
      if (userEnv.containsKey("CLASSPATH")) {
        schedulerEnv.add("CLASSPATH=" + "./:" + userEnv.get("CLASSPATH") + System.getProperty("path.separator") + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      } else {
        schedulerEnv.add("CLASSPATH=" + "./:" + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      }
      schedulerEnv.add("PYTHONUNBUFFERED=1");

      String distXgboostSchedulerCmd = "python xgboost/self-define/rabitTracker.py --num-workers=" + workerNum
          + " --host-ip=" + dmlcTrackerUri + " --port=" + dmlcTrackerPort;
      LOG.info("Dist xgboost scheduler executing command:" + distXgboostSchedulerCmd);
      LOG.info("DMLC_TRACKER_URI is " + dmlcTrackerUri);
      LOG.info("DMLC_TRACKER_PORT is " + dmlcTrackerPort);
      LOG.info("DMLC_NUM_WORKER=" + workerNum);

      try {
        Runtime rt = Runtime.getRuntime();
        schedulerReservedSocket.close();
        final Process xgboostSchedulerProcess = rt.exec(distXgboostSchedulerCmd, schedulerEnv.toArray(new String[schedulerEnv.size()]));
        LOG.info("Starting thread to redirect stdout of xgboost scheduler process");
        Thread xgboostSchedulerRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xgboostSchedulerProcess.getInputStream()));
              String xgboostSchedulerStdoutLog;
              while ((xgboostSchedulerStdoutLog = reader.readLine()) != null) {
                LOG.info(xgboostSchedulerStdoutLog);
              }
            } catch (Exception e) {
              LOG.warn("Exception in thread xgboostSchedulerRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xgboostSchedulerRedirectThread.start();

        LOG.info("Starting thread to redirect stderr of xgboost scheduler process");
        Thread xgboostSchedulerStderrRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xgboostSchedulerProcess.getErrorStream()));
              String xgboostSchedulerStderrLog;
              while ((xgboostSchedulerStderrLog = reader.readLine()) != null) {
                LOG.info(xgboostSchedulerStderrLog);
              }
            } catch (Exception e) {
              LOG.warn("Error in thread xgboostSchedulerStderrRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xgboostSchedulerStderrRedirectThread.start();

      } catch (Exception e) {
        LOG.info("start xgboost scheduler error " + e);
      }

    }

    // launch xflow scheduler
    if (("XFLOW").equals(deepxAppType)) {
      LOG.info("Setting environments for the xflow scheduler");
      InetAddress address = null;
      try {
        address = InetAddress.getByName(applicationMasterHostname);
        dmlcPsRootUri = address.getHostAddress();
      } catch (UnknownHostException e) {
        LOG.info("acquire host ip failed " + e);
      }
      Socket schedulerReservedSocket = new Socket();
      try {
        Utilities.getReservePort(schedulerReservedSocket, InetAddress.getByName(applicationMasterHostname).getHostAddress(), reservePortBegin, reservePortEnd);
      } catch (IOException e) {
        LOG.error("Can not get available port");
      }
      dmlcPsRootPort = schedulerReservedSocket.getLocalPort();
      List<String> schedulerEnv = new ArrayList<>(20);
      Map<String, String> userEnv = new HashMap<>();
      if (conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV) != null) {
        String[] env = StringUtils.split(conf.get(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV), "|");
        for (String envPair : env) {
          String[] userEnvPair = StringUtils.split(envPair, "=");
          if (userEnvPair.length != 2) {
            LOG.error(envPair + " is not correct");
          } else {
            schedulerEnv.add(envPair);
            userEnv.put(userEnvPair[0], userEnvPair[1]);
          }
        }
      }
      if (userEnv.containsKey("PATH")) {
        schedulerEnv.add("PATH=" + userEnv.get("PATH") + System.getProperty("path.separator") + System.getenv("PATH"));
      } else {
        schedulerEnv.add("PATH=" + System.getenv("PATH"));
      }
      schedulerEnv.add("JAVA_HOME=" + System.getenv("JAVA_HOME"));
      schedulerEnv.add("HADOOP_HOME=" + System.getenv("HADOOP_HOME"));
      schedulerEnv.add("HADOOP_HDFS_HOME=" + System.getenv("HADOOP_HDFS_HOME"));
      if (userEnv.containsKey("LD_LIBRARY_PATH")) {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + userEnv.get("LD_LIBRARY_PATH") + System.getProperty("path.separator") + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      } else {
        schedulerEnv.add("LD_LIBRARY_PATH=" + "./:" + System.getenv("LD_LIBRARY_PATH") + ":" + System.getenv("JAVA_HOME") +
            "/jre/lib/amd64/server:" + System.getenv("HADOOP_HOME") + "/lib/native");
      }
      if (userEnv.containsKey("CLASSPATH")) {
        schedulerEnv.add("CLASSPATH=" + "./:" + userEnv.get("CLASSPATH") + System.getProperty("path.separator") + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      } else {
        schedulerEnv.add("CLASSPATH=" + "./:" + System.getenv("CLASSPATH") + ":" + System.getProperty("java.class.path"));
      }
      schedulerEnv.add("DMLC_ROLE=scheduler");
      schedulerEnv.add("DMLC_PS_ROOT_URI=" + dmlcPsRootUri);
      schedulerEnv.add("DMLC_PS_ROOT_PORT=" + dmlcPsRootPort);
      schedulerEnv.add(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString() + "=" + workerNum);
      schedulerEnv.add(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString() + "=" + psNum);
      schedulerEnv.add("PYTHONUNBUFFERED=1");

      LOG.info("Executing command:" + deepxCommand);
      LOG.info("DMLC_PS_ROOT_URI is " + dmlcPsRootUri);
      LOG.info("DMLC_PS_ROOT_PORT is " + dmlcPsRootPort);
      LOG.info(DeepXConstants.Environment.DEEPX_DMLC_WORKER_NUM.toString() + "=" + workerNum);
      LOG.info(DeepXConstants.Environment.DEEPX_DMLC_SERVER_NUM.toString() + "=" + psNum);

      try {
        Runtime rt = Runtime.getRuntime();
        schedulerReservedSocket.close();
        final Process xflowSchedulerProcess = rt.exec(deepxCommand, schedulerEnv.toArray(new String[schedulerEnv.size()]));
        LOG.info("Starting thread to redirect stdout of xflow scheduler process");
        Thread xflowSchedulerRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xflowSchedulerProcess.getInputStream()));
              String xflowSchedulerStdoutLog;
              while ((xflowSchedulerStdoutLog = reader.readLine()) != null) {
                LOG.info(xflowSchedulerStdoutLog);
              }
            } catch (Exception e) {
              LOG.warn("Exception in thread xflowSchedulerRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xflowSchedulerRedirectThread.start();

        LOG.info("Starting thread to redirect stderr of xflow scheduler process");
        Thread xflowSchedulerStderrRedirectThread = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              BufferedReader reader;
              reader = new BufferedReader(new InputStreamReader(xflowSchedulerProcess.getErrorStream()));
              String xflowSchedulerStderrLog;
              while ((xflowSchedulerStderrLog = reader.readLine()) != null) {
                LOG.info(xflowSchedulerStderrLog);
              }
            } catch (Exception e) {
              LOG.warn("Error in thread xflowSchedulerStderrRedirectThread");
              e.printStackTrace();
            }
          }
        });
        xflowSchedulerStderrRedirectThread.start();
      } catch (Exception e) {
        LOG.info("start xflow scheduler error " + e);
      }
    }

    //launch mpi exec process
    if (deepxAppType.equals("MPI")) {
      launchMpiExec();
      mpiExitCode = -1;
      while (mpiContainerCommand == null) {
        Utilities.sleep(statusUpdateInterval);
        try {
          mpiExitCode = mpiExecProcess.exitValue();
        } catch (IllegalThreadStateException e) {
          LOG.debug(deepxAppType.toLowerCase() + " exec process is running");
        }
        if (mpiExitCode != -1) {
          appendMessage(new Message(LogType.STDERR, deepxAppType.toLowerCase() + " exec exit with code " + mpiExitCode));
          throw new DeepXExecException(deepxAppType.toLowerCase() + "exec exit with code " + mpiExitCode);
        }
      }
    }

    if (conf.get(DeepXConfiguration.DEEPX_INPUT_STRATEGY, DeepXConfiguration.DEFAULT_DEEPX_INPUT_STRATEGY).equals("STREAM")) {
      allocateInputStreamSplits();
    } else {
      allocateInputSplits();
    }
    buildOutputLocations();
    buildContainerLocalResource();

    if (envs.containsKey(DeepXConstants.Environment.DEEPX_OUTPUTS_WORKER_INDEX.toString())) {
      outputIndex = Integer.parseInt(envs.get(DeepXConstants.Environment.DEEPX_OUTPUTS_WORKER_INDEX.toString()));
      if (outputIndex >= workerNum) {
        LOG.info("Note that user set the worker index " + outputIndex + " which to upload the output exceed the worker num " + workerNum + ". Job will upload the output of all workers after completed successfully!");
      }
    }
    if (workerNum == 1) {
      outputIndex = 0;
    }

    Map<String, String> workerContainerEnv = buildContainerEnv(DeepXConstants.WORKER);
    Map<String, String> psContainerEnv = buildContainerEnv(DeepXConstants.PS);
    List<String> workerContainerLaunchCommands = buildContainerLaunchCommand(workerMemory);
    List<String> psContainerLaunchCommands = buildContainerLaunchCommand(psMemory);

    LOG.info("Launching containers");
    int index = 0;
    for (Container container : acquiredPsContainers) {
      LOG.info("Launching ps container " + container.getId()
          + " on " + container.getNodeId().getHost() + ":" + container.getNodeId().getPort());

      //TODO launch container in special thread take with fault-tolerant
      launchContainer(containerLocalResource, psContainerEnv,
          psContainerLaunchCommands, container, index++);
      containerListener.registerContainer(new DeepXContainerId(container.getId()), DeepXConstants.PS);
    }
    if (deepxAppType.equals("MPI")) {
      index = 1;
    } else {
      index = 0;
    }

    if (chiefWorker) {
      chiefWorkerContainerId = acquiredChiefWorkerContainers.get(0).getId().toString();
    }
    if (tfEvaluator) {
      tfEvaluatorContainerId = acquiredEvaluatorWorkerContainers.get(0).getId().toString();
    }

    for (Container container : acquiredWorkerContainers) {
      LOG.info("Launching worker container " + container.getId()
          + " on " + container.getNodeId().getHost() + ":" + container.getNodeId().getPort());

      //TODO launch container in special thread take with fault-tolerant
      if (chiefWorker && container.getId().toString().equals(chiefWorkerContainerId)) {
        List<String> chiefWorkerContainerLaunchCommands = buildContainerLaunchCommand(chiefWorkerMemory);
        launchContainer(containerLocalResource, workerContainerEnv,
            chiefWorkerContainerLaunchCommands, container, index++);
      } else if (tfEvaluator && container.getId().toString().equals(tfEvaluatorContainerId)) {
        Map<String, String> evaluatorWorkerContainerEnv = buildContainerEnv(DeepXConstants.EVALUATOR);
        List<String> evaluatorWorkerContainerLaunchCommands = buildContainerLaunchCommand(evaluatorWorkerMemory);
        launchContainer(containerLocalResource, evaluatorWorkerContainerEnv, evaluatorWorkerContainerLaunchCommands, container, 0);
      } else {
        launchContainer(containerLocalResource, workerContainerEnv,
            workerContainerLaunchCommands, container, index++);
      }

      containerListener.registerContainer(new DeepXContainerId(container.getId()), DeepXConstants.WORKER);
      if (conf.getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR) && index == workerNum) {
        tfEvaluatorContainerId = container.getId().toString();
      }
    }

    String diagnostics = "";
    boolean finalSuccess;

    if (this.applicationContext.getOutputs().size() > 0) {
      Thread saveInnerModelMonitor = new Thread(new Runnable() {
        @Override
        public void run() {
          while (true) {
            try {
              Boolean startSaved = applicationContext.getStartSavingStatus();
              containerListener.setSaveInnerModel(startSaved);
              while (startSaved) {
                if (containerListener.interResultCompletedNum(containerListener.interResultTimeStamp())
                    == containerListener.getInnerSavingContainerNum()) {
                  lastSavingStatus = true;
                  if (!savingModelList.contains(containerListener.interResultTimeStamp())) {
                    savingModelList.add(containerListener.interResultTimeStamp());
                  }
                  break;
                }
                Utilities.sleep(conf.getInt(DeepXConfiguration.DEEPX_CONTAINER_HEARTBEAT_INTERVAL, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_HEARTBEAT_INTERVAL));
              }
            } catch (Exception e) {
              LOG.error("Monitor the InnerModel saving error: " + e);
            }
          }
        }
      });
      saveInnerModelMonitor.start();
    }

    try {
      if (!deepxAppType.equals("MPI")) {
        LOG.info("Waiting for train completed");
        Map<DeepXContainerId, DeepXContainerStatus> lastWorkerContainerStatus = new ConcurrentHashMap<>();
        Map<DeepXContainerId, DeepXContainerStatus> lastPsContainerStatus = new ConcurrentHashMap<>();
        while (!containerListener.isTrainCompleted()) {
          //report progress to client
          if (conf.getBoolean(DeepXConfiguration.DEEPX_REPORT_CONTAINER_STATUS, DeepXConfiguration.DEFAULT_DEEPX_REPORT_CONTAINER_STATUS)) {
            List<Container> workerContainersStatus = applicationContext.getWorkerContainers();
            List<Container> psContainersStatus = applicationContext.getPsContainers();
            for (Container container : workerContainersStatus) {
              if (!lastWorkerContainerStatus.containsKey(new DeepXContainerId(container.getId()))) {
                lastWorkerContainerStatus.put(new DeepXContainerId(container.getId()), DeepXContainerStatus.STARTED);
              }
              if (!applicationContext.getContainerStatus(new DeepXContainerId(container.getId())).equals(lastWorkerContainerStatus.get(new DeepXContainerId(container.getId())))) {
                this.appendMessage("container " + container.getId().toString() + " status is " + applicationContext.getContainerStatus(new DeepXContainerId(container.getId())), false);
                lastWorkerContainerStatus.put(new DeepXContainerId(container.getId()), applicationContext.getContainerStatus(new DeepXContainerId(container.getId())));
              }
            }
            for (Container container : psContainersStatus) {
              if (!lastPsContainerStatus.containsKey(new DeepXContainerId(container.getId()))) {
                lastPsContainerStatus.put(new DeepXContainerId(container.getId()), DeepXContainerStatus.STARTED);
              }
              if (!applicationContext.getContainerStatus(new DeepXContainerId(container.getId())).equals(lastPsContainerStatus.get(new DeepXContainerId(container.getId())))) {
                this.appendMessage("container " + container.getId().toString() + " status is " + applicationContext.getContainerStatus(new DeepXContainerId(container.getId())), false);
                lastPsContainerStatus.put(new DeepXContainerId(container.getId()), applicationContext.getContainerStatus(new DeepXContainerId(container.getId())));
              }
            }
          }
          List<Container> workerContainers = applicationContext.getWorkerContainers();
          Map<DeepXContainerId, String> clientProgress = applicationContext.getReporterProgress();
          float total = 0.0f;
          for (Container container : workerContainers) {
            String progressLog = clientProgress.get(new DeepXContainerId(container.getId()));
            if (progressLog != null && !progressLog.equals("")) {
              String[] progress = progressLog.toString().split(":");
              if (progress.length != 2) {
                this.appendMessage("progress log format error", false);
              } else {
                try {
                  Float percentProgress = Float.parseFloat(progress[1]);
                  if (percentProgress < 0.0 || percentProgress > 1.0) {
                    this.appendMessage("progress log format error", false);
                  } else {
                    total += Float.parseFloat(progress[1]);
                  }
                } catch (Exception e) {
                  this.appendMessage("progress log format error", false);
                }
              }
            }
          }
          if (total > 0.0f) {
            float finalProgress = total / workerContainers.size();
            DecimalFormat df = new DecimalFormat("0.00");
            df.setRoundingMode(RoundingMode.HALF_UP);
            this.appendMessage("reporter progress:" + df.format(finalProgress * 100) + "%", false);
            rmCallbackHandler.setProgress(finalProgress);
          }
          Utilities.sleep(statusUpdateInterval);
        }
        LOG.info("Train completed");
        containerListener.setTrainFinished();

        if (psNum > 0) {
          LOG.info("Waiting all ps containers completed");
          while (!containerListener.isAllPsContainersFinished()) {
            Utilities.sleep(statusUpdateInterval);
          }
          LOG.info("All ps/server containers completed");
        }
        finalSuccess = containerListener.isAllWorkerContainersSucceeded();
      } else {
        while (!containerListener.isAllContainerStarted()) {
          Utilities.sleep(statusUpdateInterval);
        }
        this.appendMessage(new Message(LogType.STDERR, "All containers are launched successfully"));
        while (mpiExitCode == -1) {
          Utilities.sleep(statusUpdateInterval);
          try {
            mpiExitCode = mpiExecProcess.exitValue();
          } catch (IllegalThreadStateException e) {
            LOG.debug(deepxAppType.toLowerCase() + " exec process is running");
          }
        }
        appendMessage(new Message(LogType.STDERR, "finish mpiexec with code " + mpiExitCode));
        containerListener.setAMFinished(mpiExitCode);
        LOG.info("Waiting all containers completed");
        finalSuccess = mpiExitCode == 0;
        while (!containerListener.isTrainCompleted()) {
          Utilities.sleep(statusUpdateInterval);
        }
        LOG.info("All containers completed");
      }

      if (finalSuccess) {
        if ((conf.get(DeepXConfiguration.DEEPX_OUTPUT_STRATEGY, DeepXConfiguration.DEFAULT_DEEPX_OUTPUT_STRATEGY).equals("STREAM")) && outputInfos.size() > 0) {
          LOG.info("DEEPX_OUTPUT_STRATEGY is STREAM, AM handling the final result...");
          FileSystem fs = new Path(outputInfos.get(0).getDfsLocation()).getFileSystem(conf);
          Map<DeepXContainerId, String> mapPath = applicationContext.getMapedTaskID();
          for (Container finishedContainer : acquiredWorkerContainers) {
            String taskID = mapPath.get(new DeepXContainerId(finishedContainer.getId()));
            Path tmpResultPath = new Path(outputInfos.get(0).getDfsLocation() + "/_temporary/" + finishedContainer.getId().toString()
                + "/_temporary/0/_temporary/" + taskID);
            LOG.info("tmpResultPath is " + tmpResultPath.toString());
            Path finalResultPath = new Path(outputInfos.get(0).getDfsLocation() + "/" + finishedContainer.getId().toString());
            LOG.info("finalResultPath is " + finalResultPath.toString());
            if (fs.exists(tmpResultPath)) {
              LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath.toString());
              fs.rename(tmpResultPath, finalResultPath);
            }
          }
          Path tmpPath = new Path(outputInfos.get(0).getDfsLocation() + "/_temporary/");
          if (fs.exists(tmpPath)) {
            fs.delete(tmpPath, true);
          }
          fs.createNewFile(new Path(outputInfos.get(0).getDfsLocation() + "/_SUCCESS"));
          fs.close();
        } else {
          for (OutputInfo outputInfo : outputInfos) {
            FileSystem fs = new Path(outputInfo.getDfsLocation()).getFileSystem(conf);
            Path finalResultPath = new Path(outputInfo.getDfsLocation());
            if (outputIndex >= 0) {
              Path tmpResultPath = new Path(outputInfo.getDfsLocation() + "/_temporary/" + outputInfo.getLocalLocation());
              if (fs.exists(tmpResultPath)) {
                LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath);
                fs.rename(tmpResultPath, finalResultPath);
              }
            } else {
              for (Container finishedContainer : acquiredWorkerContainers) {
                Path tmpResultPath = new Path(outputInfo.getDfsLocation() + "/_temporary/" + finishedContainer.getId().toString());
                if (fs.exists(tmpResultPath)) {
                  LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath);
                  fs.rename(tmpResultPath, finalResultPath);
                }
              }
              if (psNum > 0 && (deepxAppType.equals("TENSORFLOW") || deepxAppType.equals("LIGHTLDA"))) {
                for (Container finishedContainer : acquiredPsContainers) {
                  Path tmpResultPath = new Path(outputInfo.getDfsLocation() + "/_temporary/" + finishedContainer.getId().toString());
                  if (fs.exists(tmpResultPath)) {
                    LOG.info("Move from " + tmpResultPath.toString() + " to " + finalResultPath);
                    fs.rename(tmpResultPath, finalResultPath);
                  }
                }
              }
            }
            Path tmpPath = new Path(outputInfo.getDfsLocation() + "/_temporary/");
            if (fs.exists(tmpPath)) {
              fs.delete(tmpPath, true);
            }
            fs.createNewFile(new Path(outputInfo.getDfsLocation() + "/_SUCCESS"));
            fs.close();
          }
        }
      }
    } catch (Exception e) {
      finalSuccess = false;
      this.appendMessage("Some error occurs"
          + org.apache.hadoop.util.StringUtils.stringifyException(e), true);
      diagnostics = e.getMessage();
    }

    int appAttempts = conf.getInt(DeepXConfiguration.DEEPX_APP_MAX_ATTEMPTS, DeepXConfiguration.DEFAULT_DEEPX_APP_MAX_ATTEMPTS);

    if (appAttempts > conf.getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS)) {
      appAttempts = conf.getInt(YarnConfiguration.RM_AM_MAX_ATTEMPTS, YarnConfiguration.DEFAULT_RM_AM_MAX_ATTEMPTS);
    }

    if (!finalSuccess && applicationAttemptID.getAttemptId() < appAttempts) {
      Runtime.getRuntime().removeShutdownHook(cleanApplication);
      throw new RuntimeException("Application Failed, retry starting. Note that container memory will auto scale if user config the setting.");
    }

    this.appendMessage("Unregistered Application", true);
    unregisterApp(finalSuccess ? FinalApplicationStatus.SUCCEEDED
        : FinalApplicationStatus.FAILED, diagnostics);

    return finalSuccess;
  }

  /**
   * Internal class for running application class
   */
  private class RunningAppContext implements ApplicationContext {

    @Override
    public ApplicationId getApplicationID() {
      return applicationAttemptID.getApplicationId();
    }

    @Override
    public int getWorkerNum() {
      if (tfEvaluator) {
        return workerNum - 1;
      } else {
        return workerNum;
      }
    }

    @Override
    public int getPsNum() {
      return psNum;
    }


    @Override
    public int getWorkerMemory() {
      return workerMemory;
    }

    @Override
    public int getChiefWorkerMemory() {
      return chiefWorkerMemory;
    }

    @Override
    public int getEvaluatorWorkerMemory() {
      return evaluatorWorkerMemory;
    }

    @Override
    public int getPsMemory() {
      return psMemory;
    }

    @Override
    public int getWorkerVCores() {
      return workerVCores;
    }

    @Override
    public int getPsVCores() {
      return psVCores;
    }

    @Override
    public List<Container> getWorkerContainers() {
      return acquiredWorkerContainers;
    }

    @Override
    public List<Container> getPsContainers() {
      return acquiredPsContainers;
    }

    @Override
    public DeepXContainerStatus getContainerStatus(DeepXContainerId containerId) {
      return containerListener.getContainerStatus(containerId);
    }

    @Override
    public LinkedBlockingQueue<Message> getMessageQueue() {
      return applicationMessageQueue;
    }

    @Override
    public List<InputInfo> getInputs(DeepXContainerId containerId) {
      if (!containerId2InputInfo.containsKey(containerId)) {
        LOG.info("containerId2InputInfo not contains" + containerId.getContainerId());
        return new ArrayList<InputInfo>();
      }
      return containerId2InputInfo.get(containerId);
    }

    @Override
    public List<InputSplit> getStreamInputs(DeepXContainerId containerId) {
      if (!containerId2InputSplit.containsKey(containerId)) {
        LOG.info("containerId2InputSplit not contains" + containerId.getContainerId());
        return new ArrayList<InputSplit>();
      }
      return containerId2InputSplit.get(containerId);
    }

    @Override
    public List<OutputInfo> getOutputs() {
      return outputInfos;
    }

    @Override
    public String getTensorBoardUrl() {
      return containerListener.getTensorboardUrl();
    }

    @Override
    public Map<DeepXContainerId, String> getReporterProgress() {
      return containerListener.getReporterProgress();
    }

    @Override
    public Map<DeepXContainerId, String> getContainersAppStartTime() {
      return containerListener.getContainersAppStartTime();
    }

    @Override
    public Map<DeepXContainerId, String> getContainersAppFinishTime() {
      return containerListener.getContainersAppFinishTime();
    }

    @Override
    public Map<DeepXContainerId, String> getMapedTaskID() {
      return containerListener.getMapedTaskID();
    }

    @Override
    public Map<DeepXContainerId, ConcurrentHashMap<String, LinkedBlockingDeque<Object>>> getContainersCpuMetrics() {
      return containerListener.getContainersCpuMetrics();
    }

    @Override
    public Map<DeepXContainerId, ConcurrentHashMap<String, List<Double>>> getContainersCpuStatistics() {
      return containerListener.getContainersCpuStatistics();
    }

    @Override
    public int getSavingModelStatus() {
      return containerListener.interResultCompletedNum(containerListener.interResultTimeStamp());
    }

    @Override
    public Boolean getStartSavingStatus() {
      return startSavingModel;
    }

    @Override
    public int getSavingModelTotalNum() {
      return containerListener.getInnerSavingContainerNum();
    }

    @Override
    public void startSavingModelStatus(Boolean flag) {
      LOG.info("current savingModelStatus is " + flag);
      startSavingModel = flag;
    }

    @Override
    public Boolean getLastSavingStatus() {
      return lastSavingStatus;
    }

    @Override
    public List<Long> getModelSavingList() {
      return savingModelList;
    }

    @Override
    public String getTfEvaluatorId() {
      return tfEvaluatorContainerId;
    }

    @Override
    public String getChiefWorkerId() {
      return chiefWorkerContainerId;
    }

    @Override
    public Boolean getChiefWorker() {
      return chiefWorker;
    }

  }

  /**
   * @param args Command line args
   */
  public static void main(String[] args) {
    ApplicationMaster appMaster;
    try {
      appMaster = new ApplicationMaster();
      appMaster.init();
      if (appMaster.run()) {
        LOG.info("Application completed successfully.");
        System.exit(0);
      } else {
        LOG.info("Application failed.");
        System.exit(1);
      }
    } catch (Exception e) {
      LOG.fatal("Error running ApplicationMaster", e);
      System.exit(1);
    }
  }

}
