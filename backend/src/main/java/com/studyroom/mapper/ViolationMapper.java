package com.studyroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyroom.entity.Violation;
import com.studyroom.vo.ViolationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ViolationMapper extends BaseMapper<Violation> {

    String VO_SELECT = """
            SELECT v.id, v.user_id, v.reservation_id, v.type, v.remark, v.created_at,
                   u.username, u.real_name, u.student_no,
                   m.name AS room_name, s.seat_no, r.reserve_date, r.start_hour, r.end_hour
            FROM violation v
            JOIN sys_user u ON u.id = v.user_id
            LEFT JOIN reservation r ON r.id = v.reservation_id
            LEFT JOIN seat s ON s.id = r.seat_id
            LEFT JOIN study_room m ON m.id = r.room_id
            """;

    /** 管理端分页查询，userId 与 keyword 均可为空 */
    @Select(VO_SELECT + """
            WHERE (#{userId} IS NULL OR v.user_id = #{userId})
              AND (#{keyword} IS NULL
                   OR u.username LIKE CONCAT('%', #{keyword}, '%')
                   OR u.real_name LIKE CONCAT('%', #{keyword}, '%')
                   OR u.student_no LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY v.id DESC
            """)
    IPage<ViolationVO> selectPageVO(Page<ViolationVO> page,
                                    @Param("userId") Long userId,
                                    @Param("keyword") String keyword);
}
