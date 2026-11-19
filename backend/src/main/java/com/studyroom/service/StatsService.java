package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.StudyRoom;
import com.studyroom.entity.User;
import com.studyroom.entity.Violation;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.enums.Role;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatMapper;
import com.studyroom.mapper.StudyRoomMapper;
import com.studyroom.mapper.UserMapper;
import com.studyroom.mapper.ViolationMapper;
import com.studyroom.vo.StatRows;
import com.studyroom.vo.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端统计。数据库只做分组聚合，比率和补零在 Java 中完成，SQL 保持简单。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final ReservationMapper reservationMapper;
    private final StudyRoomMapper roomMapper;
    private final SeatMapper seatMapper;
    private final UserMapper userMapper;
    private final ViolationMapper violationMapper;
    private final Clock clock;

    /** 首页概览 */
    public Views.Overview overview() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        long todayReservations = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getReserveDate, today)
                .ne(Reservation::getStatus, ReservationStatus.CANCELLED));
        long inUseNow = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .in(Reservation::getStatus, ReservationStatus.IN_USE, ReservationStatus.AWAY));
        long weekViolations = violationMapper.selectCount(new LambdaQueryWrapper<Violation>()
                .ge(Violation::getCreatedAt, now.minusDays(7)));
        long studentCount = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, Role.STUDENT));

        List<Views.RoomUsage> usage = roomUsage(1);
        long seatCount = seatMapper.countEnabledByRoom().stream().mapToLong(StatRows.RoomCount::getCnt).sum();
        long used = usage.stream().mapToLong(Views.RoomUsage::usedHours).sum();
        long capacity = usage.stream().mapToLong(Views.RoomUsage::capacityHours).sum();
        return new Views.Overview(todayReservations, inUseNow, seatCount, rate(used, capacity),
                weekViolations, studentCount);
    }

    /** 最近 days 天（含今天）每天的预约数及其结果构成 */
    public List<Views.TrendPoint> trend(int days) {
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        Map<LocalDate, List<StatRows.DayStatus>> byDay = reservationMapper.countByDayAndStatus(from, to).stream()
                .collect(Collectors.groupingBy(StatRows.DayStatus::getDay));

        List<Views.TrendPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long total = 0, completed = 0, noShow = 0, cancelled = 0;
            for (StatRows.DayStatus row : byDay.getOrDefault(day, List.of())) {
                total += row.getCnt();
                switch (row.getStatus()) {
                    case "COMPLETED" -> completed += row.getCnt();
                    case "NO_SHOW" -> noShow += row.getCnt();
                    case "CANCELLED" -> cancelled += row.getCnt();
                    default -> {
                    }
                }
            }
            points.add(new Views.TrendPoint(day, total, completed, noShow, cancelled));
        }
        return points;
    }

    /** 最近 days 天各小时的平均被预约座位数，用于观察一天中的高峰时段 */
    public List<Views.HourPoint> hourly(int days) {
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        long[] counts = new long[24];
        for (StatRows.HourRange range : reservationMapper.selectHourRanges(from, to)) {
            for (int h = range.getStartHour(); h < range.getEndHour() && h < 24; h++) {
                counts[h]++;
            }
        }
        // 只返回至少有一个自习室开放的小时
        List<StudyRoom> rooms = roomMapper.selectList(null);
        int open = rooms.stream().mapToInt(StudyRoom::getOpenHour).min().orElse(8);
        int close = rooms.stream().mapToInt(StudyRoom::getCloseHour).max().orElse(22);
        List<Views.HourPoint> points = new ArrayList<>();
        for (int h = open; h < close; h++) {
            points.add(new Views.HourPoint(h, Math.round(counts[h] * 10.0 / days) / 10.0));
        }
        return points;
    }

    /** 最近 days 天（含今天）各自习室的使用率 = 已预约的座位·小时 / 可提供的座位·小时 */
    public List<Views.RoomUsage> roomUsage(int days) {
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        Map<Long, Long> seats = toMap(seatMapper.countEnabledByRoom());
        Map<Long, Long> used = toMap(reservationMapper.sumHoursByRoom(from, to));
        return roomMapper.selectList(new LambdaQueryWrapper<StudyRoom>().orderByAsc(StudyRoom::getId)).stream()
                .map(room -> {
                    long usedHours = used.getOrDefault(room.getId(), 0L);
                    long capacity = seats.getOrDefault(room.getId(), 0L)
                            * (room.getCloseHour() - room.getOpenHour()) * days;
                    return new Views.RoomUsage(room.getId(), room.getName(), usedHours, capacity, rate(usedHours, capacity));
                })
                .toList();
    }

    private static Map<Long, Long> toMap(List<StatRows.RoomCount> rows) {
        return rows.stream().collect(Collectors.toMap(StatRows.RoomCount::getRoomId, StatRows.RoomCount::getCnt));
    }

    /** 比率，保留四位小数；分母为 0 时返回 0 */
    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }
}
