package com.gen3.recommenderagent.storage.sessionrepository.redis;

import com.gen3.recommenderagent.domain.session.Session;
import com.gen3.recommenderagent.observability.RequestTelemetry;
import com.gen3.recommenderagent.storage.sessionrepository.SessionBusyException;
import com.gen3.recommenderagent.storage.sessionrepository.SessionCommitException;
import com.gen3.recommenderagent.storage.sessionrepository.SessionLockLostException;
import com.gen3.recommenderagent.storage.sessionrepository.SessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@ConditionalOnProperty(name = "session.store", havingValue = "redis", matchIfMissing = true)
@Repository
public class RedisSessionRepository implements SessionRepository {

  private static final Duration SESSION_TTL = Duration.ofHours(24);
  private static final Duration LOCK_TTL = Duration.ofMinutes(2);
  private static final Duration LOCK_RENEWAL_INTERVAL = Duration.ofSeconds(30);
  private static final ScheduledExecutorService LOCK_RENEWAL_EXECUTOR =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "redis-session-lock-renewal");
            thread.setDaemon(true);
            return thread;
          });
  private static final DefaultRedisScript<Long> RELEASE_LOCK =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> RENEW_LOCK =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
          Long.class);

  private final RedisTemplate<String, Session> redisTemplate;
  private final StringRedisTemplate stringRedisTemplate;
  private final Duration lockTtl;
  private final Duration lockRenewalInterval;
  private final ScheduledExecutorService lockRenewalExecutor;

  @Autowired
  public RedisSessionRepository(
      RedisTemplate<String, Session> redisTemplate, StringRedisTemplate stringRedisTemplate) {
    this(
        redisTemplate, stringRedisTemplate, LOCK_TTL, LOCK_RENEWAL_INTERVAL, LOCK_RENEWAL_EXECUTOR);
  }

  RedisSessionRepository(
      RedisTemplate<String, Session> redisTemplate,
      StringRedisTemplate stringRedisTemplate,
      Duration lockTtl,
      Duration lockRenewalInterval,
      ScheduledExecutorService lockRenewalExecutor) {
    this.redisTemplate = redisTemplate;
    this.stringRedisTemplate = stringRedisTemplate;
    this.lockTtl = lockTtl;
    this.lockRenewalInterval = lockRenewalInterval;
    this.lockRenewalExecutor = lockRenewalExecutor;
  }

  @Override
  public <T> T update(String sessionId, String userId, Function<Session, T> change) {
    return RequestTelemetry.measure(
        "sessionLockMs",
        () ->
            withSessionLock(
                sessionId,
                userId,
                lock -> {
                  Session session =
                      RequestTelemetry.measure("sessionLoadMs", () -> load(sessionId, userId));
                  T result = change.apply(session);
                  boolean saved =
                      RequestTelemetry.measure("sessionWriteMs", () -> commit(session, lock));
                  if (!saved) {
                    throw new SessionCommitException();
                  }
                  return result;
                }));
  }

  private Session load(String sessionId, String userId) {
    Session session = redisTemplate.opsForValue().get(sessionKey(sessionId, userId));
    if (session == null) {
      session = new Session();
      session.setSessionId(sessionId);
      session.setUserId(userId);
      session.setCreatedAt(Instant.now());
    }
    return session;
  }

  private boolean commit(Session session, LockOwnership lock) {
    String key = sessionKey(session.getSessionId(), session.getUserId());
    return Boolean.TRUE.equals(
        redisTemplate.execute(
            new SessionCallback<Boolean>() {
              @Override
              public <K, V> Boolean execute(RedisOperations<K, V> operations) {
                RedisOperations<String, Session> sessionOperations = sessionOperations(operations);
                sessionOperations.watch(List.of(key, lock.key()));
                if (!lock.isOwnedBy(stringRedisTemplate)) {
                  sessionOperations.unwatch();
                  return false;
                }
                Session existing = sessionOperations.opsForValue().get(key);
                if (existing == null
                    ? session.getVersion() != 0
                    : existing.getVersion() != session.getVersion()) {
                  sessionOperations.unwatch();
                  return false;
                }
                session.setExpiresAt(Instant.now().plus(SESSION_TTL));
                session.setVersion(session.getVersion() + 1);
                sessionOperations.multi();
                sessionOperations.opsForValue().set(key, session, SESSION_TTL);
                return sessionOperations.exec() != null;
              }
            }));
  }

  private <T> T withSessionLock(
      String sessionId, String userId, Function<LockOwnership, T> operation) {
    String lockKey = sessionKey(sessionId, userId) + ":lock";
    String owner = UUID.randomUUID().toString();
    if (!Boolean.TRUE.equals(
        stringRedisTemplate.opsForValue().setIfAbsent(lockKey, owner, lockTtl))) {
      throw new SessionBusyException();
    }
    AtomicBoolean lockLost = new AtomicBoolean();
    LockOwnership lock = new LockOwnership(lockKey, owner, lockLost);
    ScheduledFuture<?> renewal =
        lockRenewalExecutor.scheduleAtFixedRate(
            () -> renew(lockKey, owner, lockLost),
            lockRenewalInterval.toMillis(),
            lockRenewalInterval.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      T result = operation.apply(lock);
      if (lockLost.get()) {
        throw new SessionLockLostException();
      }
      return result;
    } finally {
      renewal.cancel(false);
      stringRedisTemplate.execute(RELEASE_LOCK, List.of(lockKey), owner);
    }
  }

  private void renew(String lockKey, String owner, AtomicBoolean lockLost) {
    try {
      Long renewed =
          stringRedisTemplate.execute(
              RENEW_LOCK, List.of(lockKey), owner, Long.toString(lockTtl.toMillis()));
      if (renewed == null || renewed == 0) {
        lockLost.set(true);
      }
    } catch (DataAccessException exception) {
      lockLost.set(true);
    }
  }

  @SuppressWarnings("unchecked")
  private RedisOperations<String, Session> sessionOperations(RedisOperations<?, ?> operations) {
    return (RedisOperations<String, Session>) operations;
  }

  private String sessionKey(String sessionId, String userId) {
    String user = Objects.requireNonNull(userId, "userId must not be null");
    String session = Objects.requireNonNull(sessionId, "sessionId must not be null");
    return "session:" + user.length() + ":" + user + ":" + session;
  }

  private record LockOwnership(String key, String owner, AtomicBoolean lost) {

    private boolean isOwnedBy(StringRedisTemplate redis) {
      return !lost.get() && owner.equals(redis.opsForValue().get(key));
    }
  }
}
