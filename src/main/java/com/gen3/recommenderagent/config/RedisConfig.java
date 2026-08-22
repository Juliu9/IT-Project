package com.gen3.recommenderagent.config;

import com.gen3.recommenderagent.domain.session.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Session> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {

        RedisTemplate<String, Session> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys are saved as plain strings
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        JacksonJsonRedisSerializer<Session> serializer = new JacksonJsonRedisSerializer<>(objectMapper, Session.class);

        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        return template;
    }
}
