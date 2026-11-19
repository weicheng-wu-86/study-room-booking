package com.studyroom.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务参数，对应 application.yml 中的 studyroom.* 配置。
 * 预约规则集中在这里，修改规则不需要改代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "studyroom")
public class AppProperties {

    /** 业务时区 */
    private String zone = "Asia/Shanghai";
    /** 是否在空库时写入演示数据 */
    private boolean demoData = true;

    private Jwt jwt = new Jwt();
    private Reserve reserve = new Reserve();
    private Credit credit = new Credit();

    @Data
    public static class Jwt {
        private String secret = "study-room-booking-demo-secret-key-please-change-me";
        private int expireHours = 24;
    }

    @Data
    public static class Reserve {
        /** 可预约今天及之后多少天 */
        private int maxDaysAhead = 6;
        /** 单次预约最长小时数 */
        private int maxHoursPerReservation = 4;
        /** 每人每天累计预约小时上限 */
        private int maxHoursPerDay = 8;
        /** 开始前多少分钟可签到 */
        private int checkinEarlyMinutes = 15;
        /** 开始后多少分钟内必须签到 */
        private int checkinLateMinutes = 15;
        /** 单次暂离最长分钟数 */
        private int awayMaxMinutes = 30;
        /** 每次预约最多暂离次数 */
        private int awayMaxTimes = 2;
        /** 定时扫描间隔（毫秒） */
        private long scanIntervalMs = 30000;
    }

    @Data
    public static class Credit {
        /** 违约统计窗口（天） */
        private int windowDays = 30;
        /** 窗口内达到该违约次数即禁约 */
        private int maxViolations = 3;
        /** 禁约天数 */
        private int banDays = 7;
    }
}
