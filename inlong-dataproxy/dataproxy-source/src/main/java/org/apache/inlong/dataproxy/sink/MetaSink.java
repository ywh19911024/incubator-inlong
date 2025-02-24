/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.dataproxy.sink;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.commons.collections.SetUtils;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.apache.flume.source.shaded.guava.RateLimiter;
import org.apache.inlong.common.metric.MetricRegister;
import org.apache.inlong.dataproxy.config.ConfigManager;
import org.apache.inlong.dataproxy.config.holder.ConfigUpdateCallback;
import org.apache.inlong.dataproxy.config.pojo.ThirdPartyClusterConfig;
import org.apache.inlong.dataproxy.consts.AttributeConstants;
import org.apache.inlong.dataproxy.consts.ConfigConstants;
import org.apache.inlong.dataproxy.metrics.DataProxyMetricItem;
import org.apache.inlong.dataproxy.metrics.DataProxyMetricItemSet;
import org.apache.inlong.dataproxy.metrics.audit.AuditUtils;
import org.apache.inlong.dataproxy.utils.Constants;
import org.apache.inlong.dataproxy.utils.NetworkUtils;
import org.apache.inlong.tubemq.client.config.TubeClientConfig;
import org.apache.inlong.tubemq.client.exception.TubeClientException;
import org.apache.inlong.tubemq.client.factory.TubeMultiSessionFactory;
import org.apache.inlong.tubemq.client.producer.MessageProducer;
import org.apache.inlong.tubemq.client.producer.MessageSentCallback;
import org.apache.inlong.tubemq.client.producer.MessageSentResult;
import org.apache.inlong.tubemq.corebase.Message;
import org.apache.inlong.tubemq.corebase.TErrCodeConstants;
import org.apache.inlong.tubemq.corerpc.exception.OverflowException;
import org.apache.pulsar.shade.org.apache.commons.lang.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MetaSink extends AbstractSink implements Configurable {

    private static final Logger logger = LoggerFactory.getLogger(MetaSink.class);

    private volatile boolean canTake = false;
    private volatile boolean canSend = false;
    //    private static int BATCH_SIZE = 10000; //unused
    private static final int sendNewMetricRetryCount = 3;

    private static String TOPIC = "topic";

    private static final String topicsFilePath = "topics.properties"; //unused
    private static final String slaTopicFilePath = "slaTopics.properties";

    private ConfigManager configManager;
    private Map<String, String> topicProperties;

    private ThirdPartyClusterConfig tubeConfig;
    private Set<String> masterHostAndPortLists;

    // key: masterUrl
    public Map<String, TubeMultiSessionFactory> sessionFactories;
    public Map<String, List<TopicProducerInfo>> masterUrl2producers;

    // used for RoundRobin different cluster while send message
    private AtomicInteger clusterIndex = new AtomicInteger(0);

    // key: topic
    public Map<String, List<TopicProducerInfo>> producerInfoMap;

    private LinkedBlockingQueue<EventStat> resendQueue;
    private LinkedBlockingQueue<Event> eventQueue;

    private RateLimiter diskRateLimiter;

    public AtomicInteger currentPublishTopicNum = new AtomicInteger(0);

    private Thread[] sinkThreadPool;

    private String metaTopicFilePath = topicsFilePath;

    private Map<String, String> dimensions;
    private DataProxyMetricItemSet metricItemSet;

    private static final LoadingCache<String, Long> agentIdCache = CacheBuilder
            .newBuilder().concurrencyLevel(4 * 8).initialCapacity(5000000)
            .expireAfterAccess(30, TimeUnit.SECONDS)
            .build(new CacheLoader<String, Long>() {

                @Override
                public Long load(String key) {
                    return System.currentTimeMillis();
                }
            });

    private IdCacheCleaner idCacheCleaner;
    protected static boolean idCleanerStarted = false;
    protected static final ConcurrentHashMap<String, Long> agentIdMap = new ConcurrentHashMap<String, Long>();
    private static ConcurrentHashMap<String, Long> illegalTopicMap = new ConcurrentHashMap<String, Long>();

    private int maxSurvivedTime = 3 * 1000 * 30;
    private int maxSurvivedSize = 100000;
    private boolean clientIdCache = false;
    private boolean isNewCache = true;

    private boolean overflow = false;

    /**
     * diff publish
     *
     * @param originalSet
     * @param endSet
     */
    public void diffSetPublish(Set<String> originalSet, Set<String> endSet) {
        if (SetUtils.isEqualSet(originalSet, endSet)) {
            return;
        }

        boolean changed = false;
        Set<String> newTopics = new HashSet<>();
        for (String s : endSet) {
            if (!originalSet.contains(s)) {
                changed = true;
                newTopics.add(s);
            }
        }

        if (changed) {
            try {
                initTopicSet(newTopics);
            } catch (Exception e) {
                logger.info("meta sink publish new topic fail.", e);
            }

            logger.info("topics.properties has changed, trigger diff publish for {}", getName());
            topicProperties = configManager.getTopicProperties();
        }
    }

    /**
     * when masterUrlLists change, update tubeClient
     *
     * @param originalCluster, previous masterHostAndPortList set
     * @param endCluster,      new masterHostAndPortList set
     */
    public void diffUpdateTubeClient(Set<String> originalCluster, Set<String> endCluster) {
        if (SetUtils.isEqualSet(originalCluster, endCluster)) {
            return;
        }
        // close
        for (String masterUrl : originalCluster) {

            if (!endCluster.contains(masterUrl)) {
                // step1: close and remove all related producers
                List<TopicProducerInfo> producerInfoList = masterUrl2producers.get(masterUrl);
                if (producerInfoList != null) {
                    for (TopicProducerInfo producerInfo : producerInfoList) {
                        producerInfo.shutdown();
                        // remove from topic<->producer map
                        for (String topic : producerInfo.getTopicSet()) {
                            List<TopicProducerInfo> curTopicProducers = producerInfoMap.get(topic);
                            if (curTopicProducers != null) {
                                curTopicProducers.remove(producerInfo);
                            }
                        }
                    }
                    // remove from masterUrl<->producer map
                    masterUrl2producers.remove(masterUrl);
                }

                // step2: close and remove related sessionFactories
                TubeMultiSessionFactory sessionFactory = sessionFactories.get(masterUrl);
                if (sessionFactory != null) {
                    try {
                        sessionFactory.shutdown();
                    } catch (TubeClientException e) {
                        logger.error("destroy sessionFactory error in metasink, MetaClientException {}",
                                e.getMessage());
                    }
                    sessionFactories.remove(masterUrl);
                }

                logger.info("close tubeClient of masterList:{}", masterUrl);
            }

        }
        // start new client
        for (String masterUrl : endCluster) {
            if (!originalCluster.contains(masterUrl)) {

                TubeMultiSessionFactory sessionFactory = createConnection(masterUrl);

                if (sessionFactory != null) {
                    List<Set<String>> topicGroups = partitionTopicSet(new HashSet<>(topicProperties.values()));
                    for (Set<String> topicSet : topicGroups) {
                        createTopicProducers(masterUrl, sessionFactory, topicSet);
                    }

                    logger.info("successfully start new tubeClient for the new masterList: {}", masterUrl);
                }
            }
        }

        masterHostAndPortLists = configManager.getThirdPartyClusterUrl2Token().keySet();
    }

    /**
     * when there are multi clusters, pick producer based on round-robin
     *
     * @param topic
     * @return
     * @throws TubeClientException
     */
    private MessageProducer getProducer(String topic) throws TubeClientException {
        if (producerInfoMap.containsKey(topic) && !producerInfoMap.get(topic).isEmpty()) {

            List<TopicProducerInfo> producers = producerInfoMap.get(topic);
            // round-roubin dispatch
            int currentIndex = clusterIndex.getAndIncrement();
            if (currentIndex > Integer.MAX_VALUE / 2) {
                clusterIndex.set(0);
            }
            int producerIndex = currentIndex % producers.size();
            return producers.get(producerIndex).getProducer();
        }
        return null;
//        else {
//            synchronized (this) {
//              if (!producerInfoMap.containsKey(topic)) {
//                 if (producer == null || currentPublishTopicNum.get() >= tubeConfig.getMaxTopicsEachProducerHold()) {
//                        producer = sessionFactory.createProducer();
//                        currentPublishTopicNum.set(0);
//                    }
//                    // publish topic
//                    producer.publish(topic);
//                    producerMap.put(topic, producer);
//                    currentPublishTopicNum.incrementAndGet();
//                }
//            }
//            return producerMap.get(topic);
//        }
    }

    private TubeClientConfig initTubeConfig(String masterUrl) throws Exception {
        final TubeClientConfig tubeClientConfig = new TubeClientConfig(masterUrl);
        tubeClientConfig.setLinkMaxAllowedDelayedMsgCount(tubeConfig.getLinkMaxAllowedDelayedMsgCount());
        tubeClientConfig.setSessionWarnDelayedMsgCount(tubeConfig.getSessionWarnDelayedMsgCount());
        tubeClientConfig.setSessionMaxAllowedDelayedMsgCount(tubeConfig.getSessionMaxAllowedDelayedMsgCount());
        tubeClientConfig.setNettyWriteBufferHighWaterMark(tubeConfig.getNettyWriteBufferHighWaterMark());
        tubeClientConfig.setHeartbeatPeriodMs(15000L);
        tubeClientConfig.setRpcTimeoutMs(20000L);

        return tubeClientConfig;
    }

    /**
     * If this function is called successively without calling {@see #destroyConnection()}, only the
     * first call has any effect.
     *
     * @throws FlumeException if an RPC client connection could not be opened
     */
    private void initCreateConnection() throws FlumeException {
//        synchronized (tubeSessionLock) {
        // if already connected, just skip
        if (sessionFactories != null) {
            return;
        }
        sessionFactories = new HashMap<>();
        Preconditions.checkState(masterHostAndPortLists != null && !masterHostAndPortLists.isEmpty(),
                "No tube service url specified");
        for (String masterUrl : masterHostAndPortLists) {
            createConnection(masterUrl);
        }

        if (sessionFactories.size() == 0) {
            throw new FlumeException("create tube sessionFactories err, please re-check");
        }
    }

    private TubeMultiSessionFactory createConnection(String masterHostAndPortList) {
        TubeMultiSessionFactory sessionFactory;
        try {
            TubeClientConfig conf = initTubeConfig(masterHostAndPortList);
            sessionFactory = new TubeMultiSessionFactory(conf);
            sessionFactories.put(masterHostAndPortList, sessionFactory);
        } catch (TubeClientException e) {
            logger.error("create connnection error in metasink, "
                    + "maybe tube master set error, please re-check. ex1 {}", e.getMessage());
            throw new FlumeException("connect to Tube error1, "
                    + "maybe zkstr/zkroot set error, please re-check");
        } catch (Throwable e) {
            logger.error("create connnection error in metasink, "
                            + "maybe tube master set error/shutdown in progress, please re-check. ex2 {}",
                    e.getMessage());
            throw new FlumeException("connect to meta error2, "
                    + "maybe tube master set error/shutdown in progress, please re-check");
        }
        return sessionFactory;
    }

    private void destroyConnection() {
        for (List<TopicProducerInfo> producerInfoList : producerInfoMap.values()) {
            for (TopicProducerInfo producerInfo : producerInfoList) {
                producerInfo.shutdown();
            }
        }
        producerInfoMap.clear();

        if (sessionFactories != null) {
            for (TubeMultiSessionFactory sessionFactory : sessionFactories.values()) {
                try {
                    sessionFactory.shutdown();
                } catch (TubeClientException e) {
                    logger.error("destroy sessionFactory error in metasink, MetaClientException {}",
                            e.getMessage());
                } catch (Exception e) {
                    logger.error("destroy sessionFactory error in metasink, ex {}", e.getMessage());
                }
            }
        }
        sessionFactories.clear();
        masterUrl2producers.clear();
        logger.debug("closed meta producer");
    }

    /**
     * partition topicSet to different group, each group is associated with a producer;
     * if there are multi clusters, then each group is associated with a set of producer
     *
     * @param topicSet
     * @return
     */
    private List<Set<String>> partitionTopicSet(Set<String> topicSet) {
        List<Set<String>> topicGroups = new ArrayList<>();

        List<String> sortedList = new ArrayList(topicSet);
        Collections.sort(sortedList);
        int maxTopicsEachProducerHolder = tubeConfig.getMaxTopicsEachProducerHold();
        int cycle = sortedList.size() / maxTopicsEachProducerHolder;
        int remainder = sortedList.size() % maxTopicsEachProducerHolder;

        for (int i = 0; i <= cycle; i++) {
            // allocate topic
            Set<String> subset = new HashSet<String>();
            int startIndex = i * maxTopicsEachProducerHolder;
            int endIndex = startIndex + maxTopicsEachProducerHolder - 1;
            if (i == cycle) {
                if (remainder == 0) {
                    continue;
                } else {
                    endIndex = startIndex + remainder - 1;
                }
            }
            for (int index = startIndex; index <= endIndex; index++) {
                subset.add(sortedList.get(index));
            }

            topicGroups.add(subset);
        }
        return topicGroups;
    }

    /**
     * create producer and publish topic
     *
     * @param masterUrl
     * @param sessionFactory
     * @param topicGroup
     */
    private void createTopicProducers(String masterUrl, TubeMultiSessionFactory sessionFactory,
                                      Set<String> topicGroup) {

        TopicProducerInfo info = new TopicProducerInfo(sessionFactory);
        info.initProducer();
        Set<String> succTopicSet = info.publishTopic(topicGroup);

        masterUrl2producers.computeIfAbsent(masterUrl, k -> new ArrayList<>()).add(info);

        if (succTopicSet != null) {
            for (String succTopic : succTopicSet) {
                producerInfoMap.computeIfAbsent(succTopic, k -> new ArrayList<>()).add(info);

            }
        }
    }

    private void initTopicSet(Set<String> topicSet) throws Exception {
        long startTime = System.currentTimeMillis();

        if (sessionFactories != null) {
            List<Set<String>> topicGroups = partitionTopicSet(topicSet);
            for (Set<String> subset : topicGroups) {
                for (Map.Entry<String, TubeMultiSessionFactory> entry : sessionFactories.entrySet()) {
                    createTopicProducers(entry.getKey(), entry.getValue(), subset);
                }
            }
            logger.info(getName() + " producer is ready for topics : " + producerInfoMap.keySet());
            logger.info(getName() + " initTopicSet cost: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    @Override
    public void start() {
        this.dimensions = new HashMap<>();
        this.dimensions.put(DataProxyMetricItem.KEY_CLUSTER_ID, "DataProxy");
        this.dimensions.put(DataProxyMetricItem.KEY_SINK_ID, this.getName());
        //register metrics
        this.metricItemSet = new DataProxyMetricItemSet(this.getName());
        MetricRegister.register(metricItemSet);

        //create tube connection
        try {
            initCreateConnection();
        } catch (FlumeException e) {
            logger.error("Unable to create tube client" + ". Exception follows.", e);

            /* Try to prevent leaking resources. */
            destroyConnection();

            /* FIXME: Mark ourselves as failed. */
            stop();
            return;
        }

        // start the cleaner thread
        if (clientIdCache && !isNewCache) {
            idCacheCleaner = new IdCacheCleaner(this, maxSurvivedTime, maxSurvivedSize);
            idCacheCleaner.start();
        }

        super.start();
        this.canSend = true;
        this.canTake = true;

        try {
            initTopicSet(new HashSet<String>(topicProperties.values()));
        } catch (Exception e) {
            logger.info("meta sink start publish topic fail.", e);
        }

        for (int i = 0; i < sinkThreadPool.length; i++) {
            sinkThreadPool[i] = new Thread(new SinkTask(), getName() + "_tube_sink_sender-" + i);
            sinkThreadPool[i].start();
        }

    }

    class SinkTask implements Runnable {
        private void sendMessage(MessageProducer producer, Event event, String topic, AtomicBoolean flag, EventStat es)
                throws TubeClientException, InterruptedException {
            String clientId = event.getHeaders().get(ConfigConstants.SEQUENCE_ID);
            if (!isNewCache) {
                Long lastTime = 0L;
                if (clientIdCache && clientId != null) {
                    lastTime = agentIdMap.put(clientId, System.currentTimeMillis());
                }
                if (clientIdCache && clientId != null && lastTime != null && lastTime > 0) {
                    logger.info("{} agent package {} existed,just discard.", getName(), clientId);
                } else {
                    Message message = new Message(topic, event.getBody());
                    message.setAttrKeyVal("dataproxyip", NetworkUtils.getLocalIp());
                    String streamId = "";
                    if (event.getHeaders().containsKey(AttributeConstants.STREAM_ID)) {
                        streamId = event.getHeaders().get(AttributeConstants.STREAM_ID);
                    } else if (event.getHeaders().containsKey(AttributeConstants.INAME)) {
                        streamId = event.getHeaders().get(AttributeConstants.INAME);
                    }
                    message.putSystemHeader(streamId, event.getHeaders().get(ConfigConstants.PKG_TIME_KEY));

                    producer.sendMessage(message, new MyCallback(es));
                    flag.set(true);

                }
            } else {
                boolean hasKey = false;
                if (clientIdCache && clientId != null) {
                    hasKey = agentIdCache.asMap().containsKey(clientId);
                }

                if (clientIdCache && clientId != null && hasKey) {
                    agentIdCache.put(clientId, System.currentTimeMillis());
                    logger.info("{} agent package {} existed,just discard.", getName(), clientId);
                } else {
                    if (clientId != null) {
                        agentIdCache.put(clientId, System.currentTimeMillis());
                    }

                    Message message = new Message(topic, event.getBody());
                    message.setAttrKeyVal("dataproxyip", NetworkUtils.getLocalIp());
                    String streamId = "";
                    if (event.getHeaders().containsKey(AttributeConstants.STREAM_ID)) {
                        streamId = event.getHeaders().get(AttributeConstants.STREAM_ID);
                    } else if (event.getHeaders().containsKey(AttributeConstants.INAME)) {
                        streamId = event.getHeaders().get(AttributeConstants.INAME);
                    }
                    message.putSystemHeader(streamId, event.getHeaders().get(ConfigConstants.PKG_TIME_KEY));

                    producer.sendMessage(message, new MyCallback(es));
                    flag.set(true);
                }
            }
            illegalTopicMap.remove(topic);
        }

        private void handleException(Throwable t, String topic, boolean decrementFlag, EventStat es) {
            if (t instanceof TubeClientException) {
                String message = t.getMessage();
                if (message != null && (message.contains("No available queue for topic")
                    || message.contains("The brokers of topic are all forbidden"))) {
                    illegalTopicMap.put(topic, System.currentTimeMillis() + 60 * 1000);
                    logger.info("IllegalTopicMap.put " + topic);
                    return;
                } else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        //ignore..
                    }
                }
            }
            logger.error("Sink task fail to send the message, decrementFlag=" + decrementFlag + ",sink.name="
                + Thread.currentThread().getName()
                + ",event.headers=" + es.getEvent().getHeaders(), t);
        }

        @Override
        public void run() {
            logger.info("Sink task {} started.", Thread.currentThread().getName());
            while (canSend) {
                boolean decrementFlag = false;
                boolean resendBadEvent = false;
                Event event = null;
                EventStat es = null;
                String topic = null;
                try {
                    if (MetaSink.this.overflow) {
                        MetaSink.this.overflow = false;
                        Thread.sleep(10);
                    }
                    if (!resendQueue.isEmpty()) {
                        es = resendQueue.poll();
                        if (es != null) {
                            event = es.getEvent();
                            // logger.warn("Resend event: {}", event.toString());
                            if (event.getHeaders().containsKey(TOPIC)) {
                                topic = event.getHeaders().get(TOPIC);
                            }
                            resendBadEvent = true;
                        }
                    } else {
                        event = eventQueue.take();
                        es = new EventStat(event);
//                            sendCnt.incrementAndGet();
                        if (event.getHeaders().containsKey(TOPIC)) {
                            topic = event.getHeaders().get(TOPIC);
                        }
                    }

                    if (event == null) {
                        // ignore event is null, when multiple-thread SinkTask running
                        // this null value comes from resendQueue
                        continue;
                    }

                    if (topic == null || topic.equals("")) {
                        logger.warn("no topic specified in event header, just skip this event");
                        continue;
                    }

                    Long expireTime = illegalTopicMap.get(topic);
                    if (expireTime != null) {
                        long currentTime = System.currentTimeMillis();
                        if (expireTime > currentTime) {

                            // TODO: need to be improved.
//                            reChannelEvent(es, topic);
                            continue;
                        } else {

                            illegalTopicMap.remove(topic);
                        }
                    }
                    MessageProducer producer = null;
                    try {
                        producer = getProducer(topic);
                    } catch (Exception e) {
                        logger.error("Get producer failed!", e);
                    }

                    if (producer == null) {
                        illegalTopicMap.put(topic, System.currentTimeMillis() + 30 * 1000);
                        continue;
                    }

                    AtomicBoolean flagAtomic = new AtomicBoolean(decrementFlag);
                    sendMessage(producer, event, topic, flagAtomic, es);
                    decrementFlag = flagAtomic.get();

                } catch (InterruptedException e) {
                    logger.info("Thread {} has been interrupted!", Thread.currentThread().getName());
                    return;
                } catch (Throwable t) {
                    handleException(t, topic, decrementFlag, es);
                    resendEvent(es, decrementFlag);
                }
            }
        }
    }

    public class MyCallback implements MessageSentCallback {
        private EventStat myEventStat;
        private long sendTime;

        public MyCallback(EventStat eventStat) {
            this.myEventStat = eventStat;
            this.sendTime = System.currentTimeMillis();
        }

        @Override
        public void onMessageSent(final MessageSentResult result) {
            if (result.isSuccess()) {
                // TODO: add stats
                this.addMetric(myEventStat.getEvent(), true, sendTime);
            } else {
                this.addMetric(myEventStat.getEvent(), false, 0);
                if (result.getErrCode() == TErrCodeConstants.FORBIDDEN) {
                    logger.warn("Send message failed, error message: {}, resendQueue size: {}, event:{}",
                            result.getErrMsg(), resendQueue.size(),
                            myEventStat.getEvent().hashCode());

                    return;
                }
                if (result.getErrCode() != TErrCodeConstants.SERVER_RECEIVE_OVERFLOW) {
                    logger.warn("Send message failed, error message: {}, resendQueue size: {}, event:{}",
                            result.getErrMsg(), resendQueue.size(),
                            myEventStat.getEvent().hashCode());
                }
                resendEvent(myEventStat, true);
            }
        }

        /**
         * addMetric
         *
         * @param event
         * @param result
         * @param sendTime
         */
        private void addMetric(Event event, boolean result, long sendTime) {
            Map<String, String> dimensions = new HashMap<>();
            dimensions.put(DataProxyMetricItem.KEY_CLUSTER_ID, MetaSink.this.getName());
            dimensions.put(DataProxyMetricItem.KEY_SINK_ID, MetaSink.this.getName());
            dimensions.put(DataProxyMetricItem.KEY_SINK_DATA_ID, event.getHeaders().getOrDefault(TOPIC, ""));
            DataProxyMetricItem.fillInlongId(event, dimensions);
            DataProxyMetricItem.fillAuditFormatTime(event, dimensions);
            DataProxyMetricItem metricItem = MetaSink.this.metricItemSet.findMetricItem(dimensions);
            if (result) {
                metricItem.sendSuccessCount.incrementAndGet();
                metricItem.sendSuccessSize.addAndGet(event.getBody().length);
                AuditUtils.add(AuditUtils.AUDIT_ID_DATAPROXY_SEND_SUCCESS, event);
                if (sendTime > 0) {
                    long currentTime = System.currentTimeMillis();
                    long msgTime = NumberUtils.toLong(event.getHeaders().get(Constants.HEADER_KEY_MSG_TIME),
                            sendTime);
                    long sinkDuration = currentTime - sendTime;
                    long nodeDuration = currentTime - NumberUtils.toLong(Constants.HEADER_KEY_SOURCE_TIME, msgTime);
                    long wholeDuration = currentTime - msgTime;
                    metricItem.sinkDuration.addAndGet(sinkDuration);
                    metricItem.nodeDuration.addAndGet(nodeDuration);
                    metricItem.wholeDuration.addAndGet(wholeDuration);
                }
            } else {
                metricItem.sendFailCount.incrementAndGet();
                metricItem.sendFailSize.addAndGet(event.getBody().length);
            }
        }

        @Override
        public void onException(final Throwable e) {
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }
            if (t instanceof OverflowException) {
                MetaSink.this.overflow = true;
            }
            resendEvent(myEventStat, true);
        }
    }

    /**
     * resend event
     *
     * @param es
     * @param isDecrement
     */
    private void resendEvent(EventStat es, boolean isDecrement) {
        try {
            if (es == null || es.getEvent() == null) {
                return;
            }

            if (clientIdCache) {
                String clientId = es.getEvent().getHeaders().get(ConfigConstants.SEQUENCE_ID);
                if (!isNewCache) {
                    if (clientId != null && agentIdMap.containsKey(clientId)) {
                        agentIdMap.remove(clientId);
                    }
                } else {
                    if (clientId != null && agentIdCache.asMap().containsKey(clientId)) {
                        agentIdCache.invalidate(clientId);
                    }
                }
            }
        } catch (Throwable throwable) {
            logger.error(getName() + " Discard msg because put events to both of queue and "
                    + "fileChannel fail,current resendQueue.size = "
                    + resendQueue.size(), throwable);
        }
    }

    @Override
    public Status process() throws EventDeliveryException {
        if (!this.canTake) {
            return Status.BACKOFF;
        }
        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction tx = channel.getTransaction();
        tx.begin();
        try {
            Event event = channel.take();
            if (event != null) {
                if (diskRateLimiter != null) {
                    diskRateLimiter.acquire(event.getBody().length);
                }
                Map<String, String> dimensions;
                if (event.getHeaders().containsKey(TOPIC)) {
                    dimensions = getNewDimension(DataProxyMetricItem.KEY_SINK_DATA_ID,
                            event.getHeaders().get(TOPIC));
                } else {
                    dimensions = getNewDimension(DataProxyMetricItem.KEY_SINK_DATA_ID,
                            "");
                }
                if (!eventQueue.offer(event, 3 * 1000, TimeUnit.MILLISECONDS)) {
                    logger.info("[{}] Channel --> Queue(has no enough space,current code point) "
                            + "--> Tube,Check if Tube server or network is ok.(if this situation last long time "
                            + "it will cause memoryChannel full and fileChannel write.)", getName());
                    tx.rollback();
                    // metric
                    DataProxyMetricItem metricItem = this.metricItemSet.findMetricItem(dimensions);
                    metricItem.readFailCount.incrementAndGet();
                    metricItem.readFailSize.addAndGet(event.getBody().length);
                } else {
                    tx.commit();
                    DataProxyMetricItem metricItem = this.metricItemSet.findMetricItem(dimensions);
                    metricItem.readSuccessCount.incrementAndGet();
                    metricItem.readFailSize.addAndGet(event.getBody().length);
                }
            } else {

                // logger.info("[{}]No data to process in the channel.",getName());
                status = Status.BACKOFF;
                tx.commit();
            }
        } catch (Throwable t) {
            logger.error("Process event failed!" + this.getName(), t);
            try {
                tx.rollback();
            } catch (Throwable e) {
                logger.error("metasink transaction rollback exception", e);

            }
        } finally {
            tx.close();
        }
        return status;
    }

    @Override
    public void configure(Context context) {
        logger.info(context.toString());
//        logger.info("sinktest:"+getName()+getChannel());//sinktest:meta-sink-msg2null

        configManager = ConfigManager.getInstance();
        topicProperties = configManager.getTopicProperties();
        masterHostAndPortLists = configManager.getThirdPartyClusterUrl2Token().keySet();
        Preconditions.checkState(masterHostAndPortLists != null || masterHostAndPortLists.isEmpty(),
                "No master and port list specified");
        tubeConfig = configManager.getThirdPartyClusterConfig();
        configManager.getTopicConfig().addUpdateCallback(new ConfigUpdateCallback() {
            @Override
            public void update() {

                diffSetPublish(new HashSet<String>(topicProperties.values()),
                        new HashSet<String>(configManager.getTopicProperties().values()));
            }
        });
        configManager.getThirdPartyClusterHolder().addUpdateCallback(new ConfigUpdateCallback() {
            @Override
            public void update() {
                diffUpdateTubeClient(masterHostAndPortLists, configManager.getThirdPartyClusterUrl2Token().keySet());
            }
        });

        producerInfoMap = new ConcurrentHashMap<>();
        masterUrl2producers = new ConcurrentHashMap<>();

        if (tubeConfig.getEnableSlaMetricSink()) {
            this.metaTopicFilePath = slaTopicFilePath;
        }

        clientIdCache = tubeConfig.getClientIdCache();
        if (clientIdCache) {
            int survivedTime = tubeConfig.getMaxSurvivedTime();
            if (survivedTime > 0) {
                maxSurvivedTime = survivedTime;
            } else {
                logger.warn("invalid {}", survivedTime);
            }

            int survivedSize = tubeConfig.getMaxSurvivedSize();
            if (survivedSize > 0) {
                maxSurvivedSize = survivedSize;
            } else {
                logger.warn("invalid {}", survivedSize);
            }
        }

        int badEventQueueSize = tubeConfig.getBadEventQueueSize();
        Preconditions.checkArgument(badEventQueueSize > 0, "badEventQueueSize must be > 0");
        resendQueue = new LinkedBlockingQueue<>(badEventQueueSize);

        int threadNum = tubeConfig.getThreadNum();
        Preconditions.checkArgument(threadNum > 0, "threadNum must be > 0");
        sinkThreadPool = new Thread[threadNum];
        int eventQueueSize = tubeConfig.getEventQueueSize();
        Preconditions.checkArgument(eventQueueSize > 0, "eventQueueSize must be > 0");
        eventQueue = new LinkedBlockingQueue<Event>(eventQueueSize);

        if (tubeConfig.getDiskIoRatePerSec() != 0) {
            diskRateLimiter = RateLimiter.create(tubeConfig.getDiskIoRatePerSec());
        }

    }

    private Map getNewDimension(String otherKey, String value) {
        Map dimensions = new HashMap<>();
        dimensions.put(DataProxyMetricItem.KEY_CLUSTER_ID, "DataProxy");
        dimensions.put(DataProxyMetricItem.KEY_SINK_ID, this.getName());
        dimensions.put(otherKey, value);
        return dimensions;
    }

    /**
     * get metricItemSet
     *
     * @return the metricItemSet
     */
    public DataProxyMetricItemSet getMetricItemSet() {
        return metricItemSet;
    }

    class TopicProducerInfo {

        private TubeMultiSessionFactory sessionFactory;
        private MessageProducer producer;
        private Set<String> topicSet;

        public TopicProducerInfo(TubeMultiSessionFactory sessionFactory) {
            this.sessionFactory = sessionFactory;
        }

        public void shutdown() {
            if (producer != null) {
                try {
                    producer.shutdown();
                } catch (TubeClientException e) {
                    logger.error("destroy producer error in metasink, MetaClientException {}", e.getMessage());
                } catch (Throwable e) {
                    logger.error("destroy producer error in metasink, ex {}", e.getMessage());
                }
            }
        }

        public void initProducer() {
            if (sessionFactory == null) {
                logger.error("sessionFactory is null, can't create producer");
                return;
            }
            try {
                this.producer = sessionFactory.createProducer();
            } catch (TubeClientException e) {
                logger.error("create tube messageProducer error in metasink, ex {}", e.getMessage());
            }
        }

        public Set<String> publishTopic(Set<String> topicSet) {
            try {
                this.topicSet = producer.publish(topicSet);
            } catch (TubeClientException e) {
                logger.info(getName() + " meta sink initTopicSet fail.", e);
            }
            return this.topicSet;
        }

        public MessageProducer getProducer() {
            return producer;
        }

        public Set<String> getTopicSet() {
            return this.topicSet;
        }
    }

}
