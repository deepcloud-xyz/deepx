package net.deepcloud.deepx.common;

import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class HeartbeatRequest implements Writable {
  private DeepXContainerStatus deepxContainerStatus;
  private BooleanWritable interResultSavedStatus;
  private String progressLog;
  private String containersStartTime;
  private String containersFinishTime;

  public HeartbeatRequest() {
    deepxContainerStatus = DeepXContainerStatus.UNDEFINED;
    interResultSavedStatus = new BooleanWritable(false);
    progressLog = "";
    containersStartTime = "";
    containersFinishTime = "";
  }

  public void setDeepXContainerStatus(DeepXContainerStatus deepxContainerStatus) {
    this.deepxContainerStatus = deepxContainerStatus;
  }

  public DeepXContainerStatus getDeepXContainerStatus() {
    return this.deepxContainerStatus;
  }

  public void setInnerModelSavedStatus(Boolean savedStatus) {
    this.interResultSavedStatus.set(savedStatus);
  }

  public Boolean getInnerModelSavedStatus() {
    return this.interResultSavedStatus.get();
  }

  public void setProgressLog(String deepxProgress) {
    this.progressLog = deepxProgress;
  }

  public String getProgressLog() {
    return this.progressLog;
  }

  public void setContainersStartTime(String startTime) {
    this.containersStartTime = startTime;
  }

  public String getContainersStartTime() {
    return this.containersStartTime;
  }

  public void setContainersFinishTime(String finishTime) {
    this.containersFinishTime = finishTime;
  }

  public String getContainersFinishTime() {
    return this.containersFinishTime;
  }

  @Override
  public void write(DataOutput dataOutput) throws IOException {
    WritableUtils.writeEnum(dataOutput, this.deepxContainerStatus);
    interResultSavedStatus.write(dataOutput);
    Text.writeString(dataOutput, this.progressLog);
    Text.writeString(dataOutput, this.containersStartTime);
    Text.writeString(dataOutput, this.containersFinishTime);
  }

  @Override
  public void readFields(DataInput dataInput) throws IOException {
    this.deepxContainerStatus = WritableUtils.readEnum(dataInput, DeepXContainerStatus.class);
    interResultSavedStatus.readFields(dataInput);
    this.progressLog = Text.readString(dataInput);
    this.containersStartTime = Text.readString(dataInput);
    this.containersFinishTime = Text.readString(dataInput);
  }

}
