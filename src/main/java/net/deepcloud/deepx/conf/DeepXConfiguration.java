package net.deepcloud.deepx.conf;

import net.deepcloud.deepx.common.TextMultiOutputFormat;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.http.HttpConfig;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.OutputFormat;

public class DeepXConfiguration extends YarnConfiguration {

  private static final String DEEPX_DEFAULT_XML_FILE = "deepx-default.xml";

  private static final String DEEPX_SITE_XML_FILE = "deepx-site.xml";

  static {
    YarnConfiguration.addDefaultResource(DEEPX_DEFAULT_XML_FILE);
    YarnConfiguration.addDefaultResource(DEEPX_SITE_XML_FILE);
  }

  public DeepXConfiguration() {
    super();
  }

  public DeepXConfiguration(Configuration conf) {
    super(conf);
  }

  /**
   * Configuration used in Client
   */
  public static final String DEFAULT_DEEPX_APP_TYPE = "DEEPX";

  public static final String DEEPX_STAGING_DIR = "deepx.staging.dir";

  public static final String DEFAULT_DEEPX_STAGING_DIR = "/tmp/DeepX/staging";

  public static final String DEEPX_CONTAINER_EXTRAENV = "deepx.container.extraEnv";

  public static final String DEEPX_LOG_PULL_INTERVAL = "deepx.log.pull.interval";

  public static final int DEFAULT_DEEPX_LOG_PULL_INTERVAL = 10000;

  public static final String DEEPX_USER_CLASSPATH_FIRST = "deepx.user.classpath.first";

  public static final boolean DEFAULT_DEEPX_USER_CLASSPATH_FIRST = true;

  public static final String DEEPX_HOST_LOCAL_ENABLE = "deepx.host.local.enable";

  public static final boolean DEFAULT_DEEPX_HOST_LOCAL_ENABLE = false;

  public static final String DEEPX_AM_NODELABELEXPRESSION = "deepx.am.nodeLabelExpression";

  public static final String DEEPX_WORKER_NODELABELEXPRESSION = "deepx.worker.nodeLabelExpression";

  public static final String DEEPX_PS_NODELABELEXPRESSION = "deepx.ps.nodeLabelExpression";

  public static final String DEEPX_REPORT_CONTAINER_STATUS = "deepx.report.container.status";

  public static final boolean DEFAULT_DEEPX_REPORT_CONTAINER_STATUS = true;

  public static final String DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION = "deepx.container.mem.usage.warn.fraction";

  public static final Double DEFAULT_DEEPX_CONTAINER_MEM_USAGE_WARN_FRACTION = 0.70;

  public static final String DEEPX_AM_MEMORY = "deepx.am.memory";

  public static final int DEFAULT_DEEPX_AM_MEMORY = 1024;

  public static final String DEEPX_AM_CORES = "deepx.am.cores";

  public static final int DEFAULT_DEEPX_AM_CORES = 1;

  public static final String DEEPX_WORKER_MEMORY = "deepx.worker.memory";

  public static final int DEFAULT_DEEPX_WORKER_MEMORY = 1024;

  public static final String DEEPX_WORKER_VCORES = "deepx.worker.cores";

  public static final int DEFAULT_DEEPX_WORKER_VCORES = 1;

  public static final String DEEPX_WORKER_NUM = "deepx.worker.num";

  public static final int DEFAULT_DEEPX_WORKER_NUM = 1;

  public static final String DEEPX_CHIEF_WORKER_MEMORY = "deepx.chief.worker.memory";

  public static final String DEEPX_EVALUATOR_WORKER_MEMORY = "deepx.evaluator.worker.memory";

  public static final String DEEPX_PS_MEMORY = "deepx.ps.memory";

  public static final int DEFAULT_DEEPX_PS_MEMORY = 1024;

  public static final String DEEPX_PS_VCORES = "deepx.ps.cores";

  public static final int DEFAULT_DEEPX_PS_VCORES = 1;

  public static final String DEEPX_PS_NUM = "deepx.ps.num";

  public static final int DEFAULT_DEEPX_PS_NUM = 0;

  public static final String DEEPX_WORKER_MEM_AUTO_SCALE = "deepx.worker.mem.autoscale";

  public static final Double DEFAULT_DEEPX_WORKER_MEM_AUTO_SCALE = 0.5;

  public static final String DEEPX_PS_MEM_AUTO_SCALE = "deepx.ps.mem.autoscale";

  public static final Double DEFAULT_DEEPX_PS_MEM_AUTO_SCALE = 0.2;

  public static final String DEEPX_APP_MAX_ATTEMPTS = "deepx.app.max.attempts";

  public static final int DEFAULT_DEEPX_APP_MAX_ATTEMPTS = 1;

  public static final String DEEPX_MODE_SINGLE = "deepx.mode.single";

  public static Boolean DEFAULT_DEEPX_MODE_SINGLE = false;

  public static final String DEEPX_TF_EVALUATOR = "deepx.tf.evaluator";

  public static Boolean DEFAULT_DEEPX_TF_EVALUATOR = false;

  public static final String DEEPX_APP_QUEUE = "deepx.app.queue";

  public static final String DEFAULT_DEEPX_APP_QUEUE = "DEFAULT";

  public static final String DEEPX_APP_PRIORITY = "deepx.app.priority";

  public static final int DEFAULT_DEEPX_APP_PRIORITY = 3;

  public static final String DEEPX_OUTPUT_LOCAL_DIR = "deepx.output.local.dir";

  public static final String DEFAULT_DEEPX_OUTPUT_LOCAL_DIR = "output";

  public static final String DEEPX_INPUTF0RMAT_CLASS = "deepx.inputformat.class";

  public static final Class<? extends InputFormat> DEFAULT_DEEPX_INPUTF0RMAT_CLASS = org.apache.hadoop.mapred.TextInputFormat.class;

  public static final String DEEPX_OUTPUTFORMAT_CLASS = "deepx.outputformat.class";

  public static final Class<? extends OutputFormat> DEFAULT_DEEPX_OUTPUTF0RMAT_CLASS = TextMultiOutputFormat.class;

  public static final String DEEPX_INPUTFILE_RENAME = "deepx.inputfile.rename";

  public static final Boolean DEFAULT_DEEPX_INPUTFILE_RENAME = false;

  public static final String DEEPX_INPUT_STRATEGY = "deepx.input.strategy";

  public static final String DEFAULT_DEEPX_INPUT_STRATEGY = "DOWNLOAD";

  public static final String DEEPX_OUTPUT_STRATEGY = "deepx.output.strategy";

  public static final String DEFAULT_DEEPX_OUTPUT_STRATEGY = "UPLOAD";

  public static final String DEEPX_STREAM_EPOCH = "deepx.stream.epoch";

  public static final int DEFAULT_DEEPX_STREAM_EPOCH = 1;

  public static final String DEEPX_INPUT_STREAM_SHUFFLE = "deepx.input.stream.shuffle";

  public static final Boolean DEFAULT_DEEPX_INPUT_STREAM_SHUFFLE = false;

  public static final String DEEPX_INPUTFORMAT_CACHESIZE_LIMIT= "deepx.inputformat.cachesize.limit";

  public static final int DEFAULT_DEEPX_INPUTFORMAT_CACHESIZE_LIMIT = 100 * 1024;

  public static final String DEEPX_INPUTFORMAT_CACHE = "deepx.inputformat.cache";

  public static final boolean DEFAULT_DEEPX_INPUTFORMAT_CACHE = false;

  public static final String DEEPX_INPUTFORMAT_CACHEFILE_NAME = "deepx.inputformat.cachefile.name";

  public static final String DEFAULT_DEEPX_INPUTFORMAT_CACHEFILE_NAME = "inputformatCache.gz";

  public static final String DEEPX_INTERREAULST_DIR = "deepx.interresult.dir";

  public static final String DEFAULT_DEEPX_INTERRESULT_DIR = "/interResult_";

  public static final String DEEPX_INTERRESULT_SAVE_INC = "deepx.interresult.save.inc";

  public static final Boolean DEFAULT_DEEPX_INTERRESULT_SAVE_INC = false;


  public static final String[] DEFAULT_DEEPX_APPLICATION_CLASSPATH = {
      "$HADOOP_CONF_DIR",
      "$HADOOP_COMMON_HOME/share/hadoop/common/*",
      "$HADOOP_COMMON_HOME/share/hadoop/common/lib/*",
      "$HADOOP_HDFS_HOME/share/hadoop/hdfs/*",
      "$HADOOP_HDFS_HOME/share/hadoop/hdfs/lib/*",
      "$HADOOP_YARN_HOME/share/hadoop/yarn/*",
      "$HADOOP_YARN_HOME/share/hadoop/yarn/lib/*",
      "$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/*",
      "$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/lib/*"
  };

  public static final String DEEPX_TF_DISTRIBUTION_STRATEGY = "deepx.tf.distribution.strategy";
  public static final Boolean DEFAULT_DEEPX_TF_DISTRIBUTION_STRATEGY = false;
  public static final String DEEPX_TF_BOARD_PATH = "deepx.tf.board.path";
  public static final String DEFAULT_DEEPX_TF_BOARD_PATH = "tensorboard";
  public static final String DEEPX_TF_BOARD_WORKER_INDEX = "deepx.tf.board.worker.index";
  public static final int DEFAULT_DEEPX_TF_BOARD_WORKER_INDEX = 0;
  public static final String DEEPX_TF_BOARD_RELOAD_INTERVAL = "deepx.tf.board.reload.interval";
  public static final int DEFAULT_DEEPX_TF_BOARD_RELOAD_INTERVAL = 1;
  public static final String DEEPX_TF_BOARD_LOG_DIR = "deepx.tf.board.log.dir";
  public static final String DEFAULT_DEEPX_TF_BOARD_LOG_DIR = "eventLog";
  public static final String DEEPX_TF_BOARD_ENABLE = "deepx.tf.board.enable";
  public static final Boolean DEFAULT_DEEPX_TF_BOARD_ENABLE = true;
  public static final String DEEPX_TF_BOARD_HISTORY_DIR = "deepx.tf.board.history.dir";
  public static final String DEFAULT_DEEPX_TF_BOARD_HISTORY_DIR = "/tmp/DeepX/eventLog";
  public static final String DEEPX_BOARD_PATH = "deepx.board.path";
  public static final String DEFAULT_DEEPX_BOARD_PATH = "visualDL";
  public static final String DEEPX_BOARD_MODELPB = "deepx.board.modelpb";
  public static final String DEFAULT_DEEPX_BOARD_MODELPB = "";
  public static final String DEEPX_BOARD_CACHE_TIMEOUT = "deepx.board.cache.timeout";
  public static final int DEFAULT_DEEPX_BOARD_CACHE_TIMEOUT = 20;
  /**
   * Configuration used in ApplicationMaster
   */
  public static final String DEEPX_CONTAINER_EXTRA_JAVA_OPTS = "deepx.container.extra.java.opts";

  public static final String DEFAULT_DEEPX_CONTAINER_JAVA_OPTS_EXCEPT_MEMORY = "";

  public static final String DEEPX_ALLOCATE_INTERVAL = "deepx.allocate.interval";

  public static final int DEFAULT_DEEPX_ALLOCATE_INTERVAL = 1000;

  public static final String DEEPX_STATUS_UPDATE_INTERVAL = "deepx.status.update.interval";

  public static final int DEFAULT_DEEPX_STATUS_PULL_INTERVAL = 1000;

  public static final String DEEPX_TASK_TIMEOUT = "deepx.task.timeout";

  public static final int DEFAULT_DEEPX_TASK_TIMEOUT = 5 * 60 * 1000;

  public static final String DEEPX_LOCALRESOURCE_TIMEOUT = "deepx.localresource.timeout";

  public static final int DEFAULT_DEEPX_LOCALRESOURCE_TIMEOUT = 5 * 60 * 1000;

  public static final String DEEPX_TASK_TIMEOUT_CHECK_INTERVAL_MS = "deepx.task.timeout.check.interval";

  public static final int DEFAULT_DEEPX_TASK_TIMEOUT_CHECK_INTERVAL_MS = 3 * 1000;

  public static final String DEEPX_INTERRESULT_UPLOAD_TIMEOUT = "deepx.interresult.upload.timeout";

  public static final int DEFAULT_DEEPX_INTERRESULT_UPLOAD_TIMEOUT = 50 * 60 * 1000;

  public static final String DEEPX_MESSAGES_LEN_MAX = "deepx.messages.len.max";

  public static final int DEFAULT_DEEPX_MESSAGES_LEN_MAX = 1000;

  public static final String DEEPX_EXECUTE_NODE_LIMIT = "deepx.execute.node.limit";

  public static final int DEFAULT_DEEPX_EXECUTENODE_LIMIT = 200;

  public static final String DEEPX_CLEANUP_ENABLE = "deepx.cleanup.enable";

  public static final boolean DEFAULT_DEEPX_CLEANUP_ENABLE = true;

  public static final String DEEPX_CONTAINER_MAX_FAILURES_RATE = "deepx.container.maxFailures.rate";

  public static final double DEFAULT_DEEPX_CONTAINER_FAILURES_RATE = 0.5;

  public static final String DEEPX_ENV_MAXLENGTH = "deepx.env.maxlength";

  public static final Integer DEFAULT_DEEPX_ENV_MAXLENGTH = 102400;

  /**
   * Configuration used in Container
   */
  public static final String DEEPX_DOWNLOAD_FILE_RETRY = "deepx.download.file.retry";

  public static final int DEFAULT_DEEPX_DOWNLOAD_FILE_RETRY = 3;

  public static final String DEEPX_DOWNLOAD_FILE_THREAD_NUMS = "deepx.download.file.thread.nums";

  public static final int DEFAULT_DEEPX_DOWNLOAD_FILE_THREAD_NUMS = 10;

  public static final String DEEPX_UPLOAD_OUTPUT_THREAD_NUMS = "deepx.upload.output.thread.nums";

  public static final String DEEPX_FILE_LIST_LEVEL = "deepx.file.list.level";

  public static final int DEFAULT_DEEPX_FILE_LIST_LEVEL = 2;

  public static final String DEEPX_CONTAINER_HEARTBEAT_INTERVAL = "deepx.container.heartbeat.interval";

  public static final int DEFAULT_DEEPX_CONTAINER_HEARTBEAT_INTERVAL = 10 * 1000;

  public static final String DEEPX_CONTAINER_HEARTBEAT_RETRY = "deepx.container.heartbeat.retry";

  public static final int DEFAULT_DEEPX_CONTAINER_HEARTBEAT_RETRY = 3;

  public static final String DEEPX_CONTAINER_UPDATE_APP_STATUS_INTERVAL = "deepx.container.update.appstatus.interval";

  public static final int DEFAULT_DEEPX_CONTAINER_UPDATE_APP_STATUS_INTERVAL = 3 * 1000;

  public static final String DEEPX_CONTAINER_AUTO_CREATE_OUTPUT_DIR = "deepx.container.auto.create.output.dir";

  public static final boolean DEFAULT_DEEPX_CONTAINER_AUTO_CREATE_OUTPUT_DIR = true;

  public static final String DEEPX_RESERVE_PORT_BEGIN = "deepx.reserve.port.begin";

  public static final int DEFAULT_DEEPX_RESERVE_PORT_BEGIN = 20000;

  public static final String DEEPX_RESERVE_PORT_END = "deepx.reserve.port.end";

  public static final int DEFAULT_DEEPX_RESERVE_PORT_END = 30000;

  /**
   * Configuration used in Log Dir
   */
  public static final String DEEPX_HISTORY_LOG_DIR = "deepx.history.log.dir";

  public static final String DEFAULT_DEEPX_HISTORY_LOG_DIR = "/tmp/DeepX/history";

  public static final String DEEPX_HISTORY_LOG_DELETE_MONITOR_TIME_INTERVAL = "deepx.history.log.delete-monitor-time-interval";

  public static final int DEFAULT_DEEPX_HISTORY_LOG_DELETE_MONITOR_TIME_INTERVAL = 24 * 60 * 60 * 1000;

  public static final String DEEPX_HISTORY_LOG_MAX_AGE_MS = "deepx.history.log.max-age-ms";

  public static final int DEFAULT_DEEPX_HISTORY_LOG_MAX_AGE_MS = 24 * 60 * 60 * 1000;

  /**
   * Configuration used in Job History
   */
  public static final String DEEPX_HISTORY_ADDRESS = "deepx.history.address";

  public static final String DEEPX_HISTORY_PORT = "deepx.history.port";

  public static final int DEFAULT_DEEPX_HISTORY_PORT = 10021;

  public static final String DEFAULT_DEEPX_HISTORY_ADDRESS = "0.0.0.0:" + DEFAULT_DEEPX_HISTORY_PORT;

  public static final String DEEPX_HISTORY_WEBAPP_ADDRESS = "deepx.history.webapp.address";

  public static final String DEEPX_HISTORY_WEBAPP_PORT = "deepx.history.webapp.port";

  public static final int DEFAULT_DEEPX_HISTORY_WEBAPP_PORT = 19886;

  public static final String DEFAULT_DEEPX_HISTORY_WEBAPP_ADDRESS = "0.0.0.0:" + DEFAULT_DEEPX_HISTORY_WEBAPP_PORT;

  public static final String DEEPX_HISTORY_WEBAPP_HTTPS_ADDRESS = "deepx.history.webapp.https.address";

  public static final String DEEPX_HISTORY_WEBAPP_HTTPS_PORT = "deepx.history.webapp.https.port";

  public static final int DEFAULT_DEEPX_HISTORY_WEBAPP_HTTPS_PORT = 19885;

  public static final String DEFAULT_DEEPX_HISTORY_WEBAPP_HTTPS_ADDRESS = "0.0.0.0:" + DEFAULT_DEEPX_HISTORY_WEBAPP_HTTPS_PORT;

  public static final String DEEPX_HISTORY_BIND_HOST = "deepx.history.bind-host";

  public static final String DEEPX_HISTORY_CLIENT_THREAD_COUNT = "deepx.history.client.thread-count";

  public static final int DEFAULT_DEEPX_HISTORY_CLIENT_THREAD_COUNT = 10;

  public static final String DEEPX_HS_RECOVERY_ENABLE = "deepx.history.recovery.enable";

  public static final boolean DEFAULT_DEEPX_HS_RECOVERY_ENABLE = false;

  public static final String DEEPX_HISTORY_KEYTAB = "deepx.history.keytab";

  public static final String DEEPX_HISTORY_PRINCIPAL = "deepx.history.principal";

  /**
   * To enable https in DEEPX history server
   */
  public static final String DEEPX_HS_HTTP_POLICY = "deepx.history.http.policy";
  public static String DEFAULT_DEEPX_HS_HTTP_POLICY =
      HttpConfig.Policy.HTTP_ONLY.name();

  /**
   * The kerberos principal to be used for spnego filter for history server
   */
  public static final String DEEPX_WEBAPP_SPNEGO_USER_NAME_KEY = "deepx.webapp.spnego-principal";

  /**
   * The kerberos keytab to be used for spnego filter for history server
   */
  public static final String DEEPX_WEBAPP_SPNEGO_KEYTAB_FILE_KEY = "deepx.webapp.spnego-keytab-file";

  //Delegation token related keys
  public static final String DELEGATION_KEY_UPDATE_INTERVAL_KEY =
      "deepx.cluster.delegation.key.update-interval";
  public static final long DELEGATION_KEY_UPDATE_INTERVAL_DEFAULT =
      24 * 60 * 60 * 1000; // 1 day
  public static final String DELEGATION_TOKEN_RENEW_INTERVAL_KEY =
      "deepx.cluster.delegation.token.renew-interval";
  public static final long DELEGATION_TOKEN_RENEW_INTERVAL_DEFAULT =
      24 * 60 * 60 * 1000;  // 1 day
  public static final String DELEGATION_TOKEN_MAX_LIFETIME_KEY =
      "deepx.cluster.delegation.token.max-lifetime";
  public static final long DELEGATION_TOKEN_MAX_LIFETIME_DEFAULT =
      24 * 60 * 60 * 1000; // 7 days

  /**
   * deepx support docker conf
   */
  public static final String DEEPX_DOCKER_REGISTRY_HOST = "deepx.docker.registry.host";

  public static final String DEEPX_DOCKER_REGISTRY_PORT = "deepx.docker.registry.port";

  public static final String DEEPX_DOCKER_IMAGE= "deepx.docker.image";

  public static final String DEEPX_CONTAINER_TYPE= "deepx.container.type";
  public static final String DEFAULT_DEEPX_CONTAINER_TYPE = "yarn";

  public static final String DEEPX_DOCKER_RUN_ARGS = "deepx.docker.run.args";

  public static final String DEEPX_DOCKER_WORK_DIR = "deepx.docker.worker.dir";

  public static final String DEFAULT_DEEPX_DOCKER_WORK_DIR = "work";

  /** Configuration for mpi app */
  public static final String DEEPX_MPI_EXTRA_LD_LIBRARY_PATH = "deepx.mpi.extra.ld.library.path";

  public static final String DEEPX_MPI_INSTALL_DIR = "deepx.mpi.install.dir";
  public static final String DEFAULT_DEEPX_MPI_INSTALL_DIR = "/usr/local/openmpi/";

  public static final String DEEPX_MPI_CONTAINER_UPDATE_APP_STATUS_RETRY = "deepx.mpi.container.update.status.retry";
  public static final int DEFAULT_DEEPX_MPI_CONTAINER_UPDATE_APP_STATUS_RETRY = 3;

}
