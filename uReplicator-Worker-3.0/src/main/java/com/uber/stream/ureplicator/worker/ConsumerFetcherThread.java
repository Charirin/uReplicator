/*
 * Copyright (C) 2015-2019 Uber Technologies, Inc. (streaming-data@uber.com)
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
package com.uber.stream.ureplicator.worker;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import com.uber.stream.ureplicator.common.KafkaUReplicatorMetricsReporter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import kafka.utils.ShutdownableThread;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetcher thread that fetches data for multiple topic partitions
 */
public class ConsumerFetcherThread extends ShutdownableThread {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerFetcherThread.class);

  // TODO: use GuardedBy
  // updateMapLock guards partitionMap and kafkaConsumer
  private final Object updateMapLock = new Object();
  // partitionMapLock guards partitionAddMap and partitionDeleteMap
  private final Object partitionMapLock = new Object();
  private final Map<TopicPartition, PartitionOffsetInfo> partitionMap = new ConcurrentHashMap<>();
  // partitionAddMap stores the topic partition pending add
  // partitionDeleteMap stores the topic partition pending delete
  // the purpose of using these two fields is for non-blocking add/delete topic partition
  // since partitionMap need to be guard to avoid concurrent topic partition assign on kafkaConsumer
  private final Map<TopicPartition, PartitionOffsetInfo> partitionAddMap = new ConcurrentHashMap<>();
  private final Map<TopicPartition, Boolean> partitionDeleteMap = new ConcurrentHashMap<>();
  private final Map<TopicPartition, Long> partitionResetOffsetMap = new ConcurrentHashMap<>();
  private final KafkaConsumer kafkaConsumer;
  private long lastDumpTime = 0L;
  private final int offsetMonitorMs;
  private final int fetchBackOffMs;
  private final int pollTimeoutMs;
  private final RateLimiter rateLimiter;
  /**
   * Constructor
   *
   * @param threadName fetcher thread name
   * @param properties kafka consumer configuration properties
   * @param rateLimiter consumer rate limiter
   */
  public ConsumerFetcherThread(String threadName, CustomizedConsumerConfig properties, RateLimiter rateLimiter) {
    super(threadName, true);
    this.fetchBackOffMs = properties.getFetcherThreadBackoffMs();
    this.offsetMonitorMs = properties.getOffsetMonitorInterval();
    this.pollTimeoutMs = properties.getPollTimeoutMs();
    this.kafkaConsumer = new KafkaConsumer(properties);
    KafkaUReplicatorMetricsReporter.get()
        .registerKafkaMetrics("consumer." + threadName, kafkaConsumer.metrics());
    this.rateLimiter = rateLimiter;
  }

  @Override
  public void doWork() {
    try {
      ConsumerRecords records = null;
      synchronized (partitionMapLock) {
        boolean topicChanged = refreshPartitionMap();
        if (topicChanged) {
          LOGGER.info("[{}]Assignment changed, partitionMap {}, partitionResetOffsetMap: {} ",
              getName(),
              partitionMap.keySet(),
              partitionResetOffsetMap);
          kafkaConsumer.assign(partitionMap.keySet());
          for (TopicPartition tp : partitionResetOffsetMap.keySet()) {
            long offset = partitionResetOffsetMap.get(tp);
            if (offset >= 0) {
              kafkaConsumer.seek(tp, offset);
            }
          }
          partitionResetOffsetMap.clear();
        }
        if (partitionMap.size() != 0) {
          records = kafkaConsumer.poll(pollTimeoutMs);
        }
      }
      if (partitionMap.size() == 0 || records == null || records.isEmpty()) {
        Thread.sleep(fetchBackOffMs);
        return;
      }

      processFetchedData(records);
      logTopicPartitionInfo();
    } catch (Throwable e) {
      LOGGER.error("[{}]: Catch Throwable exception: ", getName(), e.getMessage(), e);
      // reset offset to fetchoffset to avoid data losss
      for (PartitionOffsetInfo offsetInfo : partitionMap.values()) {
        if (offsetInfo.fetchOffset() >= 0) {
          kafkaConsumer.seek(offsetInfo.topicPartition(), offsetInfo.fetchOffset());
        }
      }
    }
  }

  private void processFetchedData(ConsumerRecords consumerRecords) throws InterruptedException {
    Set<TopicPartition> partitions = consumerRecords.partitions();
    for (TopicPartition tp : partitions) {
      PartitionOffsetInfo partitionOffsetInfo = partitionMap.getOrDefault(tp, null);
      if (partitionOffsetInfo == null) {
        continue;
      }

      List<ConsumerRecord> records = consumerRecords.records(tp);
      if (records.size() != 0 && !partitionOffsetInfo.fetchedEndBounded()) {
        if (rateLimiter != null) {
          rateLimiter.acquire(records.size());
        }
        partitionOffsetInfo.enqueue(records);
      }
    }
  }

  private void logTopicPartitionInfo() {
    if ((System.currentTimeMillis() - lastDumpTime) < offsetMonitorMs || partitionMap.size() == 0) {
      return;
    }
    Map<TopicPartition, Long> endOffsets = kafkaConsumer.endOffsets(partitionMap.keySet());
    if (endOffsets == null) {
      LOGGER.error("[{}]: Failed to find endOffsets for topic partitions: {}", getName(),
          partitionMap.keySet());
      return;
    }
    List<String> topicOffsetPairs = new ArrayList<>();
    for (PartitionOffsetInfo poi : partitionMap.values()) {
      if (endOffsets.containsKey(poi.topicPartition())) {
        Long lag = endOffsets.get(poi.topicPartition()) - poi.fetchOffset();
        topicOffsetPairs
            .add(
                String.format("%s:%d:%d", poi.topicPartition().toString(), poi.fetchOffset(), lag));
      } else {
        LOGGER.warn("[{}]: Failed to find endOffsets for topic partition : {}", getName(), poi);
        continue;
      }
    }
    LOGGER.info("[{}]: Topic partitions dump in fetcher thread: {}", getName(),
        String.join(",", topicOffsetPairs));
    lastDumpTime = System.currentTimeMillis();
  }

  private boolean refreshPartitionMap() {
    synchronized (updateMapLock) {
      boolean partitionChange = false;

      for (TopicPartition addTp : partitionAddMap.keySet()) {
        if (!partitionMap.containsKey(addTp)) {
          // Avoid duplicate message
          partitionMap.put(addTp, partitionAddMap.get(addTp));
          partitionResetOffsetMap.put(addTp, partitionAddMap.get(addTp).startingOffset());
          partitionChange = true;
        }
      }
      partitionAddMap.clear();

      for (TopicPartition removeTp : partitionDeleteMap.keySet()) {
        if (partitionMap.containsKey(removeTp)) {
          partitionMap.remove(removeTp);
          partitionChange = true;
        }
      }
      partitionDeleteMap.clear();
      return partitionChange;
    }
  }

  public void addPartitions(Map<TopicPartition, PartitionOffsetInfo> partitionAndOffsets) {
    LOGGER.trace("[{}]: Enter addPartitions set {}", getName(),
        partitionAndOffsets.keySet());
    synchronized (updateMapLock) {
      for (TopicPartition tp : partitionAndOffsets.keySet()) {
        if (partitionMap.containsKey(tp)) {
          LOGGER.warn("[{}]: TopicPartition {} already in current thread", getName(), tp);
        }
        partitionAddMap.put(tp, partitionAndOffsets.get(tp));
        if (partitionDeleteMap.containsKey(tp)) {
          partitionDeleteMap.remove(tp);
        }
      }
    }
    LOGGER.trace("[{}]: Finish addPartitions set {} ", getName(),
        partitionAndOffsets.keySet());
  }

  public void removePartitions(Set<TopicPartition> topicAndPartitions) {
    LOGGER.trace("[{}]: Enter removePartitions set {}", getName(),
        topicAndPartitions);
    synchronized (updateMapLock) {
      for (TopicPartition tp : topicAndPartitions) {
        if (!partitionMap.containsKey(tp)) {
          continue;
        }
        partitionDeleteMap.put(tp, true);
        if (partitionAddMap.containsKey(tp)) {
          partitionAddMap.remove(tp);
        }
      }
    }
    LOGGER.trace("[{}]: Finish removePartitions set {} ", getName(),
        topicAndPartitions);
  }

  public Set<TopicPartition> getTopicPartitions() {
    return ImmutableSet.copyOf(partitionMap.keySet());
  }

  public void shutdown() {
    if (isShutdownComplete()) {
      return;
    }
    LOGGER.info("[{}] Shutting down fetcher thread", getName());
    initiateShutdown();
    awaitShutdown();
  }

  @Override
  public void awaitShutdown() {
    super.awaitShutdown();
    synchronized (partitionMapLock) {
      kafkaConsumer.close();
      partitionMap.clear();
      partitionDeleteMap.clear();
      partitionAddMap.clear();
      partitionResetOffsetMap.clear();
    }
    LOGGER.info("[{}] Shutdown fetcher thread finished", getName());
  }
}
