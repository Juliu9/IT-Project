package com.gen3.recommenderagent.storage.sessionrepository;

import com.gen3.recommenderagent.domain.session.Session;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RedisSessionRepository implements SessionRepository {

    private final RedisTemplate<String, Session> redisTemplate;

    public RedisSessionRepository(
            RedisTemplate<String, Session> redisTemplate) {

        this.redisTemplate = redisTemplate;
    }

    @Override
    public Session getSession(String sessionId) {

        return redisTemplate
                .opsForValue()
                .get("session:" + sessionId);
    }

    @Override
    public void updateSession(Session session) {

        redisTemplate
                .opsForValue()
                .set(
                        "session:" + session.getSessionId(),
                        session
                );
    }
}