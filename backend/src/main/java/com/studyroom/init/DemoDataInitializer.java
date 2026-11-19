package com.studyroom.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.config.AppProperties;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Notice;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.Seat;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.entity.StudyRoom;
import com.studyroom.entity.User;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.enums.Role;
import com.studyroom.enums.ViolationType;
import com.studyroom.mapper.NoticeMapper;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import com.studyroom.mapper.UserMapper;
import com.studyroom.service.RoomService;
import com.studyroom.service.ViolationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 演示数据：首次启动且用户表为空时写入，便于直接体验和答辩演示。
 * <p>
 * 账号：管理员 admin / admin123；学生 stu001 ~ stu120，密码均为 123456。
 * 另外生成 3 间自习室、过去 14 天的历史预约（供统计图表使用）以及今天起三天的有效预约。
 * 随机数使用固定种子，每次生成的数据相同。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private static final String[] SURNAMES = {"王", "李", "张", "刘", "陈", "杨", "黄", "赵", "吴", "周",
            "徐", "孙", "马", "朱", "胡", "郭", "何", "林", "高", "罗"};
    private static final String[] GIVEN_NAMES = {"子涵", "欣怡", "浩然", "雨桐", "梓轩", "思琪", "俊杰", "佳怡", "宇航", "诗涵",
            "博文", "梦瑶", "明轩", "晓彤", "志强", "婉婷", "天宇", "雅琪", "文博", "静怡"};

    private final UserMapper userMapper;
    private final SeatMapper seatMapper;
    private final ReservationMapper reservationMapper;
    private final SeatOccupancyMapper occupancyMapper;
    private final NoticeMapper noticeMapper;
    private final RoomService roomService;
    private final ViolationService violationService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final Clock clock;

    private final Random random = new Random(20261117L);
    /** 已占用的“座位-日期-小时”和“用户-日期-小时”，用来保证生成的数据不违反唯一约束 */
    private final Set<String> taken = new HashSet<>();

    @Override
    public void run(String... args) {
        if (!props.isDemoData() || userMapper.selectCount(null) > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        log.info("数据库为空，开始写入演示数据……");

        User admin = createUser("admin", passwordEncoder.encode("admin123"), "系统管理员", null, Role.ADMIN, now);
        String studentPassword = passwordEncoder.encode("123456");
        List<User> students = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            // 姓和名分别轮换，120 个学生姓名互不相同
            String name = SURNAMES[i % SURNAMES.length] + GIVEN_NAMES[(i + 3 * (i / 20)) % GIVEN_NAMES.length];
            students.add(createUser(String.format("stu%03d", i), studentPassword, name,
                    String.format("2023%04d", 1000 + i), Role.STUDENT, now.minusDays(30)));
        }

        List<StudyRoom> rooms = List.of(
                roomService.create(new Requests.SaveRoom("图书馆一楼综合自习室", "图书馆 1 层东侧",
                        "空间开阔，靠窗座位采光好，适合日常自习。", 8, 22, 6, 8)),
                roomService.create(new Requests.SaveRoom("图书馆三楼考研自习室", "图书馆 3 层 301",
                        "安静区，禁止讨论，座位均配有电源插座。", 7, 23, 5, 10)),
                roomService.create(new Requests.SaveRoom("教学楼 A 座晚自习室", "教学楼 A 座 201",
                        "离宿舍区近，适合晚间自习。", 8, 22, 4, 6)));
        List<Seat> seats = new ArrayList<>();
        for (StudyRoom room : rooms) {
            seats.addAll(decorateSeats(room));
        }

        createNotices(admin.getId(), now);

        // 过去 14 天的历史预约
        for (int daysAgo = 14; daysAgo >= 1; daysAgo--) {
            generateDay(now.toLocalDate().minusDays(daysAgo), 180, rooms, seats, students, now);
        }
        // 今天、明天、后天的预约
        generateDay(now.toLocalDate(), 150, rooms, seats, students, now);
        generateDay(now.toLocalDate().plusDays(1), 80, rooms, seats, students, now);
        generateDay(now.toLocalDate().plusDays(2), 30, rooms, seats, students, now);

        log.info("演示数据写入完成：管理员 admin/admin123，学生 stu001~stu120（密码 123456）");
    }

    private User createUser(String username, String encodedPassword, String realName, String studentNo,
                            Role role, LocalDateTime createdAt) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(encodedPassword);
        u.setRealName(realName);
        u.setStudentNo(studentNo);
        u.setRole(role);
        u.setEnabled(true);
        u.setCreatedAt(createdAt);
        u.setUpdatedAt(createdAt);
        userMapper.insert(u);
        return u;
    }

    /** 给座位加上属性：两侧靠窗、部分有插座，并停用个别座位模拟维修 */
    private List<Seat> decorateSeats(StudyRoom room) {
        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>().eq(Seat::getRoomId, room.getId()));
        for (Seat seat : seats) {
            seat.setNearWindow(seat.getColNo() == 1 || seat.getColNo().equals(room.getColCount()));
            seat.setHasSocket(room.getName().contains("考研") || seat.getRowNo() % 2 == 1);
            // 每间自习室停用一个座位
            seat.setEnabled(!(seat.getRowNo() == 2 && seat.getColNo() == 3));
            seatMapper.updateById(seat);
        }
        return seats.stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).toList();
    }

    private void createNotices(Long adminId, LocalDateTime now) {
        String[][] items = {
                {"自习室预约系统上线通知", "即日起，校内自习室统一通过本系统预约使用。请同学们提前预约、按时签到，离开时主动签退，把座位留给有需要的同学。", "1"},
                {"关于预约违约处理的说明", "预约后超过 15 分钟未签到，或暂离超过 30 分钟未返回，系统将自动释放座位并记一次违约。30 天内累计违约 3 次，将暂停预约资格 7 天。", "0"},
                {"期末复习周开放时间调整", "期末复习周期间，图书馆三楼考研自习室开放时间延长为 7:00-23:00，请合理安排学习时间，注意休息。", "0"}};
        for (int i = 0; i < items.length; i++) {
            Notice n = new Notice();
            n.setTitle(items[i][0]);
            n.setContent(items[i][1]);
            n.setPinned("1".equals(items[i][2]));
            n.setPublisherId(adminId);
            n.setCreatedAt(now.minusDays(items.length - i));
            n.setUpdatedAt(n.getCreatedAt());
            noticeMapper.insert(n);
        }
    }

    /**
     * 为某一天随机生成约 count 条互不冲突的预约，状态按“这一天相对现在”的位置决定：
     * 已结束的时段多数为已完成，少量为取消、爽约；正在进行的为使用中；尚未开始的为待签到。
     */
    private void generateDay(LocalDate date, int count, List<StudyRoom> rooms, List<Seat> seats,
                             List<User> students, LocalDateTime now) {
        for (int i = 0; i < count; i++) {
            Seat seat = seats.get(random.nextInt(seats.size()));
            StudyRoom room = rooms.stream().filter(r -> r.getId().equals(seat.getRoomId())).findFirst().orElseThrow();
            User user = students.get(random.nextInt(students.size()));
            int length = 1 + random.nextInt(4);
            int start = room.getOpenHour() + random.nextInt(room.getCloseHour() - room.getOpenHour() - length + 1);
            int end = start + length;
            if (!claim(seat.getId(), user.getId(), date, start, end)) {
                continue;
            }

            Reservation r = new Reservation();
            r.setUserId(user.getId());
            r.setSeatId(seat.getId());
            r.setRoomId(room.getId());
            r.setReserveDate(date);
            r.setStartHour(start);
            r.setEndHour(end);
            r.setAwayCount(0);
            LocalDateTime startTime = r.startTime();
            LocalDateTime endTime = r.endTime();
            r.setCreatedAt(startTime.minusHours(2 + random.nextInt(20)));
            r.setUpdatedAt(r.getCreatedAt());

            if (!endTime.isAfter(now)) {
                // 已结束的时段
                int dice = random.nextInt(100);
                // 第一个学生（stu001）是演示用的登录账号，不给它生成爽约记录，保证演示时可以正常预约
                if (dice < 87 || (dice >= 96 && user == students.get(0))) {
                    r.setStatus(ReservationStatus.COMPLETED);
                    r.setCheckinTime(startTime.minusMinutes(10).plusMinutes(random.nextInt(20)));
                    r.setCheckoutTime(dice < 20 ? endTime.minusMinutes(10 + random.nextInt(40)) : endTime);
                } else if (dice < 96) {
                    r.setStatus(ReservationStatus.CANCELLED);
                    r.setRemark("用户取消");
                } else {
                    r.setStatus(ReservationStatus.NO_SHOW);
                }
            } else if (!startTime.isAfter(now)) {
                r.setStatus(ReservationStatus.IN_USE);
                r.setCheckinTime(startTime.minusMinutes(5));
            } else {
                r.setStatus(ReservationStatus.RESERVED);
                // 预约时间不能晚于现在
                if (r.getCreatedAt().isAfter(now)) {
                    r.setCreatedAt(now.minusMinutes(1 + random.nextInt(120)));
                    r.setUpdatedAt(r.getCreatedAt());
                }
            }
            reservationMapper.insert(r);

            if (r.getStatus().isActive()) {
                List<SeatOccupancy> slots = new ArrayList<>();
                for (int h = start; h < end; h++) {
                    slots.add(new SeatOccupancy(null, seat.getId(), user.getId(), date, h, r.getId()));
                }
                occupancyMapper.insertBatch(slots);
            } else if (r.getStatus() == ReservationStatus.NO_SHOW) {
                // 走真实的违约记录逻辑：30 天内满 3 次会被禁约 7 天，演示数据里因此会有个别学生处于禁约期
                violationService.record(user.getId(), r.getId(), ViolationType.NO_SHOW,
                        "预约后超过 15 分钟未签到", startTime.plusMinutes(15));
            }
        }
    }

    /** 尝试占用座位和用户在 [start, end) 的每个小时，任一小时已被占用则整体放弃 */
    private boolean claim(Long seatId, Long userId, LocalDate date, int start, int end) {
        List<String> keys = new ArrayList<>();
        for (int h = start; h < end; h++) {
            keys.add("s:" + seatId + ":" + date + ":" + h);
            keys.add("u:" + userId + ":" + date + ":" + h);
        }
        for (String key : keys) {
            if (taken.contains(key)) {
                return false;
            }
        }
        taken.addAll(keys);
        return true;
    }
}
