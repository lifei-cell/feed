package com.example.feed.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafka,
            @Value("${feed.fanout.dlt-topic:${feed.fanout.topic}.DLT}") String dltTopic,
            @Value("${feed.fanout.kafka-retry.backoff:1s}") java.time.Duration backoff,
            @Value("${feed.fanout.kafka-retry.attempts:2}") long attempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(dltTopic, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(backoff.toMillis(), Math.max(0, attempts)));
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> dltKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Value("${feed.fanout.dlt-capture-retry-delay:5s}") java.time.Duration retryDelay) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        // Never commit past an uncaptured DLT record. The partition stays blocked until persistence recovers.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(retryDelay.toMillis(), Long.MAX_VALUE)));
        return factory;
    }
}
