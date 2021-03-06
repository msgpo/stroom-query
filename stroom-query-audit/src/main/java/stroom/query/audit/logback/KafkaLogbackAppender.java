/*
 * Copyright 2018 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.audit.logback;

import ch.qos.logback.core.Appender;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.FilterReply;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Properties;

/**
 * This is a logback implementation that writes all logs to a kafka queue.
 * Kafka may prove a nice way to capture logs from disparate boxes and processes onto a single bus.
 * @param <E> The log object
 */
public class KafkaLogbackAppender<E> extends ContextAwareBase implements Appender<E> {

    private String name;
    private final Properties producerConfig;

    private final String topic;

    private volatile Producer<String, byte[]> producer = null;

    private static final Integer PARTITION = 0;
    private static final String KEY = "0";

    public KafkaLogbackAppender(final Properties producerConfig,
                                final String topic) {
        this.topic = topic;

        // Build properties that can be used by the kafka producer
        this.producerConfig = new Properties();
        this.producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        this.producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
        this.producerConfig.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        this.producerConfig.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        this.producerConfig.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        this.producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class.getName());
        this.producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.ByteArraySerializer.class.getName());

        this.producerConfig.putAll(producerConfig);
    }

    public Properties getProducerConfig() {
        return producerConfig;
    }

    public String getTopic() {
        return topic;
    }

    @Override
    public void start() {

        producer = new KafkaProducer<>(this.producerConfig);
    }

    @Override
    public void stop() {
        producer.close();
    }

    @Override
    public boolean isStarted() {
        return false;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void doAppend(E e) throws LogbackException {
        final ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(
                        topic,
                        PARTITION,
                        System.currentTimeMillis(),
                        KEY,
                        e.toString().getBytes(Charset.defaultCharset()));

        producer.send(record);
    }

    @Override
    public void setName(String s) {
        this.name = s;
    }

    @Override
    public void addFilter(Filter<E> filter) {

    }

    @Override
    public void clearAllFilters() {

    }

    @Override
    public List<Filter<E>> getCopyOfAttachedFiltersList() {
        return null;
    }

    @Override
    public FilterReply getFilterChainDecision(E e) {
        return FilterReply.ACCEPT;
    }
}
