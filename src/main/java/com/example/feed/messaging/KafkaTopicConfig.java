package com.example.feed.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {
    @Bean
    NewTopic fanoutTopic(@Value("${feed.fanout.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic fanoutDeadLetterTopic(
            @Value("${feed.fanout.dlt-topic:${feed.fanout.topic}.DLT}") String topic) {
        return TopicBuilder.name(topic).partitions(6).replicas(1).build();
    }
}
