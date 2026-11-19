package com.studyroom.service;

import com.studyroom.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 基于 Redis 的分布式锁。
 * <ul>
 *   <li>加锁：SET key token NX PX ttl，一条命令完成“不存在才设置 + 过期时间”，不会因进程崩溃而死锁；</li>
 *   <li>解锁：用 Lua 脚本比较 token 后再删除，保证只能释放自己加的锁，
 *       避免业务执行超过过期时间后误删别人的锁。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 锁的默认过期时间，远大于一次预约事务的耗时 */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(10);
    /** 默认最长等待时间 */
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(3);

    private final StringRedisTemplate redis;

    /**
     * 尝试加锁，在 wait 时间内自旋重试。
     *
     * @return 成功返回本次加锁的 token（解锁时需要），超时返回 null
     */
    public String tryLock(String key, Duration ttl, Duration wait) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(ok)) {
                return token;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                // 随机退避，避免大量等待者同时醒来
                Thread.sleep(ThreadLocalRandom.current().nextLong(10, 30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public void unlock(String key, String token) {
        redis.execute(UNLOCK_SCRIPT, List.of(key), token);
    }

    /**
     * 在持有锁的情况下执行 action，执行完（无论成功或异常）释放锁。
     * 等待超时仍未拿到锁时抛出 409，由前端提示用户稍后重试。
     */
    public <T> T withLock(String key, Supplier<T> action) {
        String token = tryLock(key, DEFAULT_TTL, DEFAULT_WAIT);
        if (token == null) {
            throw BusinessException.conflict("当前预约人数较多，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }
}
