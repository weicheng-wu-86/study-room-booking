package com.studyroom.vo;

import com.studyroom.enums.Role;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 由 Java 代码组装的响应对象（record）。由 MyBatis 直接映射的查询结果见 ReservationVO、ViolationVO、StatRows。
 */
public final class Views {

    private Views() {
    }

    public record Login(String token, UserInfo user) {
    }

    /** 用户信息，不含密码 */
    public record UserInfo(Long id, String username, String realName, String studentNo, String phone,
                           Role role, Boolean enabled, LocalDateTime banUntil, boolean banned,
                           long recentViolations, LocalDateTime createdAt) {
    }

    /** 预约规则与服务器当前时间。前端一律以服务器时间为准，避免用户设备时区不同造成偏差 */
    public record Rules(int maxDaysAhead, int maxHoursPerReservation, int maxHoursPerDay,
                        int checkinEarlyMinutes, int checkinLateMinutes, int awayMaxMinutes, int awayMaxTimes,
                        int windowDays, int maxViolations, int banDays, LocalDateTime serverTime) {
    }

    /** 自习室及其在某一天的占用概况 */
    public record Room(Long id, String name, String location, String description,
                       Integer openHour, Integer closeHour, Integer rowCount, Integer colCount, Boolean enabled,
                       long seatCount, long occupiedSeatHours, long totalSeatHours) {
    }

    /**
     * 学生端看到的座位。注意不包含签到码，否则学生不到场也能签到。
     *
     * @param occupiedHours 已被他人占用的小时
     * @param myHours       当前用户自己占用的小时
     */
    public record SeatInfo(Long id, String seatNo, Integer rowNo, Integer colNo, Boolean hasSocket,
                           Boolean nearWindow, Boolean enabled, List<Integer> occupiedHours, List<Integer> myHours) {
    }

    public record SeatMap(Room room, LocalDate date, List<SeatInfo> seats) {
    }

    // ---------- 管理端统计 ----------

    public record Overview(long todayReservations, long inUseNow, long seatCount, double todayUsageRate,
                           long weekViolations, long studentCount) {
    }

    public record TrendPoint(LocalDate date, long total, long completed, long noShow, long cancelled) {
    }

    /** 某个小时平均每天有多少个座位被预约 */
    public record HourPoint(int hour, double avgSeats) {
    }

    public record RoomUsage(Long roomId, String roomName, long usedHours, long capacityHours, double rate) {
    }
}
