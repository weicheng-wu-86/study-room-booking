package com.studyroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.vo.StatRows;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SeatOccupancyMapper extends BaseMapper<SeatOccupancy> {

    /** 座位在 [start, end) 内已被占用的小时数 */
    @Select("""
            SELECT COUNT(*) FROM seat_occupancy
            WHERE seat_id = #{seatId} AND use_date = #{date}
              AND hour_slot >= #{start} AND hour_slot < #{end}
            """)
    int countSeatConflict(@Param("seatId") Long seatId, @Param("date") LocalDate date,
                          @Param("start") int start, @Param("end") int end);

    /** 用户在 [start, end) 内已占用座位的小时数 */
    @Select("""
            SELECT COUNT(*) FROM seat_occupancy
            WHERE user_id = #{userId} AND use_date = #{date}
              AND hour_slot >= #{start} AND hour_slot < #{end}
            """)
    int countUserConflict(@Param("userId") Long userId, @Param("date") LocalDate date,
                          @Param("start") int start, @Param("end") int end);

    /** 一条 INSERT 写入一次预约的全部小时；任何一行违反唯一索引，整条语句失败 */
    @Insert("""
            <script>
            INSERT INTO seat_occupancy (seat_id, user_id, use_date, hour_slot, reservation_id) VALUES
            <foreach collection="list" item="o" separator=",">
                (#{o.seatId}, #{o.userId}, #{o.useDate}, #{o.hourSlot}, #{o.reservationId})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<SeatOccupancy> list);

    /** 释放一条预约占用的全部小时（取消、爽约） */
    @Delete("DELETE FROM seat_occupancy WHERE reservation_id = #{reservationId}")
    int deleteByReservation(@Param("reservationId") Long reservationId);

    /** 释放一条预约在 hour 之后的小时（提前签退、暂离超时），当前小时保留 */
    @Delete("DELETE FROM seat_occupancy WHERE reservation_id = #{reservationId} AND hour_slot > #{hour}")
    int deleteAfterHour(@Param("reservationId") Long reservationId, @Param("hour") int hour);

    /** 清理历史占用，占用表只需保留当天及以后的数据 */
    @Delete("DELETE FROM seat_occupancy WHERE use_date < #{date}")
    int deleteBefore(@Param("date") LocalDate date);

    /** 某自习室某天的全部占用，用于绘制座位图 */
    @Select("""
            SELECT o.seat_id, o.user_id, o.hour_slot
            FROM seat_occupancy o JOIN seat s ON s.id = o.seat_id
            WHERE s.room_id = #{roomId} AND o.use_date = #{date}
            """)
    List<SeatOccupancy> selectByRoomAndDate(@Param("roomId") Long roomId, @Param("date") LocalDate date);

    /** 某天各自习室已被占用的“座位·小时”数 */
    @Select("""
            SELECT s.room_id, COUNT(*) AS cnt
            FROM seat_occupancy o JOIN seat s ON s.id = o.seat_id
            WHERE o.use_date = #{date}
            GROUP BY s.room_id
            """)
    List<StatRows.RoomCount> countByRoom(@Param("date") LocalDate date);
}
