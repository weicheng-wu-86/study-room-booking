package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 座位 */
@Data
@TableName("seat")
public class Seat {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long roomId;
    /** 座位号，如 A03（A 行第 3 列） */
    private String seatNo;
    private Integer rowNo;
    private Integer colNo;
    private Boolean hasSocket;
    private Boolean nearWindow;
    /** 是否可预约，维修或作为过道时停用 */
    private Boolean enabled;
    /** 签到码，写入座位二维码，学生到场扫码完成签到 */
    private String checkinCode;
}
