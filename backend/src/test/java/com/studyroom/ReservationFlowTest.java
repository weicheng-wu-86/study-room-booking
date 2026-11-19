package com.studyroom;

import com.studyroom.dto.Requests;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.User;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.vo.ReservationVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 预约业务规则与状态流转的集成测试（对应论文 6.2 节功能测试用例 TC-01 ~ TC-16）。
 */
class ReservationFlowTest extends BaseIntegrationTest {

    @Test
    @DisplayName("TC-01 正常预约：生成待签到记录，并按小时写入占用")
    void createSuccess() {
        User u = newStudent("alice");
        ReservationVO vo = reserve(u, 0, 9, 12);

        assertEquals(ReservationStatus.RESERVED, vo.getStatus());
        assertEquals("A01", vo.getSeatNo());
        assertEquals(3, occupancyCount(vo.getId()));
        assertEquals(DAY.atTime(8, 45), vo.getCheckinOpenTime());
        assertEquals(DAY.atTime(9, 15), vo.getCheckinDeadline());
    }

    @Test
    @DisplayName("TC-02 同一座位时段重叠：拒绝并返回 409")
    void sameSeatOverlapRejected() {
        reserve(newStudent("alice"), 0, 9, 12);
        User bob = newStudent("bob");
        assertBusinessError(409, () -> reserve(bob, 0, 11, 13));
    }

    @Test
    @DisplayName("TC-03 同一座位相邻时段：允许")
    void adjacentSlotAllowed() {
        reserve(newStudent("alice"), 0, 9, 12);
        ReservationVO vo = reserve(newStudent("bob"), 0, 12, 14);
        assertEquals(ReservationStatus.RESERVED, vo.getStatus());
    }

    @Test
    @DisplayName("TC-04 同一用户同一时段预约两个座位：拒绝")
    void sameUserOverlapRejected() {
        User u = newStudent("alice");
        reserve(u, 0, 9, 11);
        assertBusinessError(409, () -> reserve(u, 1, 10, 12));
    }

    @Test
    @DisplayName("TC-05 时间规则：超时长、过去时段、非开放时间、超出可预约天数均拒绝")
    void timeRulesRejected() {
        User u = newStudent("alice");
        clock.set(DAY.atTime(10, 30));
        Long seatId = seats.get(0).getId();

        assertBusinessError(400, () -> reserve(u, 0, 11, 16));   // 5 小时 > 单次上限 4 小时
        assertBusinessError(400, () -> reserve(u, 0, 9, 11));    // 9 点已过去
        assertBusinessError(400, () -> reserve(u, 0, 20, 23));   // 超出 22 点关闭时间
        assertBusinessError(400, () -> reserve(u, 0, 12, 12));   // 结束不晚于开始
        assertBusinessError(400, () -> reservationService.create(u.getId(),
                new Requests.CreateReservation(seatId, DAY.plusDays(7), 9, 11)));   // 只能预约 7 天内
        assertBusinessError(400, () -> reservationService.create(u.getId(),
                new Requests.CreateReservation(seatId, DAY.minusDays(1), 9, 11)));  // 昨天
    }

    @Test
    @DisplayName("TC-06 允许预约当前这个小时，签到截止时间从提交时刻起算")
    void currentHourAllowed() {
        clock.set(DAY.atTime(10, 40));
        ReservationVO vo = reserve(newStudent("alice"), 0, 10, 12);
        assertEquals(DAY.atTime(10, 55), vo.getCheckinDeadline());
    }

    @Test
    @DisplayName("TC-07 每日累计上限 8 小时：超出拒绝")
    void dailyLimit() {
        User u = newStudent("alice");
        reserve(u, 0, 8, 12);
        reserve(u, 0, 13, 17);
        assertBusinessError(400, () -> reserve(u, 0, 18, 19));
    }

    @Test
    @DisplayName("TC-08 取消预约：状态变为已取消，座位立即可被他人预约")
    void cancelReleasesSeat() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 12);

        reservationService.cancel(alice.getId(), vo.getId());

        assertEquals(ReservationStatus.CANCELLED, reload(vo.getId()).getStatus());
        assertEquals(0, occupancyCount(vo.getId()));
        assertEquals(ReservationStatus.RESERVED, reserve(newStudent("bob"), 0, 9, 12).getStatus());
    }

    @Test
    @DisplayName("TC-09 不能操作他人的预约：返回 404")
    void cannotTouchOthersReservation() {
        ReservationVO vo = reserve(newStudent("alice"), 0, 9, 12);
        User bob = newStudent("bob");
        assertBusinessError(404, () -> reservationService.cancel(bob.getId(), vo.getId()));
        assertBusinessError(404, () -> reservationService.checkout(bob.getId(), vo.getId()));
    }

    @Test
    @DisplayName("TC-10 签到时间窗：过早拒绝，窗口内成功，签到码错误拒绝")
    void checkinWindow() {
        User u = newStudent("alice");
        ReservationVO vo = reserve(u, 0, 9, 12);
        String code = seats.get(0).getCheckinCode();

        clock.set(DAY.atTime(8, 30));
        assertBusinessError(400, () -> reservationService.checkin(u.getId(), code));        // 8:45 才开放签到
        clock.set(DAY.atTime(8, 50));
        assertBusinessError(400, () -> reservationService.checkin(u.getId(), "WRONGCODE1")); // 无效签到码
        assertBusinessError(400, () -> reservationService.checkin(u.getId(), seats.get(1).getCheckinCode())); // 别的座位

        ReservationVO checked = reservationService.checkin(u.getId(), code);
        assertEquals(ReservationStatus.IN_USE, checked.getStatus());
        assertEquals(DAY.atTime(8, 50), checked.getCheckinTime());
        // 重复签到：已没有待签到的预约
        assertBusinessError(400, () -> reservationService.checkin(u.getId(), code));
        assertNotNull(vo.getId());
    }

    @Test
    @DisplayName("TC-11 超过签到截止时间后签到：拒绝")
    void checkinAfterDeadlineRejected() {
        User u = newStudent("alice");
        reserve(u, 0, 9, 12);
        clock.set(DAY.atTime(9, 16));
        assertBusinessError(400, () -> reservationService.checkin(u.getId(), seats.get(0).getCheckinCode()));
    }

    @Test
    @DisplayName("TC-12 暂离与返回：状态往返切换，暂离次数累加，超过 2 次拒绝")
    void awayAndBack() {
        User u = newStudent("alice");
        ReservationVO vo = reserve(u, 0, 9, 12);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(u.getId(), seats.get(0).getCheckinCode());

        ReservationVO away = reservationService.away(u.getId(), vo.getId());
        assertEquals(ReservationStatus.AWAY, away.getStatus());
        assertEquals(DAY.atTime(9, 30), away.getAwayDeadline());

        clock.advance(Duration.ofMinutes(20));
        ReservationVO back = reservationService.back(u.getId(), vo.getId());
        assertEquals(ReservationStatus.IN_USE, back.getStatus());
        assertNull(back.getAwayStartTime());

        reservationService.away(u.getId(), vo.getId());
        reservationService.back(u.getId(), vo.getId());
        assertEquals(2, reload(vo.getId()).getAwayCount());
        assertBusinessError(400, () -> reservationService.away(u.getId(), vo.getId()));
    }

    @Test
    @DisplayName("TC-13 提前签退：释放尚未开始的小时，当前小时保留")
    void earlyCheckoutReleasesRemainingHours() {
        User alice = newStudent("alice");
        ReservationVO vo = reserve(alice, 0, 9, 13);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(alice.getId(), seats.get(0).getCheckinCode());

        clock.set(DAY.atTime(10, 20));
        ReservationVO done = reservationService.checkout(alice.getId(), vo.getId());

        assertEquals(ReservationStatus.COMPLETED, done.getStatus());
        assertEquals(DAY.atTime(10, 20), done.getCheckoutTime());
        assertEquals(2, occupancyCount(vo.getId()));                 // 只剩 9 点、10 点两行
        User bob = newStudent("bob");
        assertEquals(ReservationStatus.RESERVED, reserve(bob, 0, 11, 13).getStatus());  // 11-13 点已释放
        assertBusinessError(409, () -> reserve(newStudent("carol"), 0, 10, 11));        // 10 点仍被占用
    }

    @Test
    @DisplayName("TC-14 只有待签到的预约可以取消")
    void cannotCancelAfterCheckin() {
        User u = newStudent("alice");
        ReservationVO vo = reserve(u, 0, 9, 12);
        clock.set(DAY.atTime(9, 0));
        reservationService.checkin(u.getId(), seats.get(0).getCheckinCode());
        assertBusinessError(400, () -> reservationService.cancel(u.getId(), vo.getId()));
    }

    @Test
    @DisplayName("TC-15 被禁约或被停用的用户不能预约")
    void bannedOrDisabledUserRejected() {
        User banned = newStudent("banned");
        banned.setBanUntil(LocalDateTime.now(clock).plusDays(3));
        userMapper.updateById(banned);
        assertBusinessError(403, () -> reserve(banned, 0, 9, 11));

        User disabled = newStudent("disabled");
        disabled.setEnabled(false);
        userMapper.updateById(disabled);
        assertBusinessError(403, () -> reserve(disabled, 0, 9, 11));
    }

    @Test
    @DisplayName("TC-16 停用的座位不能预约；管理员取消预约后座位释放且不记违约")
    void disabledSeatAndAdminCancel() {
        User u = newStudent("alice");
        roomService.updateSeat(seats.get(2).getId(), new Requests.UpdateSeat(false, null, null));
        assertBusinessError(400, () -> reserve(u, 2, 9, 11));

        ReservationVO vo = reserve(u, 0, 9, 11);
        reservationService.adminCancel(vo.getId(), "座位维修");
        Reservation r = reload(vo.getId());
        assertEquals(ReservationStatus.CANCELLED, r.getStatus());
        assertEquals("管理员取消：座位维修", r.getRemark());
        assertEquals(0, occupancyCount(vo.getId()));
        assertEquals(0, violationMapper.selectCount(null));
    }
}
