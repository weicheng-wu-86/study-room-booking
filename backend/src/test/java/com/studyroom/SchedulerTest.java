package com.studyroom;

import com.studyroom.entity.Reservation;
import com.studyroom.entity.User;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.task.ReservationScheduler;
import com.studyroom.vo.ReservationVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 定时释放与违约机制的集成测试（对应论文 6.2 节功能测试用例 TC-17 ~ TC-22）。
 * 测试中不等待真实时间流逝，而是拨动 MutableClock 后手动调用 scanOnce()。
 */
class SchedulerTest extends BaseIntegrationTest {

    @Autowired
    private ReservationScheduler scheduler;

    @Test
    @DisplayName("TC-17 超时未签到：判定爽约，释放座位，记一次违约")
    void noShowReleasesSeatAndRecordsViolation() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 11);

        clock.set(DAY.atTime(9, 15));
        assertEquals(0, scheduler.scanOnce());            // 9:15 整仍在宽限期内
        clock.set(DAY.atTime(9, 16));
        assertEquals(1, scheduler.scanOnce());

        assertEquals(ReservationStatus.NO_SHOW, reload(vo.getId()).getStatus());
        assertEquals(0, occupancyCount(vo.getId()));
        assertEquals(1L, violationMapper.selectCount(null).longValue());
        // 座位已释放，其他同学可以预约剩余时段
        assertEquals(ReservationStatus.RESERVED, reserve(newStudent("bob"), 0, 9, 11).getStatus());
    }

    @Test
    @DisplayName("TC-18 重复扫描不会重复处理（幂等）")
    void scanIsIdempotent() {
        reserve(newStudent("alice"), 0, 9, 11);
        clock.set(DAY.atTime(9, 30));
        assertEquals(1, scheduler.scanOnce());
        assertEquals(0, scheduler.scanOnce());
        assertEquals(1L, violationMapper.selectCount(null).longValue());
    }

    @Test
    @DisplayName("TC-19 30 天内第 3 次违约：自动禁约 7 天，禁约期间无法预约")
    void thirdViolationBansUser() {
        User alice = newStudent("alice");
        for (int i = 0; i < 3; i++) {
            int start = 9 + i * 2;
            clock.set(DAY.atTime(start - 1, 0));
            reserve(alice, 0, start, start + 1);
            clock.set(DAY.atTime(start, 16));
            assertEquals(1, scheduler.scanOnce());
            if (i < 2) {
                assertNull(userMapper.selectById(alice.getId()).getBanUntil(), "前两次违约不应禁约");
            }
        }
        assertEquals(DAY.atTime(13, 16).plusDays(7), userMapper.selectById(alice.getId()).getBanUntil());
        assertBusinessError(403, () -> reserve(alice, 0, 15, 16));
    }

    @Test
    @DisplayName("TC-20 暂离超时：判定暂离超时，释放之后的小时，记违约")
    void awayTimeout() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 13);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(alice.getId(), seats.get(0).getCheckinCode());
        clock.set(DAY.atTime(9, 40));
        reservationService.away(alice.getId(), vo.getId());

        clock.advance(Duration.ofMinutes(30));
        assertEquals(0, scheduler.scanOnce());            // 刚好 30 分钟，未超时
        clock.advance(Duration.ofMinutes(1));
        assertEquals(1, scheduler.scanOnce());

        Reservation r = reload(vo.getId());
        assertEquals(ReservationStatus.AWAY_TIMEOUT, r.getStatus());
        assertNotNull(r.getCheckoutTime());
        assertEquals(2, occupancyCount(vo.getId()));      // 此时为 10:11，保留 9、10 点，释放 11、12 点
        assertEquals(1L, violationMapper.selectCount(null).longValue());
    }

    @Test
    @DisplayName("TC-21 暂离超时后用户点击返回：直接按超时处理，不等定时任务")
    void backAfterAwayTimeout() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 13);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(alice.getId(), seats.get(0).getCheckinCode());
        reservationService.away(alice.getId(), vo.getId());

        clock.advance(Duration.ofMinutes(45));
        assertBusinessError(400, () -> reservationService.back(alice.getId(), vo.getId()));
        assertEquals(ReservationStatus.AWAY_TIMEOUT, reload(vo.getId()).getStatus());
        assertEquals(0, scheduler.scanOnce());            // 已处理过，定时任务不再重复处理
    }

    @Test
    @DisplayName("TC-22 到达结束时间：使用中的预约自动结束，签退时间记为结束时刻")
    void autoCompleteAtEnd() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 11);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(alice.getId(), seats.get(0).getCheckinCode());

        clock.set(DAY.atTime(10, 59));
        assertEquals(0, scheduler.scanOnce());
        clock.set(DAY.atTime(11, 0));
        assertEquals(1, scheduler.scanOnce());

        Reservation r = reload(vo.getId());
        assertEquals(ReservationStatus.COMPLETED, r.getStatus());
        assertEquals(DAY.atTime(11, 0), r.getCheckoutTime());
        assertEquals(0L, violationMapper.selectCount(null).longValue());
    }
}
