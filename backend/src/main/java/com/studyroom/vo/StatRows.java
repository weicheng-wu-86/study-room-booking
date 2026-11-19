package com.studyroom.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 统计类 SQL 的分组查询结果行。
 */
public final class StatRows {

    private StatRows() {
    }

    /** 按“日期 + 状态”分组的预约数 */
    @Data
    public static class DayStatus {
        private LocalDate day;
        private String status;
        private long cnt;
    }

    /** 按自习室分组的数量（座位数、占用小时数等） */
    @Data
    public static class RoomCount {
        private Long roomId;
        private long cnt;
    }

    /** 一条预约的起止小时，用于按小时统计热度 */
    @Data
    public static class HourRange {
        private Integer startHour;
        private Integer endHour;
    }
}
