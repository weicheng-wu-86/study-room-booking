package com.studyroom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.common.BusinessException;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.entity.User;
import com.studyroom.enums.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 并发预约的集成测试（对应论文 6.3 节）。
 * 所有线程先在发令枪（CountDownLatch）前就位，再同时发起请求，尽量制造真实的并发竞争。
 */
class ConcurrentReserveTest extends BaseIntegrationTest {

    @Test
    @DisplayName("TC-23 100 人同时预约同一座位同一时段：恰好 1 人成功")
    void hundredUsersOneSeat() throws Exception {
        int users = 100;
        List<User> students = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            students.add(newStudent("rush" + i));
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        runConcurrently(students.size(), i -> {
            try {
                reserve(students.get(i), 0, 9, 12);
                success.incrementAndGet();
            } catch (BusinessException e) {
                (e.getCode() == 409 ? conflict : other).incrementAndGet();
            }
        });

        assertEquals(1, success.get(), "只能有 1 人预约成功");
        assertEquals(users - 1, conflict.get(), "其余请求都应得到 409 冲突");
        assertEquals(0, other.get());
        // 数据库中的最终状态：1 条预约、3 行占用，失败请求没有留下任何数据
        assertEquals(1L, reservationMapper.selectCount(null).longValue());
        assertEquals(3L, occupancyMapper.selectCount(null).longValue());
    }

    @Test
    @DisplayName("TC-24 同一用户同时预约 6 个不同座位的同一时段：恰好 1 个成功")
    void oneUserManySeats() throws Exception {
        User alice = newStudent("alice");
        AtomicInteger success = new AtomicInteger();
        runConcurrently(seats.size(), i -> {
            try {
                reserve(alice, i, 9, 11);
                success.incrementAndGet();
            } catch (BusinessException ignored) {
                // 预期中的冲突
            }
        });

        assertEquals(1, success.get());
        assertEquals(1L, reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, ReservationStatus.RESERVED)).longValue());
    }

    @Test
    @DisplayName("TC-25 60 人同时预约 6 个座位的不重叠时段：互不影响，全部成功")
    void nonConflictingRequestsAllSucceed() throws Exception {
        List<User> students = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            students.add(newStudent("user" + i));
        }
        AtomicInteger success = new AtomicInteger();
        // 第 i 个人预约第 i%6 个座位的第 i/6 个小时（8 点起），60 个请求两两不冲突
        runConcurrently(60, i -> {
            reserve(students.get(i), i % 6, 8 + i / 6, 9 + i / 6);
            success.incrementAndGet();
        });
        assertEquals(60, success.get());
        assertEquals(60L, occupancyMapper.selectCount(null).longValue());
    }

    @Test
    @DisplayName("TC-26 绕过分布式锁直接写占用表：唯一索引拒绝重复占用（最终防线）")
    void uniqueIndexIsTheLastDefense() {
        User alice = newStudent("alice");
        User bob = newStudent("bob");
        Long seatId = seats.get(0).getId();
        occupancyMapper.insertBatch(List.of(new SeatOccupancy(null, seatId, alice.getId(), DAY, 9, 1L)));

        // 同一座位同一小时 → 违反 uk_seat_slot
        assertThrows(DuplicateKeyException.class, () -> occupancyMapper.insertBatch(
                List.of(new SeatOccupancy(null, seatId, bob.getId(), DAY, 9, 2L))));
        // 同一用户同一小时占另一个座位 → 违反 uk_user_slot
        assertThrows(DuplicateKeyException.class, () -> occupancyMapper.insertBatch(
                List.of(new SeatOccupancy(null, seats.get(1).getId(), alice.getId(), DAY, 9, 3L))));
    }

    /** 可抛异常的任务 */
    private interface IndexedTask {
        void run(int index) throws Exception;
    }

    /** 启动 n 个线程，全部就绪后同时执行 task，并等待全部结束；任何未预期的异常都会使测试失败 */
    private void runConcurrently(int n, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int index = i;
            Callable<Void> job = () -> {
                ready.countDown();
                go.await();
                task.run(index);
                return null;
            };
            futures.add(pool.submit(job));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }
}
