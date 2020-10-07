package net.deepcloud.deepx.api;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.yarn.api.ApplicationConstants;

@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface DeepXConstants {

  String DEEPX_JOB_CONFIGURATION = "core-site.xml";

  String DEEPX_APPLICATION_JAR = "AppMaster.jar";

  String WORKER = "worker";

  String PS = "ps";

  String EVALUATOR = "evaluator";

  String CHIEF = "chief";

  String STREAM_INPUT_DIR = "mapreduce.input.fileinputformat.inputdir";

  String STREAM_OUTPUT_DIR = "mapreduce.output.fileoutputformat.outputdir";

  String AM_ENV_PREFIX = "deepx.am.env.";

  String CONTAINER_ENV_PREFIX = "deepx.container.env.";

  enum Environment {
    HADOOP_USER_NAME("HADOOP_USER_NAME"),

    DEEPX_APP_TYPE("DEEPX_APP_TYPE"),

    DEEPX_APP_NAME("DEEPX_APP_NAME"),

    DEEPX_CONTAINER_MAX_MEMORY("DEEPX_MAX_MEM"),

    DEEPX_LIBJARS_LOCATION("DEEPX_LIBJARS_LOCATION"),

    DEEPX_TF_ROLE("TF_ROLE"),

    DEEPX_TF_INDEX("TF_INDEX"),

    DEEPX_TF_CLUSTER_DEF("TF_CLUSTER_DEF"),

    DEEPX_DMLC_WORKER_NUM("DMLC_NUM_WORKER"),

    DEEPX_DMLC_SERVER_NUM("DMLC_NUM_SERVER"),

    DEEPX_LIGHTGBM_WORKER_NUM("LIGHTGBM_NUM_WORKER"),

    DEEPX_LIGHTLDA_WORKER_NUM("LIGHTLDA_NUM_WORKER"),

    DEEPX_LIGHTLDA_PS_NUM("LIGHTLDA_NUM_PS"),

    DEEPX_INPUT_FILE_LIST("INPUT_FILE_LIST"),

    DEEPX_STAGING_LOCATION("DEEPX_STAGING_LOCATION"),

    DEEPX_CACHE_FILE_LOCATION("DEEPX_CACHE_FILE_LOCATION"),

    DEEPX_CACHE_ARCHIVE_LOCATION("DEEPX_CACHE_ARCHIVE_LOCATION"),

    DEEPX_FILES_LOCATION("DEEPX_FILES_LOCATION"),

    APP_JAR_LOCATION("APP_JAR_LOCATION"),

    DEEPX_JOB_CONF_LOCATION("DEEPX_JOB_CONF_LOCATION"),

    DEEPX_EXEC_CMD("DEEPX_EXEC_CMD"),

    USER_PATH("USER_PATH"),

    USER_LD_LIBRARY_PATH("USER_LD_LIBRARY_PATH"),

    DEEPX_OUTPUTS("DEEPX_OUTPUTS"),

    DEEPX_OUTPUTS_WORKER_INDEX("DEEPX_OUTPUT_WORKER_INDEX"),

    DEEPX_INPUTS("DEEPX_INPUTS"),

    DEEPX_INPUT_PATH("DEEPX_INPUT_PATH"),

    CONTAINER_COMMAND("CONTAINER_COMMAND"),

    MPI_EXEC_DIR("MPI_EXEC_DIR"),

    MPI_FILES_LINKS("LINKS"),

    APPMASTER_HOST("APPMASTER_HOST"),

    APPMASTER_PORT("APPMASTER_PORT"),

    APP_ID("APP_ID"),

    APP_ATTEMPTID("APP_ATTEMPTID");

    private final String variable;

    Environment(String variable) {
      this.variable = variable;
    }

    public String key() {
      return variable;
    }

    public String toString() {
      return variable;
    }

    public String $() {
      if (Shell.WINDOWS) {
        return "%" + variable + "%";
      } else {
        return "$" + variable;
      }
    }

    @InterfaceAudience.Public
    @InterfaceStability.Unstable
    public String $$() {
      return ApplicationConstants.PARAMETER_EXPANSION_LEFT +
          variable +
          ApplicationConstants.PARAMETER_EXPANSION_RIGHT;
    }
  }
}
