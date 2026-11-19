package com.studyroom.controller;

import com.studyroom.common.PageResult;
import com.studyroom.common.Result;
import com.studyroom.dto.Requests;
import com.studyroom.security.UserContext;
import com.studyroom.service.ReservationService;
import com.studyroom.service.ViolationService;
import com.studyroom.vo.ReservationVO;
import com.studyroom.vo.ViolationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端的预约操作。所有接口都以“当前登录用户”为主体，用户 id 取自令牌而不是请求参数，
 * 因此无法通过改参数操作他人的预约。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ViolationService violationService;

    @PostMapping("/reservations")
    public Result<ReservationVO> create(@Valid @RequestBody Requests.CreateReservation req) {
        return Result.ok(reservationService.create(UserContext.currentId(), req));
    }

    /** 我当前有效的预约（待签到、使用中、暂离） */
    @GetMapping("/reservations/active")
    public Result<List<ReservationVO>> active() {
        return Result.ok(reservationService.active(UserContext.currentId()));
    }

    @GetMapping("/reservations/mine")
    public Result<PageResult<ReservationVO>> mine(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  @RequestParam(required = false) String status) {
        return Result.ok(reservationService.myPage(UserContext.currentId(), page, Math.min(size, 100), status));
    }

    @PostMapping("/reservations/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        reservationService.cancel(UserContext.currentId(), id);
        return Result.ok();
    }

    /** 扫码签到：请求体中的 code 为座位二维码里的签到码 */
    @PostMapping("/reservations/checkin")
    public Result<ReservationVO> checkin(@Valid @RequestBody Requests.Checkin req) {
        return Result.ok(reservationService.checkin(UserContext.currentId(), req.code()));
    }

    @PostMapping("/reservations/{id}/away")
    public Result<ReservationVO> away(@PathVariable Long id) {
        return Result.ok(reservationService.away(UserContext.currentId(), id));
    }

    @PostMapping("/reservations/{id}/back")
    public Result<ReservationVO> back(@PathVariable Long id) {
        return Result.ok(reservationService.back(UserContext.currentId(), id));
    }

    @PostMapping("/reservations/{id}/checkout")
    public Result<ReservationVO> checkout(@PathVariable Long id) {
        return Result.ok(reservationService.checkout(UserContext.currentId(), id));
    }

    /** 我的违约记录 */
    @GetMapping("/violations/mine")
    public Result<PageResult<ViolationVO>> myViolations(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        return Result.ok(violationService.page(page, Math.min(size, 100), UserContext.currentId(), null));
    }
}
