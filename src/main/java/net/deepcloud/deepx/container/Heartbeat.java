package net.deepcloud.deepx.container;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.deepcloud.deepx.api.ApplicationContainerProtocol;
import net.deepcloud.deepx.api.DeepXConstants;
import net.deepcloud.deepx.common.*;
import net.deepcloud.deepx.conf.DeepXConfiguration;
import net.deepcloud.deepx.util.Utilities;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Heartbeat extends Thread {

  private static final Log LOG = LogFactory.getLog(Heartbeat.class);

  private ApplicationContainerProtocol protocol;

  private Configuration conf;

  private DeepXContainerId containerId;

  private HeartbeatRequest heartbeatRequest;

  private HeartbeatResponse heartbeatResponse;

  private int heartbeatInterval;

  private int heartbeatRetryMax;

  private Long lastInnerModelTimeStamp;

  private Long previousInnerModelTimeStamp;

  private Boolean IsDeepXTrainCompleted;

  private int outputIndex;

  private int index;

  private String role;

  private int downloadRetry;

  private int uploadTimeOut;

  public Heartbeat(ApplicationContainerProtocol protocol, Configuration conf,
                   DeepXContainerId deepxContainerId, int outputIndex, int index, String role) {
    this.protocol = protocol;
    this.conf = conf;
    this.containerId = deepxContainerId;
    this.heartbeatRequest = new HeartbeatRequest();
    this.heartbeatResponse = new HeartbeatResponse();
    this.lastInnerModelTimeStamp = Long.MIN_VALUE;
    this.previousInnerModelTimeStamp = Long.MIN_VALUE;
    this.IsDeepXTrainCompleted = false;
    this.heartbeatInterval = this.conf.getInt(DeepXConfiguration.DEEPX_CONTAINER_HEARTBEAT_INTERVAL, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_HEARTBEAT_INTERVAL);
    this.heartbeatRetryMax = this.conf.getInt(DeepXConfiguration.DEEPX_CONTAINER_HEARTBEAT_RETRY, DeepXConfiguration.DEFAULT_DEEPX_CONTAINER_HEARTBEAT_RETRY);
    this.downloadRetry = this.conf.getInt(DeepXConfiguration.DEEPX_DOWNLOAD_FILE_RETRY, DeepXConfiguration.DEFAULT_DEEPX_DOWNLOAD_FILE_RETRY);
    this.uploadTimeOut = this.conf.getInt(DeepXConfiguration.DEEPX_INTERRESULT_UPLOAD_TIMEOUT, DeepXConfiguration.DEFAULT_DEEPX_INTERRESULT_UPLOAD_TIMEOUT);
    this.outputIndex = outputIndex;
    this.index = index;
    this.role = role;
  }

  @SuppressWarnings("static-access")
  public void run() {
    while (!Thread.currentThread().interrupted()) {
      heartbeatResponse = heartbeatWithRetry();
      heartbeatResponseHandle(heartbeatResponse);
      Utilities.sleep(heartbeatInterval);
    }
  }

  public void setContainerStatus(DeepXContainerStatus containerStatus) {
    this.heartbeatRequest.setDeepXContainerStatus(containerStatus);
  }

  public void setInnerModelSavedStatus(Boolean flag) {
    this.heartbeatRequest.setInnerModelSavedStatus(flag);
  }

  public void setProgressLog(String deepxProgressLog) {
    this.heartbeatRequest.setProgressLog(deepxProgressLog);
  }

  public void setContainersStartTime(String startTime) {
    this.heartbeatRequest.setContainersStartTime(startTime);
  }

  public void setContainersFinishTime(String finishTime) {
    this.heartbeatRequest.setContainersFinishTime(finishTime);
  }

  public Boolean isDeepXTrainCompleted() {
    return this.IsDeepXTrainCompleted;
  }

  public HeartbeatResponse heartbeatWithRetry() {
    int retry = 0;
    while (true) {
      try {
        heartbeatResponse = protocol.heartbeat(containerId, heartbeatRequest);
        LOG.debug("Send HeartBeat to ApplicationMaster");
        return heartbeatResponse;
      } catch (Exception e) {
        retry++;
        if (retry <= heartbeatRetryMax) {
          LOG.warn("Send heartbeat to ApplicationMaster failed in retry " + retry);
          Utilities.sleep(heartbeatInterval);
        } else {
          LOG.warn("Send heartbeat to ApplicationMaster failed in retry " + retry
              + ", container will suicide!", e);
          System.exit(1);
        }
      }
    }
  }

  public void heartbeatResponseHandle(HeartbeatResponse heartbeatResponse) {
    LOG.debug("Received the heartbeat response from the AM. CurrentJob finished " + heartbeatResponse.getIsDeepXTrainCompleted()
        + " , currentInnerModelSavedTimeStamp is " + heartbeatResponse.getInnerModelTimeStamp());
    if (!heartbeatResponse.getIsDeepXTrainCompleted()) {
      if (!heartbeatResponse.getInnerModelTimeStamp().equals(lastInnerModelTimeStamp)) {
        previousInnerModelTimeStamp = lastInnerModelTimeStamp;
        lastInnerModelTimeStamp = heartbeatResponse.getInnerModelTimeStamp();
        ExecutorService executor = Executors.newFixedThreadPool(
            conf.getInt(DeepXConfiguration.DEEPX_UPLOAD_OUTPUT_THREAD_NUMS, DeepXConfiguration.DEFAULT_DEEPX_DOWNLOAD_FILE_THREAD_NUMS),
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("Upload-InnerModel-Thread #%d")
                .build()
        );

        try {
          for (OutputInfo outputs : protocol.getOutputLocation()) {
            LOG.info("Output path: " + outputs.getLocalLocation() + "#" + outputs.getDfsLocation());
            FileSystem localFs = FileSystem.getLocal(conf);
            Path localPath = new Path(outputs.getLocalLocation());
            Path remotePath = new Path(outputs.getDfsLocation()
                + conf.get(DeepXConfiguration.DEEPX_INTERREAULST_DIR, DeepXConfiguration.DEFAULT_DEEPX_INTERRESULT_DIR)
                + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date(lastInnerModelTimeStamp))
                + "/" + containerId.toString());
            LOG.info("InnerModel path:" + remotePath);
            FileSystem dfs = remotePath.getFileSystem(conf);
            if (dfs.exists(remotePath)) {
              LOG.info("Container remote output path " + remotePath + "exists, so we has to delete is first.");
              dfs.delete(remotePath);
            }
            dfs.close();
            if (localFs.exists(localPath)) {
              String splitDir = localPath.toString();
              if (!localPath.toString().endsWith("/")) {
                splitDir = localPath.toString() + "/";
              } else if (!localPath.toString().startsWith("/")) {
                splitDir = "/" + localPath.toString();
              }
              List<FileStatus> uploadFiles = Utilities.listStatusRecursively(localPath,
                  localFs, null, conf.getInt(DeepXConfiguration.DEEPX_FILE_LIST_LEVEL, DeepXConfiguration.DEFAULT_DEEPX_FILE_LIST_LEVEL));
              for (FileStatus uploadFile : uploadFiles) {
                if (conf.getBoolean(DeepXConfiguration.DEEPX_INTERRESULT_SAVE_INC, DeepXConfiguration.DEFAULT_DEEPX_INTERRESULT_SAVE_INC) && uploadFile.getModificationTime() <= previousInnerModelTimeStamp) {
                  LOG.debug("current file " + uploadFile.getPath().toString() + " not changed after last saved.");
                } else {
                  Path uploadPath = uploadFile.getPath();
                  LOG.debug("upload:" + uploadPath + " \tfrom\tlocalPath:" + localPath);
                  String[] fileName = StringUtils.splitByWholeSeparator(uploadPath.toString() + "/", splitDir, 2);
                  if (fileName.length == 2) {
                    Path uploadDstPath = new Path(remotePath.toString() + "/" + fileName[1]);
                    UploadTask uploadTask = new UploadTask(conf, uploadDstPath, uploadPath);
                    LOG.debug("upload from " + uploadPath + " to " + uploadDstPath);
                    executor.submit(uploadTask);
                  } else {
                    LOG.error("Get the local path error");
                  }
                }
              }
            }
            localFs.close();
          }
        } catch (IOException e) {
          LOG.error("container " + containerId + "upload the interResult error:" + e);
        }
        executor.shutdown();
        Boolean isUploadInnerFinish = false;
        try {
          isUploadInnerFinish = executor.awaitTermination(uploadTimeOut, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          LOG.error("UploadInnerThread exception: " + e);
        }
        if (isUploadInnerFinish) {
          LOG.info("container " + containerId + " currentStatus:" + heartbeatRequest.getDeepXContainerStatus() + " , savedModel completed");
          setInnerModelSavedStatus(true);
        } else {
          List<Runnable> list = executor.shutdownNow();
          LOG.info("InnerModel Upload timeout, more than set :" + uploadTimeOut + " s. Already has the " + list.size() + " task not executed.");
          setInnerModelSavedStatus(true);
        }
      }
      LOG.debug("container " + containerId + " currentStatus:" + heartbeatRequest.getDeepXContainerStatus());
    }
    this.IsDeepXTrainCompleted = heartbeatResponse.getIsDeepXTrainCompleted();
  }
}
