package net.deepcloud.deepx.common;

import net.deepcloud.deepx.conf.DeepXConfiguration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.IOException;

public class UploadTask implements Runnable {

  private static final Log LOG = LogFactory.getLog(UploadTask.class);

  private Configuration conf;

  private final Path uploadDst;

  private final Path uploadSrc;

  private final int downloadRetry;

  public UploadTask(Configuration conf, Path uploadDst, Path uploadSrc) throws IOException {
    this.conf = conf;
    this.uploadDst = uploadDst;
    this.uploadSrc = uploadSrc;
    this.downloadRetry = conf.getInt(DeepXConfiguration.DEEPX_DOWNLOAD_FILE_RETRY, DeepXConfiguration.DEFAULT_DEEPX_DOWNLOAD_FILE_RETRY);
  }

  @Override
  public void run() {
    LOG.info("Upload output file from " + this.uploadSrc + " to " + this.uploadDst);
    int retry = 0;
    while (true) {
      try {
        FileSystem dfs = uploadDst.getFileSystem(conf);
        if (dfs.exists(uploadDst)) {
          LOG.info("Container remote output path " + uploadDst + " exists, so we has to delete is first.");
          dfs.delete(uploadDst);
        }
        dfs.copyFromLocalFile(false, false, uploadSrc, uploadDst);
        LOG.info("Upload output file from " + this.uploadSrc + " to " + this.uploadDst + " successful.");
        dfs.close();
        break;
      } catch (Exception e) {
        if (retry < downloadRetry) {
          LOG.warn("Upload output file from " + this.uploadSrc + " to " + this.uploadDst + " failed, retry in " + (++retry), e);
        } else {
          LOG.error("Upload output file from " + this.uploadSrc + " to " + this.uploadDst + " failed after " + downloadRetry + " retry times!", e);
          break;
        }
      }
    }
  }
}