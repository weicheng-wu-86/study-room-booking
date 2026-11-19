package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 座位占用：一条预约按小时拆成多行。
 * 表上的两个唯一索引 (seat_id, use_date, hour_slot) 与 (user_id, use_date, hour_slot)
 * 从数据库层面保证不会出现“一座多约”和“一人多座”。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("seat_occupancy")
public class SeatOccupancy {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long seatId;
    private Long userId;
    private LocalDate useDate;
    /** 被占用的整点小时，9 表示 9:00-10:00 */
    private Integer hourSlot;
    private Long reservationId;
}
