package com.studyroom.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyroom.entity.Reservation;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.vo.ReservationVO;
import com.studyroom.vo.StatRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {

    String VO_SELECT = """
            SELECT r.id, r.user_id, r.seat_id, r.room_id, r.reserve_date, r.start_hour, r.end_hour, r.status,
                   r.checkin_time, r.checkout_time, r.away_start_time, r.away_count, r.remark, r.created_at,
                   s.seat_no, m.name AS room_name, m.location AS room_location,
                   u.username, u.real_name, u.student_no
            FROM reservation r
            JOIN seat s ON s.id = r.seat_id
            JOIN study_room m ON m.id = r.room_id
            JOIN sys_user u ON u.id = r.user_id
            """;

    /**
     * 带条件的状态流转（乐观并发控制）：只有当前状态仍是 from 时才更新为 to。
     * 返回 false 说明状态已被其他请求或定时任务改变，调用方据此放弃后续操作。
     * 例如“用户点击签到”和“定时任务判定爽约”同时发生时，只有一方能成功。
     *
     * @param extra 流转时需要一并修改的其他字段，可为 null
     */
    default boolean transit(Long id, ReservationStatus from, ReservationStatus to, LocalDateTime now,
                            Consumer<LambdaUpdateWrapper<Reservation>> extra) {
        LambdaUpdateWrapper<Reservation> wrapper = new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getStatus, from)
                .set(Reservation::getStatus, to)
                .set(Reservation::getUpdatedAt, now);
        if (extra != null) {
            extra.accept(wrapper);
        }
        return update(null, wrapper) == 1;
    }

    @Select(VO_SELECT + "WHERE r.id = #{id}")
    ReservationVO selectVOById(@Param("id") Long id);

    /** 我的预约（分页），status 为空表示全部 */
    @Select(VO_SELECT + """
            WHERE r.user_id = #{userId}
              AND (#{status} IS NULL OR r.status = #{status})
            ORDER BY r.reserve_date DESC, r.start_hour DESC, r.id DESC
            """)
    IPage<ReservationVO> selectMyPage(Page<ReservationVO> page,
                                      @Param("userId") Long userId,
                                      @Param("status") String status);

    /** 我当前仍有效的预约（待签到、使用中、暂离），按时间先后 */
    @Select(VO_SELECT + """
            WHERE r.user_id = #{userId} AND r.status IN ('RESERVED', 'IN_USE', 'AWAY')
            ORDER BY r.reserve_date, r.start_hour
            """)
    List<ReservationVO> selectActiveByUser(@Param("userId") Long userId);

    /** 管理端分页查询，所有条件均可为空 */
    @Select(VO_SELECT + """
            WHERE (#{date} IS NULL OR r.reserve_date = #{date})
              AND (#{roomId} IS NULL OR r.room_id = #{roomId})
              AND (#{status} IS NULL OR r.status = #{status})
              AND (#{keyword} IS NULL
                   OR u.username LIKE CONCAT('%', #{keyword}, '%')
                   OR u.real_name LIKE CONCAT('%', #{keyword}, '%')
                   OR u.student_no LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY r.id DESC
            """)
    IPage<ReservationVO> selectAdminPage(Page<ReservationVO> page,
                                         @Param("date") LocalDate date,
                                         @Param("roomId") Long roomId,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword);

    /** 用户某天已预约的总小时数（已取消和爽约的不计） */
    @Select("""
            SELECT COALESCE(SUM(end_hour - start_hour), 0) FROM reservation
            WHERE user_id = #{userId} AND reserve_date = #{date}
              AND status NOT IN ('CANCELLED', 'NO_SHOW')
            """)
    int sumHoursOfDay(@Param("userId") Long userId, @Param("date") LocalDate date);

    // ---------- 统计 ----------

    /** 日期区间内，按“日期 + 状态”分组的预约数 */
    @Select("""
            SELECT reserve_date AS day, status, COUNT(*) AS cnt FROM reservation
            WHERE reserve_date BETWEEN #{from} AND #{to}
            GROUP BY reserve_date, status
            """)
    List<StatRows.DayStatus> countByDayAndStatus(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 日期区间内，各自习室实际被预约的“座位·小时”数（已取消和爽约的不计） */
    @Select("""
            SELECT room_id, COALESCE(SUM(end_hour - start_hour), 0) AS cnt FROM reservation
            WHERE reserve_date BETWEEN #{from} AND #{to}
              AND status NOT IN ('CANCELLED', 'NO_SHOW')
            GROUP BY room_id
            """)
    List<StatRows.RoomCount> sumHoursByRoom(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 日期区间内有效预约的起止小时，用于统计各小时热度 */
    @Select("""
            SELECT start_hour, end_hour FROM reservation
            WHERE reserve_date BETWEEN #{from} AND #{to}
              AND status NOT IN ('CANCELLED', 'NO_SHOW')
            """)
    List<StatRows.HourRange> selectHourRanges(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
