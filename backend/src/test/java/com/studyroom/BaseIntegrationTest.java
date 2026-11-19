package com.studyroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.common.BusinessException;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.Seat;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.entity.StudyRoom;
import com.studyroom.entity.User;
import com.studyroom.enums.Role;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import com.studyroom.mapper.UserMapper;
import com.studyroom.mapper.ViolationMapper;
import com.studyroom.service.ReservationService;
import com.studyroom.service.RoomService;
import com.studyroom.vo.ReservationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 集成测试基类：启动完整的 Spring 容器，连接真实的 MySQL 与 Redis（需要本机已启动这两个服务）。
 * 每个测试方法执行前清空所有表，并建好一间 2 行 3 列、8:00-22:00 开放的自习室，
 * 把时钟拨到 2026-11-20 08:00。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(BaseIntegrationTest.ClockConfig.class)
public abstract class BaseIntegrationTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(ZoneId.of("Asia/Shanghai"));
        }
    }

    protected static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Autowired
    protected MutableClock clock;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserMapper userMapper;
    @Autowired
    protected SeatMapper seatMapper;
    @Autowired
    protected ReservationMapper reservationMapper;
    @Autowired
    protected SeatOccupancyMapper occupancyMapper;
    @Autowired
    protected ViolationMapper violationMapper;
    @Autowired
    protected RoomService roomService;
    @Autowired
    protected ReservationService reservationService;

    protected StudyRoom room;
    protected List<Seat> seats;

    @BeforeEach
    void resetDatabase() {
        for (String table : List.of("seat_occupancy", "violation", "reservation", "seat", "study_room", "notice", "sys_user")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        clock.set(DAY.atTime(8, 0));
        room = roomService.create(new Requests.SaveRoom("测试自习室", "测试楼 101", null, 8, 22, 2, 3));
        seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getRoomId, room.getId()).orderByAsc(Seat::getId));
    }

    protected User newStudent(String username) {
        LocalDateTime now = LocalDateTime.now(clock);
        User u = new User();
        u.setUsername(username);
        u.setPassword("not-used-in-tests");
        u.setRealName("测试" + username);
        u.setRole(Role.STUDENT);
        u.setEnabled(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userMapper.insert(u);
        return u;
    }

    /** 在测试日预约 seats[seatIndex] 的 [start, end) */
    protected ReservationVO reserve(User user, int seatIndex, int start, int end) {
        return reservationService.create(user.getId(),
                new Requests.CreateReservation(seats.get(seatIndex).getId(), DAY, start, end));
    }

    protected Reservation reload(Long reservationId) {
        return reservationMapper.selectById(reservationId);
    }

    protected long occupancyCount(Long reservationId) {
        return occupancyMapper.selectCount(new LambdaQueryWrapper<SeatOccupancy>()
                .eq(SeatOccupancy::getReservationId, reservationId));
    }

    /** 断言 action 抛出指定状态码的业务异常 */
    protected void assertBusinessError(int expectedCode, Executable action) {
        BusinessException e = assertThrows(BusinessException.class, action);
        assertEquals(expectedCode, e.getCode(), "错误信息：" + e.getMessage());
    }
}
