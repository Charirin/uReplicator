/*
 * Copyright (C) 2015-2017 Uber Technologies, Inc. (streaming-data@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.stream.kafka.mirrormaker.controller.core;

import com.uber.stream.kafka.mirrormaker.controller.utils.C3QueryUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.helix.model.IdealState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkloadInfoRetriever {

  private static final Logger LOGGER = LoggerFactory.getLogger(WorkloadInfoRetriever.class);

  public static final TopicWorkload DEFAULT_WORKLOAD = new TopicWorkload(TopicWorkload.DEFAULT_BYTES_PER_SECOND,
      TopicWorkload.DEFAULT_MSGS_PER_SECOND);

  private final Map<String, TopicWorkload> _topicWorkloadMap = new ConcurrentHashMap<>();
  private TopicWorkload _defaultTopicWorkload = DEFAULT_WORKLOAD;

  private final HelixMirrorMakerManager _helixMirrorMakerManager;
  private final String _srcKafkaCluster;

  private final ScheduledExecutorService _periodicalScheduler = Executors.newSingleThreadScheduledExecutor();
  private final long _refreshPeriodInSeconds;
  private long _lastRefreshTimeMillis = 0;
  private final long _minRefreshIntervalMillis = 60000;

  private long _maxValidTimeMillis = 3600 * 1000L;

  public WorkloadInfoRetriever(HelixMirrorMakerManager helixMirrorMakerManager) {
    this._helixMirrorMakerManager = helixMirrorMakerManager;
    this._refreshPeriodInSeconds = helixMirrorMakerManager.getControllerConf().getWorkloadRefreshPeriodInSeconds();
    String srcKafkaZkPath = helixMirrorMakerManager.getControllerConf().getSrcKafkaZkPath();
    if (srcKafkaZkPath == null) {
      LOGGER.error("Source kafka Zookeeper path is not configured");
      _srcKafkaCluster = "";
    } else {
      srcKafkaZkPath = srcKafkaZkPath.trim();
      int idx = srcKafkaZkPath.lastIndexOf('/');
      _srcKafkaCluster = idx < 0 ? "" : srcKafkaZkPath.substring(idx + 1);
    }
  }

  public void start() {
    if (_srcKafkaCluster.isEmpty()) {
      LOGGER.error("Source kafka Zookeeper path is not configured. Skip to use workload retriever.");
      return;
    }
    LOGGER.info("Start workload retriever");
    try {
      refreshWorkloads();
    } catch (IOException e) {
      LOGGER.error("Got exception during retrieve initial topic workloads! ", e);
    }

    if (_refreshPeriodInSeconds > 0) {
      LOGGER.info("Trying to schedule periodical refreshing workload at rate " + _refreshPeriodInSeconds + " seconds");
      _periodicalScheduler.scheduleWithFixedDelay(new Runnable() {
        @Override
        public void run() {
          try {
            refreshWorkloads();
          } catch (Exception e) {
            LOGGER.error("Got exception during refresh topic workloads! ", e);
          }
        }
      }, _refreshPeriodInSeconds, _refreshPeriodInSeconds, TimeUnit.SECONDS);
    }
  }

  public void stop() {
    _periodicalScheduler.shutdown();
  }

  public TopicWorkload topicWorkload(String topic) {
    TopicWorkload tw = _topicWorkloadMap.get(topic);
    if (tw != null && System.currentTimeMillis() - tw.getLastUpdate() < _maxValidTimeMillis) {
      return tw;
    }
    return _defaultTopicWorkload;
  }

  public void setTopicDefaultWorkload(TopicWorkload defaultWorkload) {
    _defaultTopicWorkload = defaultWorkload;
  }

  public Map<String, TopicWorkload> getWorkloadMap() {
    return _topicWorkloadMap;
  }

  public void refreshWorkloads() throws IOException {
    if (_lastRefreshTimeMillis + _minRefreshIntervalMillis > System.currentTimeMillis()) {
      LOGGER.info("Too soon to refresh workload, skip");
      return;
    }
    LOGGER.info("Refreshing workload for source " + _srcKafkaCluster);
    _lastRefreshTimeMillis = System.currentTimeMillis();

    List<String> topics = _helixMirrorMakerManager.getTopicLists();
    Map<String, Integer> topicsPartitions = new HashMap<>();
    for (String topic : topics) {
      IdealState idealState = _helixMirrorMakerManager.getIdealStateForTopic(topic);
      if (idealState != null) {
        int partitions = idealState.getNumPartitions();
        if (partitions > 0) {
          topicsPartitions.put(topic, partitions);
        }
      }
    }
    Map<String, TopicWorkload> topicWorkloads = C3QueryUtils.retrieveTopicInRate(
        _helixMirrorMakerManager.getControllerConf().getC3Host(),
        _helixMirrorMakerManager.getControllerConf().getC3Port(), _srcKafkaCluster,
        new ArrayList<String>(topicsPartitions.keySet()));
    synchronized (_topicWorkloadMap) {
      for (Map.Entry<String, TopicWorkload> entry : topicWorkloads.entrySet()) {
        Integer partitions = topicsPartitions.get(entry.getKey());
        if (partitions != null) {
          entry.getValue().setParitions(partitions);
          _topicWorkloadMap.put(entry.getKey(), entry.getValue());
        }
      }
    }
    LOGGER.info("Current workloads: " + _topicWorkloadMap);
  }

}
