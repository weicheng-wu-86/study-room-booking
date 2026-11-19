package com.studyroom.controller;

import com.studyroom.common.PageResult;
import com.studyroom.common.Result;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Notice;
import com.studyroom.entity.Seat;
import com.studyroom.entity.StudyRoom;
import com.studyroom.security.UserContext;
import com.studyroom.service.NoticeService;
import com.studyroom.service.ReservationService;
import com.studyroom.service.RoomService;
import com.studyroom.service.StatsService;
import com.studyroom.service.UserService;
import com.studyroom.service.ViolationService;
import com.studyroom.vo.ReservationVO;
import com.studyroom.vo.ViolationVO;
import com.studyroom.vo.Views;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管理端接口。路径统一以 /api/admin 开头，由 AuthInterceptor 校验管理员角色。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final StatsService statsService;
    private final RoomService roomService;
    private final ReservationService reservationService;
    private final UserService userService;
    private final ViolationService violationService;
    private final NoticeService noticeService;

    // ---------- 数据统计 ----------

    @GetMapping("/stats/overview")
    public Result<Views.Overview> overview() {
        return Result.ok(statsService.overview());
    }

    @GetMapping("/stats/trend")
    public Result<List<Views.TrendPoint>> trend(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statsService.trend(clampDays(days)));
    }

    @GetMapping("/stats/hourly")
    public Result<List<Views.HourPoint>> hourly(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statsService.hourly(clampDays(days)));
    }

    @GetMapping("/stats/rooms")
    public Result<List<Views.RoomUsage>> roomUsage(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(statsService.roomUsage(clampDays(days)));
    }

    // ---------- 自习室与座位 ----------

    @GetMapping("/rooms")
    public Result<List<Views.Room>> rooms() {
        return Result.ok(roomService.list(null, false));
    }

    @PostMapping("/rooms")
    public Result<StudyRoom> createRoom(@Valid @RequestBody Requests.SaveRoom req) {
        return Result.ok(roomService.create(req));
    }

    @PutMapping("/rooms/{id}")
    public Result<StudyRoom> updateRoom(@PathVariable Long id, @Valid @RequestBody Requests.SaveRoom req) {
        return Result.ok(roomService.update(id, req));
    }

    @PutMapping("/rooms/{id}/enabled")
    public Result<Void> setRoomEnabled(@PathVariable Long id, @Valid @RequestBody Requests.SetEnabled req) {
        roomService.setEnabled(id, req.enabled());
        return Result.ok();
    }

    @DeleteMapping("/rooms/{id}")
    public Result<Void> deleteRoom(@PathVariable Long id) {
        roomService.delete(id);
        return Result.ok();
    }

    /** 座位列表，包含签到码（用于打印座位二维码） */
    @GetMapping("/rooms/{id}/seats")
    public Result<List<Seat>> seats(@PathVariable Long id) {
        return Result.ok(roomService.adminSeats(id));
    }

    @PutMapping("/seats/{id}")
    public Result<Seat> updateSeat(@PathVariable Long id, @RequestBody Requests.UpdateSeat req) {
        return Result.ok(roomService.updateSeat(id, req));
    }

    @PostMapping("/seats/{id}/reset-code")
    public Result<Seat> resetSeatCode(@PathVariable Long id) {
        return Result.ok(roomService.resetCheckinCode(id));
    }

    // ---------- 预约管理 ----------

    @GetMapping("/reservations")
    public Result<PageResult<ReservationVO>> reservations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long roomId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(reservationService.adminPage(page, Math.min(size, 100), date, roomId, status, keyword));
    }

    @PostMapping("/reservations/{id}/cancel")
    public Result<Void> cancelReservation(@PathVariable Long id, @Valid @RequestBody Requests.Cancel req) {
        reservationService.adminCancel(id, req.reason());
        return Result.ok();
    }

    // ---------- 用户管理 ----------

    @GetMapping("/users")
    public Result<PageResult<Views.UserInfo>> users(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int size,
                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(userService.page(page, Math.min(size, 100), keyword));
    }

    @PutMapping("/users/{id}/enabled")
    public Result<Void> setUserEnabled(@PathVariable Long id, @Valid @RequestBody Requests.SetEnabled req) {
        userService.setEnabled(UserContext.currentId(), id, req.enabled());
        return Result.ok();
    }

    @PostMapping("/users/{id}/unban")
    public Result<Void> unban(@PathVariable Long id) {
        userService.unban(id);
        return Result.ok();
    }

    /** 重置密码，返回新的随机密码 */
    @PostMapping("/users/{id}/reset-password")
    public Result<Map<String, String>> resetPassword(@PathVariable Long id) {
        return Result.ok(Map.of("password", userService.resetPassword(id)));
    }

    // ---------- 违约管理 ----------

    @GetMapping("/violations")
    public Result<PageResult<ViolationVO>> violations(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int size,
                                                      @RequestParam(required = false) String keyword) {
        return Result.ok(violationService.page(page, Math.min(size, 100), null, keyword));
    }

    @DeleteMapping("/violations/{id}")
    public Result<Void> revokeViolation(@PathVariable Long id) {
        violationService.revoke(id);
        return Result.ok();
    }

    // ---------- 公告管理 ----------

    @PostMapping("/notices")
    public Result<Notice> createNotice(@Valid @RequestBody Requests.SaveNotice req) {
        return Result.ok(noticeService.create(UserContext.currentId(), req));
    }

    @PutMapping("/notices/{id}")
    public Result<Notice> updateNotice(@PathVariable Long id, @Valid @RequestBody Requests.SaveNotice req) {
        return Result.ok(noticeService.update(id, req));
    }

    @DeleteMapping("/notices/{id}")
    public Result<Void> deleteNotice(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.ok();
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 90));
    }
}
