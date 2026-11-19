package com.studyroom.controller;

import com.studyroom.common.Result;
import com.studyroom.config.AppProperties;
import com.studyroom.entity.Notice;
import com.studyroom.security.UserContext;
import com.studyroom.service.NoticeService;
import com.studyroom.service.RoomService;
import com.studyroom.vo.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学生端的只读查询：预约规则、公告、自习室列表、座位图。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final NoticeService noticeService;
    private final AppProperties props;
    private final Clock clock;

    /** 预约规则与服务器当前时间 */
    @GetMapping("/rules")
    public Result<Views.Rules> rules() {
        AppProperties.Reserve r = props.getReserve();
        AppProperties.Credit c = props.getCredit();
        return Result.ok(new Views.Rules(r.getMaxDaysAhead(), r.getMaxHoursPerReservation(), r.getMaxHoursPerDay(),
                r.getCheckinEarlyMinutes(), r.getCheckinLateMinutes(), r.getAwayMaxMinutes(), r.getAwayMaxTimes(),
                c.getWindowDays(), c.getMaxViolations(), c.getBanDays(), LocalDateTime.now(clock)));
    }

    @GetMapping("/notices")
    public Result<List<Notice>> notices() {
        return Result.ok(noticeService.list());
    }

    /** 开放中的自习室及其在指定日期的占用概况，date 缺省为今天 */
    @GetMapping("/rooms")
    public Result<List<Views.Room>> rooms(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(roomService.list(date, true));
    }

    /** 座位图 */
    @GetMapping("/rooms/{id}/seats")
    public Result<Views.SeatMap> seatMap(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(roomService.seatMap(id, date, UserContext.currentId()));
    }
}
