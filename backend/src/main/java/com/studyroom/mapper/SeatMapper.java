package com.studyroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyroom.entity.Seat;
import com.studyroom.vo.StatRows;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeatMapper extends BaseMapper<Seat> {

    /** 每个自习室的可用座位数 */
    @Select("SELECT room_id, COUNT(*) AS cnt FROM seat WHERE enabled = 1 GROUP BY room_id")
    List<StatRows.RoomCount> countEnabledByRoom();
}
