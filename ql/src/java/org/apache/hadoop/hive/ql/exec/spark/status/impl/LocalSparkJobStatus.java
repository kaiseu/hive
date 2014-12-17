/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.exec.spark.status.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hive.ql.exec.spark.Statistic.SparkStatistics;
import org.apache.hadoop.hive.ql.exec.spark.Statistic.SparkStatisticsBuilder;
import org.apache.hadoop.hive.ql.exec.spark.status.SparkJobStatus;
import org.apache.hadoop.hive.ql.exec.spark.status.SparkStageProgress;
import org.apache.hive.spark.counter.SparkCounters;
import org.apache.spark.JobExecutionStatus;
import org.apache.spark.SparkJobInfo;
import org.apache.spark.SparkStageInfo;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.executor.ShuffleReadMetrics;
import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.executor.TaskMetrics;

import scala.Option;

import com.google.common.collect.Maps;

public class LocalSparkJobStatus implements SparkJobStatus {

  private final JavaSparkContext sparkContext;
  private int jobId;
  // After SPARK-2321, we only use JobMetricsListener to get job metrics
  // TODO: remove it when the new API provides equivalent functionality
  private JobMetricsListener jobMetricsListener;
  private SparkCounters sparkCounters;
  private JavaFutureAction<Void> future;
  private Set<Integer> cachedRDDIds;

  public LocalSparkJobStatus(JavaSparkContext sparkContext, int jobId,
      JobMetricsListener jobMetricsListener, SparkCounters sparkCounters,
      Set<Integer> cachedRDDIds, JavaFutureAction<Void> future) {
    this.sparkContext = sparkContext;
    this.jobId = jobId;
    this.jobMetricsListener = jobMetricsListener;
    this.sparkCounters = sparkCounters;
    this.cachedRDDIds = cachedRDDIds;
    this.future = future;
  }

  @Override
  public int getJobId() {
    return jobId;
  }

  @Override
  public JobExecutionStatus getState() {
    // For spark job with empty source data, it's not submitted actually, so we would never
    // receive JobStart/JobEnd event in JobStateListener, use JavaFutureAction to get current
    // job state.
    if (future.isDone()) {
      return JobExecutionStatus.SUCCEEDED;
    } else {
      // SparkJobInfo may not be available yet
      SparkJobInfo sparkJobInfo = getJobInfo();
      return sparkJobInfo == null ? null : sparkJobInfo.status();
    }
  }

  @Override
  public int[] getStageIds() {
    SparkJobInfo sparkJobInfo = getJobInfo();
    return sparkJobInfo == null ? new int[0] : sparkJobInfo.stageIds();
  }

  @Override
  public Map<String, SparkStageProgress> getSparkStageProgress() {
    Map<String, SparkStageProgress> stageProgresses = new HashMap<String, SparkStageProgress>();
    for (int stageId : getStageIds()) {
      SparkStageInfo sparkStageInfo = getStageInfo(stageId);
      if (sparkStageInfo != null) {
        int runningTaskCount = sparkStageInfo.numActiveTasks();
        int completedTaskCount = sparkStageInfo.numCompletedTasks();
        int failedTaskCount = sparkStageInfo.numFailedTasks();
        int totalTaskCount = sparkStageInfo.numTasks();
        SparkStageProgress sparkStageProgress = new SparkStageProgress(
            totalTaskCount, completedTaskCount, runningTaskCount, failedTaskCount);
        stageProgresses.put(String.valueOf(sparkStageInfo.stageId()) + "_" +
            sparkStageInfo.currentAttemptId(), sparkStageProgress);
      }
    }
    return stageProgresses;
  }

  @Override
  public SparkCounters getCounter() {
    return sparkCounters;
  }

  @Override
  public SparkStatistics getSparkStatistics() {
    SparkStatisticsBuilder sparkStatisticsBuilder = new SparkStatisticsBuilder();
    // add Hive operator level statistics.
    sparkStatisticsBuilder.add(sparkCounters);
    // add spark job metrics.
    String jobIdentifier = "Spark Job[" + jobId + "] Metrics";
    Map<String, List<TaskMetrics>> jobMetric = jobMetricsListener.getJobMetric(jobId);
    if (jobMetric == null) {
      return null;
    }

    Map<String, Long> flatJobMetric = combineJobLevelMetrics(jobMetric);
    for (Map.Entry<String, Long> entry : flatJobMetric.entrySet()) {
      sparkStatisticsBuilder.add(jobIdentifier, entry.getKey(), Long.toString(entry.getValue()));
    }

    return  sparkStatisticsBuilder.build();
  }

  @Override
  public void cleanup() {
    jobMetricsListener.cleanup(jobId);
    if (cachedRDDIds != null) {
      for (Integer cachedRDDId: cachedRDDIds) {
        sparkContext.sc().unpersistRDD(cachedRDDId, false);
      }
    }
  }

  private Map<String, Long> combineJobLevelMetrics(Map<String, List<TaskMetrics>> jobMetric) {
    Map<String, Long> results = Maps.newLinkedHashMap();

    long executorDeserializeTime = 0;
    long executorRunTime = 0;
    long resultSize = 0;
    long jvmGCTime = 0;
    long resultSerializationTime = 0;
    long memoryBytesSpilled = 0;
    long diskBytesSpilled = 0;
    long bytesRead = 0;
    long remoteBlocksFetched = 0;
    long localBlocksFetched = 0;
    long fetchWaitTime = 0;
    long remoteBytesRead = 0;
    long shuffleBytesWritten = 0;
    long shuffleWriteTime = 0;
    boolean inputMetricExist = false;
    boolean shuffleReadMetricExist = false;
    boolean shuffleWriteMetricExist = false;

    for (List<TaskMetrics> stageMetric : jobMetric.values()) {
      if (stageMetric != null) {
        for (TaskMetrics taskMetrics : stageMetric) {
          if (taskMetrics != null) {
            executorDeserializeTime += taskMetrics.executorDeserializeTime();
            executorRunTime += taskMetrics.executorRunTime();
            resultSize += taskMetrics.resultSize();
            jvmGCTime += taskMetrics.jvmGCTime();
            resultSerializationTime += taskMetrics.resultSerializationTime();
            memoryBytesSpilled += taskMetrics.memoryBytesSpilled();
            diskBytesSpilled += taskMetrics.diskBytesSpilled();
            if (!taskMetrics.inputMetrics().isEmpty()) {
              inputMetricExist = true;
              bytesRead += taskMetrics.inputMetrics().get().bytesRead();
            }
            Option<ShuffleReadMetrics> shuffleReadMetricsOption = taskMetrics.shuffleReadMetrics();
            if (!shuffleReadMetricsOption.isEmpty()) {
              shuffleReadMetricExist = true;
              remoteBlocksFetched += shuffleReadMetricsOption.get().remoteBlocksFetched();
              localBlocksFetched += shuffleReadMetricsOption.get().localBlocksFetched();
              fetchWaitTime += shuffleReadMetricsOption.get().fetchWaitTime();
              remoteBytesRead += shuffleReadMetricsOption.get().remoteBytesRead();
            }
            Option<ShuffleWriteMetrics> shuffleWriteMetricsOption = taskMetrics.shuffleWriteMetrics();
            if (!shuffleWriteMetricsOption.isEmpty()) {
              shuffleWriteMetricExist = true;
              shuffleBytesWritten += shuffleWriteMetricsOption.get().shuffleBytesWritten();
              shuffleWriteTime += shuffleWriteMetricsOption.get().shuffleWriteTime();
            }
          }
        }
      }
    }

    results.put("EexcutorDeserializeTime", executorDeserializeTime);
    results.put("ExecutorRunTime", executorRunTime);
    results.put("ResultSize", resultSize);
    results.put("JvmGCTime", jvmGCTime);
    results.put("ResultSerializationTime", resultSerializationTime);
    results.put("MemoryBytesSpilled", memoryBytesSpilled);
    results.put("DiskBytesSpilled", diskBytesSpilled);
    if (inputMetricExist) {
      results.put("BytesRead", bytesRead);
    }
    if (shuffleReadMetricExist) {
      results.put("RemoteBlocksFetched", remoteBlocksFetched);
      results.put("LocalBlocksFetched", localBlocksFetched);
      results.put("TotalBlocksFetched", localBlocksFetched + remoteBlocksFetched);
      results.put("FetchWaitTime", fetchWaitTime);
      results.put("RemoteBytesRead", remoteBytesRead);
    }
    if (shuffleWriteMetricExist) {
      results.put("ShuffleBytesWritten", shuffleBytesWritten);
      results.put("ShuffleWriteTime", shuffleWriteTime);
    }
    return results;
  }

  private SparkJobInfo getJobInfo() {
    return sparkContext.statusTracker().getJobInfo(jobId);
  }

  private SparkStageInfo getStageInfo(int stageId) {
    return sparkContext.statusTracker().getStageInfo(stageId);
  }
}
