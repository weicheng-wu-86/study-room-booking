package com.studyroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可手动拨动的时钟。测试中用它代替系统时钟，
 * 从而可以在毫秒内验证“15 分钟未签到”“暂离 30 分钟”这类依赖时间流逝的规则。
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
        this.instant = Instant.now();
    }

    public void set(LocalDateTime time) {
        this.instant = time.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
