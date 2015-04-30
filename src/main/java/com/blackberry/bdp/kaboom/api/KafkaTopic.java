/**
 * Copyright 2014 BlackBerry, Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package com.blackberry.bdp.kaboom.api;

import java.util.ArrayList;
import java.util.List;
import kafka.javaapi.PartitionMetadata;
import kafka.javaapi.OffsetRequest;
import kafka.javaapi.TopicMetadata;
import kafka.javaapi.TopicMetadataRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.TopicMetadataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.cluster.Broker;
import kafka.common.TopicAndPartition;

public class KafkaTopic {

	private static final Logger LOG = LoggerFactory.getLogger(KafkaTopic.class);

	private final String name;
	private final List<KafkaPartition> partitions = new ArrayList<>();
	private final short errorCode;
	private final int sizeInBytes;

	public KafkaTopic(TopicMetadata metadata) {
		this.name = metadata.topic();
		this.errorCode = metadata.errorCode();
		this.sizeInBytes = metadata.sizeInBytes();
		for (PartitionMetadata partitionMetadata : metadata.partitionsMetadata()) {
			this.partitions.add(new KafkaPartition(partitionMetadata));
		}
	}

	/**
	 * Fetches all topics from Kafka including their partition metadata 
	 * Also includes as well as each partitions latest and earliest offset
	 *
	 * @param kafkaSeedBrokers
	 * @param kafkaConsumerName
	 * @return
	 * @throws java.lang.Exception
	 */
	public static List<KafkaTopic> getAll(String kafkaSeedBrokers,
		 String kafkaConsumerName) throws Exception {

		List<String> topicStrings = new ArrayList<>();
		List<KafkaTopic> topics = new ArrayList<>();
		
		long[] offsetTypes = {-2l, -1l};
		Map<Long, Map<Integer, Map<TopicAndPartition, PartitionOffsetRequestInfo>>> offsetReqs = new HashMap<>();
		Map<Integer, Broker> brokers = new HashMap<>();

		/**
		 * Fetch everything we need as a consumer against the seed broker building 
		 * up any additional request objects we'll need later from specific brokers,
		 * if we get any errors, cycle onto the next broker, build up a map of broker
		 * ID to the broker object we find in the partition meta data for subsequent 
		 * API calls
		 */
		for (String seed : kafkaSeedBrokers.split(",")) {
			String seedHost = seed.split(":")[0];
			int seedPort = Integer.parseInt(seed.split(":")[1]);
			LOG.info("Trying broker @ {}:{}", seedHost, seedPort);
			SimpleConsumer consumer = null;
			try {
				consumer = new SimpleConsumer(seedHost, seedPort, 100000, 64 * 1024, kafkaConsumerName);
				TopicMetadataRequest req = new TopicMetadataRequest(topicStrings);
				TopicMetadataResponse resp = consumer.send(req);

				/**
				 * So, this is going to get a little crazy. Kafka doesn't allow you to send more than 
				 * one offset request for each TopicAndPartition. You also have to have all the 
				 * TopicAndPartition requests sent to only the partitions leader. So, we'll key on 
				 * offsetType (-2 earliest, -1 latest) and then on broker ID. This'll allow us to fetch 
				 * all the offset requests for all topics/partitions in only 2n Kafka API calls where n 
				 * is the number of brokers who are partition leaders (way better than making an 
				 * API call for every single partition.... The outter -2/-1 key is mainly to prevent 
				 * duplicate code for later error handling and assigning offsets to the partitions
				 */				

				offsetReqs.put(-1l, new HashMap<Integer, Map<TopicAndPartition, PartitionOffsetRequestInfo>>());
				offsetReqs.put(-2l, new HashMap<Integer, Map<TopicAndPartition, PartitionOffsetRequestInfo>>());

				for (TopicMetadata topicMetadata : resp.topicsMetadata()) {
					topics.add(new KafkaTopic(topicMetadata));
					for (PartitionMetadata pmd : topicMetadata.partitionsMetadata()) {
						TopicAndPartition tap = new TopicAndPartition(topicMetadata.topic(), pmd.partitionId());
						for (long offsetType : offsetTypes) {
							Map<Integer, Map<TopicAndPartition, PartitionOffsetRequestInfo>> offsetTypeForBroker = offsetReqs.get(offsetType);							
							Map<TopicAndPartition, PartitionOffsetRequestInfo> brokerRequests = offsetTypeForBroker.get(pmd.leader().id());
							if (brokerRequests == null) {
								brokerRequests = new HashMap<>();
								offsetTypeForBroker.put(pmd.leader().id(), brokerRequests);
							}
							brokerRequests.put(tap, new PartitionOffsetRequestInfo(offsetType, 1));
							brokers.put(pmd.leader().id(), pmd.leader());
						}
					}
				}
				break; // Don't iterate onto the next broker, we're done
			} catch (Exception e) {
				LOG.error("Error getting meta data", e);
			} finally {
				if (consumer != null) {
					consumer.close();
				}
			}
		}

		for (long offsetType : offsetTypes) {
			for (int brokerId : offsetReqs.get(offsetType).keySet()) {
				Broker broker = brokers.get(brokerId);
				try {
					SimpleConsumer sc = new SimpleConsumer(broker.host(),
						 broker.port(),
						 100000,
						 64 * 1024,
						 kafkaConsumerName);
					OffsetRequest offsetRequest = new OffsetRequest(offsetReqs.get(offsetType).get(brokerId),
						 kafka.api.OffsetRequest.CurrentVersion(),
						 kafkaConsumerName);
					OffsetResponse offsetRespose = sc.getOffsetsBefore(offsetRequest);
					if (offsetRespose.hasError()) {
						LOG.error("Failed to get offset type {} from broker {}", offsetType, brokerId);
						continue;
					}
					for (KafkaTopic topic : topics) {
						for (KafkaPartition partition : topic.getPartitions()) {
							if (partition.getLeader() != brokerId) {
								continue;
							}
							if (offsetType == -2l) {
								partition.setEarliestOffset(offsetRespose.offsets(topic.getName(), partition.getPartitionId())[0]);
							} else {
								partition.setLatestOffset(offsetRespose.offsets(topic.getName(), partition.getPartitionId())[0]);
							}
						}
					}
				} 
				catch (Exception e) {
					LOG.error("An exception occured fetching offset requests", e);
				}
			}
		}
		return topics;
	}

	/**
	* @return the name
	*/
	public String getName() {
		return name;
	}

	/**
	 * @return the partitions
	 */
	public List<KafkaPartition> getPartitions() {
		return partitions;
	}

	/**
	 * @return the errorCode
	 */
	public short getErrorCode() {
		return errorCode;
	}

	/**
	 * @return the sizeInBytes
	 */
	public int getSizeInBytes() {
		return sizeInBytes;
	}

}
