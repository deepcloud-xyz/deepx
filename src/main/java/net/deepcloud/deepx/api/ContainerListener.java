package net.deepcloud.deepx.api;

import net.deepcloud.deepx.container.DeepXContainerId;

public interface ContainerListener {

  void registerContainer(DeepXContainerId deepxContainerId, String role);

  boolean isAllPsContainersFinished();

  boolean isTrainCompleted();

  boolean isAllWorkerContainersSucceeded();

  int interResultCompletedNum(Long lastInnerModel);

  boolean isAllContainerStarted();
}
