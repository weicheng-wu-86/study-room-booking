package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.studyroom.common.BusinessException;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.Seat;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.entity.StudyRoom;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import com.studyroom.mapper.StudyRoomMapper;
import com.studyroom.vo.StatRows;
import com.studyroom.vo.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自习室与座位：学生端的查询（自习室列表、座位图），管理端的增删改。
 */
@Service
@RequiredArgsConstructor
public class RoomService {

    /** 签到码字符集，去掉了容易混淆的 0/O、1/I */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StudyRoomMapper roomMapper;
    private final SeatMapper seatMapper;
    private final SeatOccupancyMapper occupancyMapper;
    private final ReservationMapper reservationMapper;
    private final Clock clock;

    // ---------- 查询 ----------

    /**
     * 自习室列表及其在 date 当天的占用概况。
     *
     * @param onlyEnabled 学生端只看开放中的自习室，管理端看全部
     */
    public List<Views.Room> list(LocalDate date, boolean onlyEnabled) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        LambdaQueryWrapper<StudyRoom> wrapper = new LambdaQueryWrapper<StudyRoom>().orderByAsc(StudyRoom::getId);
        if (onlyEnabled) {
            wrapper.eq(StudyRoom::getEnabled, true);
        }
        Map<Long, Long> seatCounts = toMap(seatMapper.countEnabledByRoom());
        Map<Long, Long> occupied = toMap(occupancyMapper.countByRoom(day));
        return roomMapper.selectList(wrapper).stream()
                .map(r -> toView(r, seatCounts.getOrDefault(r.getId(), 0L), occupied.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    /** 某自习室在某天的座位图：每个座位哪些小时已被占用、哪些是当前用户自己的 */
    public Views.SeatMap seatMap(Long roomId, LocalDate date, Long userId) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        StudyRoom room = requireRoom(roomId);
        List<Seat> seats = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getRoomId, roomId)
                .orderByAsc(Seat::getRowNo).orderByAsc(Seat::getColNo));

        Map<Long, List<Integer>> othersHours = new HashMap<>();
        Map<Long, List<Integer>> myHours = new HashMap<>();
        for (SeatOccupancy o : occupancyMapper.selectByRoomAndDate(roomId, day)) {
            Map<Long, List<Integer>> target = o.getUserId().equals(userId) ? myHours : othersHours;
            target.computeIfAbsent(o.getSeatId(), k -> new ArrayList<>()).add(o.getHourSlot());
        }

        List<Views.SeatInfo> seatViews = seats.stream()
                .map(s -> new Views.SeatInfo(s.getId(), s.getSeatNo(), s.getRowNo(), s.getColNo(),
                        s.getHasSocket(), s.getNearWindow(), s.getEnabled(),
                        sorted(othersHours.get(s.getId())), sorted(myHours.get(s.getId()))))
                .toList();
        long enabledSeats = seats.stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).count();
        long occupied = othersHours.values().stream().mapToLong(List::size).sum()
                + myHours.values().stream().mapToLong(List::size).sum();
        return new Views.SeatMap(toView(room, enabledSeats, occupied), day, seatViews);
    }

    public List<Seat> adminSeats(Long roomId) {
        requireRoom(roomId);
        return seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getRoomId, roomId)
                .orderByAsc(Seat::getRowNo).orderByAsc(Seat::getColNo));
    }

    // ---------- 管理端：自习室 ----------

    /** 新建自习室，并按“行数 × 列数”自动生成座位 */
    @Transactional
    public StudyRoom create(Requests.SaveRoom req) {
        if (req.rowCount() == null || req.colCount() == null) {
            throw BusinessException.badRequest("请填写座位行数和列数");
        }
        checkHours(req);
        StudyRoom room = new StudyRoom();
        room.setName(req.name().trim());
        room.setLocation(req.location().trim());
        room.setDescription(req.description());
        room.setOpenHour(req.openHour());
        room.setCloseHour(req.closeHour());
        room.setRowCount(req.rowCount());
        room.setColCount(req.colCount());
        room.setEnabled(true);
        room.setCreatedAt(LocalDateTime.now(clock));
        roomMapper.insert(room);

        for (int row = 1; row <= req.rowCount(); row++) {
            for (int col = 1; col <= req.colCount(); col++) {
                Seat seat = new Seat();
                seat.setRoomId(room.getId());
                seat.setSeatNo(seatNo(row, col));
                seat.setRowNo(row);
                seat.setColNo(col);
                seat.setHasSocket(false);
                seat.setNearWindow(false);
                seat.setEnabled(true);
                seat.setCheckinCode(newCheckinCode());
                seatMapper.insert(seat);
            }
        }
        return room;
    }

    /** 修改基本信息与开放时间。座位行列数在创建后不可修改（个别座位可单独停用） */
    public StudyRoom update(Long id, Requests.SaveRoom req) {
        StudyRoom room = requireRoom(id);
        checkHours(req);
        room.setName(req.name().trim());
        room.setLocation(req.location().trim());
        room.setDescription(req.description());
        room.setOpenHour(req.openHour());
        room.setCloseHour(req.closeHour());
        roomMapper.updateById(room);
        return room;
    }

    public void setEnabled(Long id, boolean enabled) {
        requireRoom(id);
        roomMapper.update(null, new LambdaUpdateWrapper<StudyRoom>()
                .eq(StudyRoom::getId, id).set(StudyRoom::getEnabled, enabled));
    }

    /** 删除自习室。已产生过预约记录的自习室不允许删除（历史记录需要它），只能停用 */
    @Transactional
    public void delete(Long id) {
        requireRoom(id);
        long history = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getRoomId, id));
        if (history > 0) {
            throw BusinessException.badRequest("该自习室已有预约记录，不能删除，可以改为停用");
        }
        seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getRoomId, id));
        roomMapper.deleteById(id);
    }

    // ---------- 管理端：座位 ----------

    public Seat updateSeat(Long seatId, Requests.UpdateSeat req) {
        Seat seat = requireSeat(seatId);
        if (req.enabled() != null) {
            seat.setEnabled(req.enabled());
        }
        if (req.hasSocket() != null) {
            seat.setHasSocket(req.hasSocket());
        }
        if (req.nearWindow() != null) {
            seat.setNearWindow(req.nearWindow());
        }
        seatMapper.updateById(seat);
        return seat;
    }

    /** 重置签到码（二维码泄露或损坏时使用） */
    public Seat resetCheckinCode(Long seatId) {
        Seat seat = requireSeat(seatId);
        seat.setCheckinCode(newCheckinCode());
        seatMapper.updateById(seat);
        return seat;
    }

    // ---------- 内部方法 ----------

    private StudyRoom requireRoom(Long id) {
        StudyRoom room = roomMapper.selectById(id);
        if (room == null) {
            throw BusinessException.notFound("自习室不存在");
        }
        return room;
    }

    private Seat requireSeat(Long id) {
        Seat seat = seatMapper.selectById(id);
        if (seat == null) {
            throw BusinessException.notFound("座位不存在");
        }
        return seat;
    }

    private static void checkHours(Requests.SaveRoom req) {
        if (req.openHour() >= req.closeHour()) {
            throw BusinessException.badRequest("关闭时间必须晚于开放时间");
        }
    }

    private static Views.Room toView(StudyRoom r, long seatCount, long occupiedSeatHours) {
        long total = seatCount * (r.getCloseHour() - r.getOpenHour());
        return new Views.Room(r.getId(), r.getName(), r.getLocation(), r.getDescription(),
                r.getOpenHour(), r.getCloseHour(), r.getRowCount(), r.getColCount(), r.getEnabled(),
                seatCount, occupiedSeatHours, total);
    }

    private static Map<Long, Long> toMap(List<StatRows.RoomCount> rows) {
        return rows.stream().collect(Collectors.toMap(StatRows.RoomCount::getRoomId, StatRows.RoomCount::getCnt));
    }

    private static List<Integer> sorted(List<Integer> hours) {
        return hours == null ? List.of() : hours.stream().sorted().toList();
    }

    /** 座位号：行用字母、列用两位数字，如 A03 */
    static String seatNo(int row, int col) {
        return (char) ('A' + row - 1) + String.format("%02d", col);
    }

    static String newCheckinCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
