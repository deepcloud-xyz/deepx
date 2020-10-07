package net.deepcloud.deepx.client;

import net.deepcloud.deepx.AM.ApplicationMaster;
import net.deepcloud.deepx.api.ApplicationMessageProtocol;
import net.deepcloud.deepx.api.DeepXConstants;
import net.deepcloud.deepx.common.LogType;
import net.deepcloud.deepx.common.Message;
import net.deepcloud.deepx.common.exceptions.RequestOverLimitException;
import net.deepcloud.deepx.conf.DeepXConfiguration;
import net.deepcloud.deepx.util.Utilities;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.protocolrecords.*;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client {

  private static final Log LOG = LogFactory.getLog(Client.class);
  private ClientArguments clientArguments;
  private final DeepXConfiguration conf;
  private YarnClient yarnClient;
  private YarnClientApplication newAPP;
  private ApplicationMessageProtocol deepxClient;
  private transient AtomicBoolean isRunning;
  private StringBuffer appFilesRemotePath;
  private StringBuffer appLibJarsRemotePath;
  private ApplicationId applicationId;
  private final FileSystem dfs;
  private final ConcurrentHashMap<String, String> inputPaths;
  private final ConcurrentHashMap<String, String> outputPaths;
  private static FsPermission JOB_FILE_PERMISSION;

  private final Map<String, String> appMasterUserEnv;
  private final Map<String, String> containerUserEnv;

  private Client(String[] args) throws IOException, ParseException, ClassNotFoundException {
    this.conf = new DeepXConfiguration();
    this.dfs = FileSystem.get(conf);
    this.clientArguments = new ClientArguments(args);
    this.isRunning = new AtomicBoolean(false);
    this.appFilesRemotePath = new StringBuffer(1000);
    this.appLibJarsRemotePath = new StringBuffer(1000);
    this.inputPaths = new ConcurrentHashMap<>();
    this.outputPaths = new ConcurrentHashMap<>();
    JOB_FILE_PERMISSION = FsPermission.createImmutable((short) 0644);
    this.appMasterUserEnv = new HashMap<>();
    this.containerUserEnv = new HashMap<>();

  }

  private void init() throws IOException, YarnException {
    String appSubmitterUserName = System.getenv(ApplicationConstants.Environment.USER.name());
    if (conf.get("hadoop.job.ugi") == null) {
      UserGroupInformation ugi = UserGroupInformation.createRemoteUser(appSubmitterUserName);
      conf.set("hadoop.job.ugi", ugi.getUserName() + "," + ugi.getUserName());
    }

    conf.set(DeepXConfiguration.DEEPX_AM_MEMORY, String.valueOf(clientArguments.amMem));
    conf.set(DeepXConfiguration.DEEPX_AM_CORES, String.valueOf(clientArguments.amCores));
    conf.set(DeepXConfiguration.DEEPX_WORKER_MEMORY, String.valueOf(clientArguments.workerMemory));
    conf.set(DeepXConfiguration.DEEPX_WORKER_VCORES, String.valueOf(clientArguments.workerVCores));
    conf.set(DeepXConfiguration.DEEPX_WORKER_NUM, String.valueOf(clientArguments.workerNum));
    conf.set(DeepXConfiguration.DEEPX_CHIEF_WORKER_MEMORY, String.valueOf(clientArguments.chiefWorkerMemory));
    conf.set(DeepXConfiguration.DEEPX_EVALUATOR_WORKER_MEMORY, String.valueOf(clientArguments.evaluatorWorkerMemory));
    conf.set(DeepXConfiguration.DEEPX_PS_MEMORY, String.valueOf(clientArguments.psMemory));
    conf.set(DeepXConfiguration.DEEPX_PS_VCORES, String.valueOf(clientArguments.psVCores));
    conf.set(DeepXConfiguration.DEEPX_PS_NUM, String.valueOf(clientArguments.psNum));
    conf.set(DeepXConfiguration.DEEPX_APP_PRIORITY, String.valueOf(clientArguments.priority));
    conf.setBoolean(DeepXConfiguration.DEEPX_USER_CLASSPATH_FIRST, clientArguments.userClasspathFirst);
    conf.set(DeepXConfiguration.DEEPX_TF_BOARD_WORKER_INDEX, String.valueOf(clientArguments.boardIndex));
    conf.set(DeepXConfiguration.DEEPX_TF_BOARD_RELOAD_INTERVAL, String.valueOf(clientArguments.boardReloadInterval));
    conf.set(DeepXConfiguration.DEEPX_TF_BOARD_ENABLE, String.valueOf(clientArguments.boardEnable));
    conf.set(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR, clientArguments.boardLogDir);
    conf.set(DeepXConfiguration.DEEPX_TF_BOARD_HISTORY_DIR, clientArguments.boardHistoryDir);
    conf.set(DeepXConfiguration.DEEPX_BOARD_MODELPB, clientArguments.boardModelPB);
    conf.set(DeepXConfiguration.DEEPX_BOARD_CACHE_TIMEOUT, String.valueOf(clientArguments.boardCacheTimeout));
    conf.set(DeepXConfiguration.DEEPX_DOCKER_IMAGE, String.valueOf(clientArguments.dockerImage));
    conf.set(DeepXConfiguration.DEEPX_CONTAINER_TYPE, String.valueOf(clientArguments.containerType));
    conf.set(DeepXConfiguration.DEEPX_INPUT_STRATEGY, clientArguments.inputStrategy);
    conf.set(DeepXConfiguration.DEEPX_OUTPUT_STRATEGY, clientArguments.outputStrategy);
    conf.setBoolean(DeepXConfiguration.DEEPX_INPUTFILE_RENAME, clientArguments.isRenameInputFile);
    conf.setBoolean(DeepXConfiguration.DEEPX_INPUT_STREAM_SHUFFLE, clientArguments.inputStreamShuffle);
    conf.setClass(DeepXConfiguration.DEEPX_INPUTF0RMAT_CLASS, clientArguments.inputFormatClass, InputFormat.class);
    conf.setClass(DeepXConfiguration.DEEPX_OUTPUTFORMAT_CLASS, clientArguments.outputFormatClass, OutputFormat.class);
    conf.set(DeepXConfiguration.DEEPX_STREAM_EPOCH, String.valueOf(clientArguments.streamEpoch));
    conf.setBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, clientArguments.tfEvaluator);

    if (clientArguments.queue == null || clientArguments.queue.equals("")) {
      clientArguments.queue = appSubmitterUserName;
    }
    conf.set(DeepXConfiguration.DEEPX_APP_QUEUE, clientArguments.queue);

    if (clientArguments.confs != null) {
      setConf();
      if (containerUserEnv.size() > 0) {
        StringBuilder userEnv = new StringBuilder();
        for (String key : containerUserEnv.keySet()) {
          userEnv.append(key);
          userEnv.append("=");
          userEnv.append(containerUserEnv.get(key));
          userEnv.append("|");
        }
        conf.set(DeepXConfiguration.DEEPX_CONTAINER_EXTRAENV, userEnv.deleteCharAt(userEnv.length() - 1).toString());
      }
    }

    if ("MPI".equals(clientArguments.appType)) {
      conf.setBoolean(DeepXConfiguration.DEEPX_TF_BOARD_ENABLE, false);
      conf.set(DeepXConfiguration.DEEPX_CONTAINER_TYPE, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_TYPE);
    }

    if (conf.getInt(DeepXConfiguration.DEEPX_PS_NUM, DeepXConfiguration.DEFAULT_DEEPX_PS_NUM) == 0) {
      if ("TENSORFLOW".equals(clientArguments.appType)
          && conf.getBoolean(DeepXConfiguration.DEEPX_TF_DISTRIBUTION_STRATEGY, DeepXConfiguration.DEFAULT_DEEPX_TF_DISTRIBUTION_STRATEGY)
          && conf.getInt(DeepXConfiguration.DEEPX_WORKER_NUM, DeepXConfiguration.DEFAULT_DEEPX_WORKER_NUM) > 1) {
        conf.setBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, false);
      } else {
        conf.setBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, true);
      }
    } else {
      conf.setBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, false);
    }

    if (conf.getInt(DeepXConfiguration.DEEPX_WORKER_NUM, DeepXConfiguration.DEFAULT_DEEPX_WORKER_NUM) == 1) {
      conf.setInt(DeepXConfiguration.DEEPX_TF_BOARD_WORKER_INDEX, 0);
    }

    if (conf.getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR)) {
      if ("TENSORFLOW".equals(clientArguments.appType.toUpperCase()) && !conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, true) && conf.getInt(DeepXConfiguration.DEEPX_WORKER_NUM, DeepXConfiguration.DEFAULT_DEEPX_WORKER_NUM) > 1) {
        LOG.info("Current job has the evaluator.");
      } else {
        conf.setBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR);
      }
    }

    if (conf.get(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR, DeepXConfiguration.DEFAULT_DEEPX_TF_BOARD_LOG_DIR).indexOf("/") == 0) {
      Path tf_board_log_dir = new Path(conf.get("fs.defaultFS"), conf.get(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR));
      conf.set(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR, tf_board_log_dir.toString());
    }
    if ((conf.get(DeepXConfiguration.DEEPX_TF_BOARD_LOG_DIR).indexOf("hdfs") == 0) && (!"TENSORFLOW".equals(clientArguments.appType))) {
      LOG.warn("VisualDL not support the hdfs path for logdir. Please ensure the logdir setting is right.");
    }

    yarnClient = YarnClient.createYarnClient();
    yarnClient.init(conf);
    yarnClient.start();
    LOG.info("Requesting a new application from cluster with " + yarnClient.getYarnClusterMetrics().getNumNodeManagers() + " NodeManagers");
    newAPP = yarnClient.createApplication();
  }

  private static void showWelcome() {
    System.err.println("Welcome to DeepX");
  }

  private void setConf() {
    Enumeration<String> confSet = (Enumeration<String>) clientArguments.confs.propertyNames();
    while (confSet.hasMoreElements()) {
      String confArg = confSet.nextElement();
      if (confArg.startsWith(DeepXConstants.AM_ENV_PREFIX)) {
        String key = confArg.substring(DeepXConstants.AM_ENV_PREFIX.length()).trim();
        Utilities.addPathToEnvironment(appMasterUserEnv, key, clientArguments.confs.getProperty(confArg).trim());
      } else if (confArg.startsWith(DeepXConstants.CONTAINER_ENV_PREFIX)) {
        String key = confArg.substring(DeepXConstants.CONTAINER_ENV_PREFIX.length()).trim();
        Utilities.addPathToEnvironment(containerUserEnv, key, clientArguments.confs.getProperty(confArg).trim());
      } else {
        conf.set(confArg, clientArguments.confs.getProperty(confArg));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void assignOutput() throws IOException {
    Enumeration<String> outputs = (Enumeration<String>) clientArguments.outputs.propertyNames();
    while (outputs.hasMoreElements()) {
      String outputRemote = outputs.nextElement();
      String outputLocal = clientArguments.outputs.getProperty(outputRemote);
      if (outputLocal.equals("true")) {
        outputLocal = conf.get(DeepXConfiguration.DEEPX_OUTPUT_LOCAL_DIR, DeepXConfiguration.DEFAULT_DEEPX_OUTPUT_LOCAL_DIR);
        LOG.info("Remote output path: " + outputRemote + " not defined the local output path. Default path: output.");
      }
      Path path = new Path(outputRemote);
      if (path.getFileSystem(conf).exists(path)) {
        throw new IOException("Output path " + path + " already existed!");
      }
      if (outputPaths.containsKey(outputLocal)) {
        outputPaths.put(outputLocal, outputPaths.get(outputLocal) + "," + outputRemote);
      } else {
        outputPaths.put(outputLocal, outputRemote);
      }
      LOG.info("Local output path: " + outputLocal + " and remote output path: " + outputRemote);
    }
  }

  @SuppressWarnings("unchecked")
  private void assignInput() throws IOException {
    Enumeration<String> inputs = (Enumeration<String>) clientArguments.inputs.propertyNames();
    while (inputs.hasMoreElements()) {
      String inputRemote = inputs.nextElement();
      String inputLocal = clientArguments.inputs.getProperty(inputRemote);
      if (inputLocal.equals("true")) {
        inputLocal = "input";
      }
      for (String pathdir : StringUtils.split(inputRemote, ",")) {
        Path path = new Path(pathdir);
        FileSystem fs = path.getFileSystem(conf);
        FileStatus[] pathStatus = fs.globStatus(path);
        if (pathStatus == null || pathStatus.length <= 0) {
          throw new IOException("Input path " + path + "not existed!");
        }
      }
      if (inputPaths.containsKey(inputLocal)) {
        inputPaths.put(inputLocal, inputPaths.get(inputLocal) + "," + inputRemote);
      } else {
        inputPaths.put(inputLocal, inputRemote);
      }
      LOG.info("Local input path: " + inputLocal + " and remote input path: " + inputRemote);
    }
  }

  private static ApplicationReport getApplicationReport(ApplicationId appId, YarnClient yarnClient)
      throws YarnException, IOException {
    return yarnClient.getApplicationReport(appId);
  }

  private static ApplicationMessageProtocol getAppMessageHandler(
      YarnConfiguration conf, String appMasterAddress, int appMasterPort) throws IOException {
    ApplicationMessageProtocol appMessageHandler = null;
    if (!StringUtils.isBlank(appMasterAddress) && !appMasterAddress.equalsIgnoreCase("N/A")) {
      InetSocketAddress addr = new InetSocketAddress(appMasterAddress, appMasterPort);
      appMessageHandler = RPC.getProxy(ApplicationMessageProtocol.class, ApplicationMessageProtocol.versionID, addr, conf);
    }
    return appMessageHandler;
  }

  private void checkArguments(DeepXConfiguration conf, GetNewApplicationResponse newApplication) {
    int maxMem = newApplication.getMaximumResourceCapability().getMemory();
    LOG.info("Max mem capability of resources in this cluster " + maxMem);
    int maxVCores = newApplication.getMaximumResourceCapability().getVirtualCores();
    LOG.info("Max vcores capability of resources in this cluster " + maxVCores);

    int amMem = conf.getInt(DeepXConfiguration.DEEPX_AM_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_AM_MEMORY);
    int amCores = conf.getInt(DeepXConfiguration.DEEPX_AM_CORES, DeepXConfiguration.DEFAULT_DEEPX_AM_CORES);
    if (amMem > maxMem) {
      throw new RequestOverLimitException("AM memory requested " + amMem +
          " above the max threshold of yarn cluster " + maxMem);
    }
    if (amMem <= 0) {
      throw new IllegalArgumentException(
          "Invalid memory specified for application master, exiting."
              + " Specified memory=" + amMem);
    }
    LOG.info("Apply for am Memory " + amMem + "M");
    if (amCores > maxVCores) {
      throw new RequestOverLimitException("am vcores requested " + amCores +
          " above the max threshold of yarn cluster " + maxVCores);
    }
    if (amCores <= 0) {
      throw new IllegalArgumentException(
          "Invalid vcores specified for am, exiting."
              + "Specified vcores=" + amCores);
    }
    LOG.info("Apply for am vcores " + amCores);

    int workerNum = conf.getInt(DeepXConfiguration.DEEPX_WORKER_NUM, DeepXConfiguration.DEFAULT_DEEPX_WORKER_NUM);
    int workerMemory = conf.getInt(DeepXConfiguration.DEEPX_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    int workerVcores = conf.getInt(DeepXConfiguration.DEEPX_WORKER_VCORES, DeepXConfiguration.DEFAULT_DEEPX_WORKER_VCORES);
    if (workerNum < 1) {
      throw new IllegalArgumentException(
          "Invalid no. of worker specified, exiting."
              + " Specified container number=" + workerNum);
    }
    LOG.info("Apply for worker number " + workerNum);
    if (workerMemory > maxMem) {
      throw new RequestOverLimitException("Worker memory requested " + workerMemory +
          " above the max threshold of yarn cluster " + maxMem);
    }
    if (workerMemory <= 0) {
      throw new IllegalArgumentException(
          "Invalid memory specified for worker, exiting."
              + "Specified memory=" + workerMemory);
    }
    LOG.info("Apply for worker Memory " + workerMemory + "M");

    int chiefWorkerMemory = conf.getInt(DeepXConfiguration.DEEPX_CHIEF_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    if (chiefWorkerMemory != workerMemory) {
      if (chiefWorkerMemory > maxMem) {
        throw new RequestOverLimitException("Chief Worker memory requested " + chiefWorkerMemory +
            " above the max threshold of yarn cluster " + maxMem);
      }
      if (chiefWorkerMemory <= 0) {
        throw new IllegalArgumentException(
            "Invalid memory specified for chief worker, exiting."
                + "Specified memory=" + chiefWorkerMemory);
      }
      LOG.info("Apply for chief worker Memory " + chiefWorkerMemory + "M");
    }

    int evaluatorWorkerMemory = conf.getInt(DeepXConfiguration.DEEPX_EVALUATOR_WORKER_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_WORKER_MEMORY);
    if (evaluatorWorkerMemory != workerMemory && conf.getBoolean(DeepXConfiguration.DEEPX_TF_EVALUATOR, DeepXConfiguration.DEFAULT_DEEPX_TF_EVALUATOR)) {
      if (evaluatorWorkerMemory > maxMem) {
        throw new RequestOverLimitException("Evaluator Worker memory requested " + evaluatorWorkerMemory +
            " above the max threshold of yarn cluster " + maxMem);
      }
      if (evaluatorWorkerMemory <= 0) {
        throw new IllegalArgumentException(
            "Invalid memory specified for evaluator worker, exiting."
                + "Specified memory=" + evaluatorWorkerMemory);
      }
      LOG.info("Apply for evaluator worker Memory " + evaluatorWorkerMemory + "M");
    }

    if (workerVcores > maxVCores) {
      throw new RequestOverLimitException("Worker vcores requested " + workerVcores +
          " above the max threshold of yarn cluster " + maxVCores);
    }
    if (workerVcores <= 0) {
      throw new IllegalArgumentException(
          "Invalid vcores specified for worker, exiting."
              + "Specified vcores=" + workerVcores);
    }
    LOG.info("Apply for worker vcores " + workerVcores);

    if ("TENSORFLOW".equals(clientArguments.appType) || "MXNET".equals(clientArguments.appType) || "LIGHTLDA".equals(clientArguments.appType) || "XFLOW".equals(clientArguments.appType)) {
      Boolean single = conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE);
      int psNum = conf.getInt(DeepXConfiguration.DEEPX_PS_NUM, DeepXConfiguration.DEFAULT_DEEPX_PS_NUM);
      if (psNum < 0) {
        throw new IllegalArgumentException(
            "Invalid no. of ps specified, exiting."
                + " Specified container number=" + psNum);
      }
      LOG.info("Apply for ps number " + psNum);
      if (!single) {
        int psMemory = conf.getInt(DeepXConfiguration.DEEPX_PS_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_PS_MEMORY);
        int psVcores = conf.getInt(DeepXConfiguration.DEEPX_PS_VCORES, DeepXConfiguration.DEFAULT_DEEPX_PS_VCORES);
        if (psMemory > maxMem) {
          throw new RequestOverLimitException("ps memory requested " + psMemory +
              " above the max threshold of yarn cluster " + maxMem);
        }
        if (psMemory <= 0) {
          throw new IllegalArgumentException(
              "Invalid memory specified for ps, exiting."
                  + "Specified memory=" + psMemory);
        }
        LOG.info("Apply for ps Memory " + psMemory + "M");
        if (psVcores > maxVCores) {
          throw new RequestOverLimitException("ps vcores requested " + psVcores +
              " above the max threshold of yarn cluster " + maxVCores);
        }
        if (psVcores <= 0) {
          throw new IllegalArgumentException(
              "Invalid vcores specified for ps, exiting."
                  + "Specified vcores=" + psVcores);
        }
        LOG.info("Apply for ps vcores " + psVcores);
      }
      int limitNode = conf.getInt(DeepXConfiguration.DEEPX_EXECUTE_NODE_LIMIT, DeepXConfiguration.DEFAULT_DEEPX_EXECUTENODE_LIMIT);
      if (workerNum + psNum > limitNode) {
        throw new RequestOverLimitException("Container num requested over the limit " + limitNode);
      }
    }
  }

  private boolean submitAndMonitor() throws IOException, YarnException {
    if (clientArguments.inputs != null) {
      assignInput();
    }

    if (clientArguments.outputs != null) {
      assignOutput();
    }

    if (clientArguments.deepxCacheFiles != null) {
      String[] cacheFiles = StringUtils.split(clientArguments.deepxCacheFiles, ",");
      for (String path : cacheFiles) {
        Path pathRemote;
        if (path.contains("#")) {
          String[] paths = StringUtils.split(path, "#");
          if (paths.length != 2) {
            throw new RuntimeException("Error cacheFile path format " + path);
          }
          pathRemote = new Path(paths[0]);
        } else {
          pathRemote = new Path(path);
        }

        if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
          throw new IOException("cacheFile path " + pathRemote + " not existed!");
        }

      }
    }

    if (clientArguments.deepxCacheArchives != null) {
      String[] cacheArchives = StringUtils.split(clientArguments.deepxCacheArchives, ",");
      for (String path : cacheArchives) {
        Path pathRemote;
        if (path.contains("#")) {
          String[] paths = StringUtils.split(path, "#");
          if (paths.length != 2) {
            throw new RuntimeException("Error cacheArchives path format " + path);
          }
          pathRemote = new Path(paths[0]);
        } else {
          pathRemote = new Path(path);
        }
        if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
          throw new IOException("cacheArchive path " + pathRemote + " not existed!");
        }
      }
    }

    GetNewApplicationResponse newAppResponse = newAPP.getNewApplicationResponse();
    applicationId = newAppResponse.getApplicationId();
    LOG.info("Got new Application: " + applicationId.toString());

    Path jobConfPath = Utilities
        .getRemotePath(conf, applicationId, DeepXConstants.DEEPX_JOB_CONFIGURATION);
    FSDataOutputStream out =
        FileSystem.create(jobConfPath.getFileSystem(conf), jobConfPath,
            new FsPermission(JOB_FILE_PERMISSION));
    conf.writeXml(out);
    out.close();
    Map<String, LocalResource> localResources = new HashMap<>();
    localResources.put(DeepXConstants.DEEPX_JOB_CONFIGURATION,
        Utilities.createApplicationResource(dfs, jobConfPath, LocalResourceType.FILE));

    checkArguments(conf, newAppResponse);

    ApplicationSubmissionContext applicationContext = newAPP.getApplicationSubmissionContext();
    applicationContext.setApplicationId(applicationId);
    applicationContext.setApplicationName(clientArguments.appName);
    applicationContext.setApplicationType(clientArguments.appType);
    Path appJarSrc = new Path(clientArguments.appMasterJar);
    Path appJarDst = Utilities
        .getRemotePath(conf, applicationId, DeepXConstants.DEEPX_APPLICATION_JAR);
    LOG.info("Copying " + appJarSrc + " to remote path " + appJarDst.toString());
    dfs.copyFromLocalFile(false, true, appJarSrc, appJarDst);

    localResources.put(DeepXConstants.DEEPX_APPLICATION_JAR,
        Utilities.createApplicationResource(dfs, appJarDst, LocalResourceType.FILE));

    LOG.info("Building environments for the application master");
    Map<String, String> appMasterEnv = new HashMap<>();
    appMasterEnv.put(DeepXConstants.Environment.DEEPX_APP_NAME.toString(), clientArguments.appName);
    if (clientArguments.appType != null && !clientArguments.appType.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_APP_TYPE.toString(), clientArguments.appType);
    } else {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_APP_TYPE.toString(), DeepXConfiguration.DEFAULT_DEEPX_APP_TYPE.toUpperCase());
    }
    if (clientArguments.deepxFiles != null) {
      Path[] deepxFilesDst = new Path[clientArguments.deepxFiles.length];
      LOG.info("Copy deepx files from local filesystem to remote.");
      for (int i = 0; i < clientArguments.deepxFiles.length; i++) {
        assert (!clientArguments.deepxFiles[i].isEmpty());
        Path deepxFilesSrc = new Path(clientArguments.deepxFiles[i]);
        deepxFilesDst[i] = Utilities.getRemotePath(
            conf, applicationId, new Path(clientArguments.deepxFiles[i]).getName());
        LOG.info("Copying " + clientArguments.deepxFiles[i] + " to remote path " + deepxFilesDst[i].toString());
        dfs.copyFromLocalFile(false, true, deepxFilesSrc, deepxFilesDst[i]);
        appFilesRemotePath.append(deepxFilesDst[i].toUri().toString()).append(",");
      }
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_FILES_LOCATION.toString(),
          appFilesRemotePath.deleteCharAt(appFilesRemotePath.length() - 1).toString());

      if ((clientArguments.appType.equals("MXNET") || clientArguments.appType.equals("XFLOW")) && !conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE)) {
        String appFilesRemoteLocation = appMasterEnv.get(DeepXConstants.Environment.DEEPX_FILES_LOCATION.toString());
        String[] deepxFiles = StringUtils.split(appFilesRemoteLocation, ",");
        for (String file : deepxFiles) {
          Path path = new Path(file);
          localResources.put(path.getName(),
              Utilities.createApplicationResource(path.getFileSystem(conf),
                  path,
                  LocalResourceType.FILE));
        }
      }
    }

    String libJarsClassPath = "";
    if (clientArguments.libJars != null) {
      Path[] jarFilesDst = new Path[clientArguments.libJars.length];
      LOG.info("Copy DeepX lib jars from local filesystem to remote.");
      for (int i = 0; i < clientArguments.libJars.length; i++) {
        assert (!clientArguments.libJars[i].isEmpty());
        if (!clientArguments.libJars[i].startsWith("hdfs://")) {
          Path jarFilesSrc = new Path(clientArguments.libJars[i]);
          jarFilesDst[i] = Utilities.getRemotePath(
              conf, applicationId, new Path(clientArguments.libJars[i]).getName());
          LOG.info("Copying " + clientArguments.libJars[i] + " to remote path " + jarFilesDst[i].toString());
          dfs.copyFromLocalFile(false, true, jarFilesSrc, jarFilesDst[i]);
          appLibJarsRemotePath.append(jarFilesDst[i].toUri().toString()).append(",");
        } else {
          Path pathRemote = new Path(clientArguments.libJars[i]);
          if (!pathRemote.getFileSystem(conf).exists(pathRemote)) {
            throw new IOException("hdfs lib jars path " + pathRemote + " not existed!");
          }
          appLibJarsRemotePath.append(clientArguments.libJars[i]).append(",");
        }
      }

      String appFilesRemoteLocation = appLibJarsRemotePath.deleteCharAt(appLibJarsRemotePath.length() - 1).toString();
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_LIBJARS_LOCATION.toString(),
          appFilesRemoteLocation);

      String[] jarFiles = StringUtils.split(appFilesRemoteLocation, ",");
      for (String file : jarFiles) {
        Path path = new Path(file);
        localResources.put(path.getName(),
            Utilities.createApplicationResource(path.getFileSystem(conf),
                path,
                LocalResourceType.FILE));
        libJarsClassPath += path.getName() + ":";
      }
    }
    StringBuilder classPathEnv = new StringBuilder("${CLASSPATH}:./*");
    for (String cp : conf.getStrings(DeepXConfiguration.YARN_APPLICATION_CLASSPATH,
        DeepXConfiguration.DEFAULT_DEEPX_APPLICATION_CLASSPATH)) {
      classPathEnv.append(':');
      classPathEnv.append(cp.trim());
    }

    if (conf.getBoolean(DeepXConfiguration.DEEPX_USER_CLASSPATH_FIRST, DeepXConfiguration.DEFAULT_DEEPX_USER_CLASSPATH_FIRST)) {
      appMasterEnv.put("CLASSPATH", libJarsClassPath + classPathEnv.toString());
    } else {
      appMasterEnv.put("CLASSPATH", classPathEnv.toString() + ":" + libJarsClassPath);
    }

    appMasterEnv.put(DeepXConstants.Environment.DEEPX_STAGING_LOCATION.toString(), Utilities
        .getRemotePath(conf, applicationId, "").toString());

    appMasterEnv.put(DeepXConstants.Environment.APP_JAR_LOCATION.toString(), appJarDst.toUri().toString());
    appMasterEnv.put(DeepXConstants.Environment.DEEPX_JOB_CONF_LOCATION.toString(), jobConfPath.toString());

    if (clientArguments.launchCmd != null && !clientArguments.launchCmd.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_EXEC_CMD.toString(),
          clientArguments.launchCmd);
    } else if (clientArguments.containerType.equals("docker")) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_EXEC_CMD.toString(), "");
    } else {
      throw new IllegalArgumentException("Invalid launch cmd for the application");
    }

    if (clientArguments.deepxCacheFiles != null && !clientArguments.deepxCacheFiles.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_CACHE_FILE_LOCATION.toString(), clientArguments.deepxCacheFiles);
      if (((clientArguments.appType.equals("MXNET") || clientArguments.appType.equals("XFLOW")) && !conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE))
          || clientArguments.appType.equals("DISTXGBOOST")) {
        URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
        LOG.info("default URI is " + defaultUri.toString());
        String appCacheFilesRemoteLocation = appMasterEnv.get(DeepXConstants.Environment.DEEPX_CACHE_FILE_LOCATION.toString());
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
          localResources.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.FILE));
        }
      }
    }

    if (clientArguments.deepxCacheArchives != null && !clientArguments.deepxCacheArchives.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_CACHE_ARCHIVE_LOCATION.toString(), clientArguments.deepxCacheArchives);
      if (((clientArguments.appType.equals("MXNET") || clientArguments.appType.equals("XFLOW")) && !conf.getBoolean(DeepXConfiguration.DEEPX_MODE_SINGLE, DeepXConfiguration.DEFAULT_DEEPX_MODE_SINGLE))
          || clientArguments.appType.equals("DISTXGBOOST")) {
        URI defaultUri = new Path(conf.get("fs.defaultFS")).toUri();
        String appCacheArchivesRemoteLocation = appMasterEnv.get(DeepXConstants.Environment.DEEPX_CACHE_ARCHIVE_LOCATION.toString());
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
          LOG.info("CacheArchive remote path is " + pathRemote + " and alias name is " + aliasName);
          localResources.put(aliasName,
              Utilities.createApplicationResource(pathRemote.getFileSystem(conf),
                  pathRemote,
                  LocalResourceType.ARCHIVE));
        }
      }
    }

    Set<String> inputPathKeys = inputPaths.keySet();
    StringBuilder inputLocation = new StringBuilder(1000);
    if (inputPathKeys.size() > 0) {
      for (String key : inputPathKeys) {
        inputLocation.append(inputPaths.get(key)).
            append("#").
            append(key).
            append("|");
      }
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_INPUTS.toString(),
          inputLocation.deleteCharAt(inputLocation.length() - 1).toString());
    }

    Set<String> outputPathKeys = outputPaths.keySet();
    StringBuilder outputLocation = new StringBuilder(1000);
    if (outputPathKeys.size() > 0) {
      for (String key : outputPathKeys) {
        for (String value : StringUtils.split(outputPaths.get(key), ",")) {
          outputLocation.append(value).
              append("#").
              append(key).
              append("|");
        }
      }
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_OUTPUTS.toString(),
          outputLocation.deleteCharAt(outputLocation.length() - 1).toString());
    }

    appMasterEnv.put(DeepXConstants.Environment.DEEPX_CONTAINER_MAX_MEMORY.toString(), String.valueOf(newAppResponse.getMaximumResourceCapability().getMemory()));

    if (clientArguments.userPath != null && !clientArguments.userPath.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.USER_PATH.toString(), clientArguments.userPath);
    }

    if (clientArguments.userLD_LIBRARY_PATH != null && !clientArguments.userLD_LIBRARY_PATH.equals("")) {
      appMasterEnv.put(DeepXConstants.Environment.USER_LD_LIBRARY_PATH.toString(), clientArguments.userLD_LIBRARY_PATH);
    }

    if (clientArguments.outputIndex >= 0) {
      appMasterEnv.put(DeepXConstants.Environment.DEEPX_OUTPUTS_WORKER_INDEX.toString(), String.valueOf(clientArguments.outputIndex));
    }

    if (appMasterUserEnv.size() > 0) {
      for (String envKey : appMasterUserEnv.keySet()) {
        Utilities.addPathToEnvironment(appMasterEnv, envKey, appMasterUserEnv.get(envKey));
      }
    }

    LOG.info("Building application master launch command");
    List<String> appMasterArgs = new ArrayList<>(20);
    appMasterArgs.add("${JAVA_HOME}" + "/bin/java");
    appMasterArgs.add("-Xms" + conf.getInt(DeepXConfiguration.DEEPX_AM_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_AM_MEMORY) + "m");
    appMasterArgs.add("-Xmx" + conf.getInt(DeepXConfiguration.DEEPX_AM_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_AM_MEMORY) + "m");
    appMasterArgs.add(ApplicationMaster.class.getName());
    appMasterArgs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
        + "/" + ApplicationConstants.STDOUT);
    appMasterArgs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR
        + "/" + ApplicationConstants.STDERR);

    StringBuilder command = new StringBuilder();
    for (String arg : appMasterArgs) {
      command.append(arg).append(" ");
    }

    LOG.info("Application master launch command: " + command.toString());
    List<String> appMasterLaunchcommands = new ArrayList<>();
    appMasterLaunchcommands.add(command.toString());

    Resource capability = Records.newRecord(Resource.class);
    capability.setMemory(conf.getInt(DeepXConfiguration.DEEPX_AM_MEMORY, DeepXConfiguration.DEFAULT_DEEPX_AM_MEMORY));
    capability.setVirtualCores(conf.getInt(DeepXConfiguration.DEEPX_AM_CORES, DeepXConfiguration.DEFAULT_DEEPX_AM_CORES));
    applicationContext.setResource(capability);
    ContainerLaunchContext amContainer = ContainerLaunchContext.newInstance(
        localResources, appMasterEnv, appMasterLaunchcommands, null, null, null);

    applicationContext.setAMContainerSpec(amContainer);

    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(conf.getInt(DeepXConfiguration.DEEPX_APP_PRIORITY, DeepXConfiguration.DEFAULT_DEEPX_APP_PRIORITY));
    applicationContext.setPriority(priority);
    applicationContext.setQueue(conf.get(DeepXConfiguration.DEEPX_APP_QUEUE, DeepXConfiguration.DEFAULT_DEEPX_APP_QUEUE));
    String amNodeLabelExpression = conf.get(DeepXConfiguration.DEEPX_AM_NODELABELEXPRESSION);
    if (amNodeLabelExpression != null && amNodeLabelExpression.trim() != "") {
      try {
        Method method = applicationContext.getClass().getMethod("setNodeLabelExpression", String.class);
        method.invoke(applicationContext, amNodeLabelExpression);
      } catch (Exception e) {
        LOG.warn("Set am node label expression error: " + e);
      }
    }

    try {
      LOG.info("Submitting application to ResourceManager");
      applicationId = yarnClient.submitApplication(applicationContext);
      isRunning.set(applicationId != null);
      if (isRunning.get()) {
        LOG.info("Application submitAndMonitor succeed");
      } else {
        throw new RuntimeException("Application submitAndMonitor failed!  Application Not Running.");
      }
    } catch (YarnException e) {
      throw new RuntimeException("Application submitAndMonitor failed! Exception: " + e);
    }

    boolean isApplicationSucceed = waitCompleted();
    return isApplicationSucceed;
  }

  private boolean waitCompleted() throws IOException, YarnException {
    ApplicationReport applicationReport = getApplicationReport(applicationId, yarnClient);
    LOG.info("The url to track the job: " + applicationReport.getTrackingUrl());
    while (true) {
      assert (applicationReport != null);
      if (deepxClient == null && isRunning.get()) {
        LOG.info("Application report for " + applicationId +
            " (state: " + applicationReport.getYarnApplicationState().toString() + ")");
        deepxClient = getAppMessageHandler(conf, applicationReport.getHost(),
            applicationReport.getRpcPort());
      }

      YarnApplicationState yarnApplicationState = applicationReport.getYarnApplicationState();
      FinalApplicationStatus finalApplicationStatus = applicationReport.getFinalApplicationStatus();
      if (YarnApplicationState.FINISHED == yarnApplicationState) {
        deepxClient = null;
        isRunning.set(false);
        if (FinalApplicationStatus.SUCCEEDED == finalApplicationStatus) {
          return true;
        } else {
          LOG.info("Application has completed failed with YarnApplicationState=" + yarnApplicationState.toString() +
              " and FinalApplicationStatus=" + finalApplicationStatus.toString());
          return false;
        }
      } else if (YarnApplicationState.KILLED == yarnApplicationState
          || YarnApplicationState.FAILED == yarnApplicationState) {
        deepxClient = null;
        isRunning.set(false);
        LOG.info("Application has completed with YarnApplicationState=" + yarnApplicationState.toString() +
            " and FinalApplicationStatus=" + finalApplicationStatus.toString());
        return false;
      }

      if (deepxClient != null) {
        try {
          Message[] messages = deepxClient.fetchApplicationMessages();
          if (messages != null && messages.length > 0) {
            for (Message message : messages) {
              if (message.getLogType() == LogType.STDERR) {
                LOG.info(message.getMessage());
              } else {
                System.out.println(message.getMessage());
              }
            }
          }
        } catch (UndeclaredThrowableException e) {
          deepxClient = null;
          LOG.warn("Connecting to ApplicationMaster failed, try again later ", e);
        }
      }

      int logInterval = conf.getInt(DeepXConfiguration.DEEPX_LOG_PULL_INTERVAL, DeepXConfiguration.DEFAULT_DEEPX_LOG_PULL_INTERVAL);
      Utilities.sleep(logInterval);
      applicationReport = getApplicationReport(applicationId, yarnClient);
    }
  }

  public static void main(String[] args) {
    showWelcome();
    boolean result = false;
    try {
      LOG.info("Initializing Client");
      Client client = new Client(args);
      client.init();
      result = client.submitAndMonitor();
    } catch (Exception e) {
      LOG.fatal("Error running Client", e);
      System.exit(1);
    }
    if (result) {
      LOG.info("Application completed successfully");
      System.exit(0);
    }
    LOG.error("Application run failed!");
    System.exit(2);
  }
}
