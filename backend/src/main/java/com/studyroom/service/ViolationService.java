package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyroom.common.BusinessException;
import com.studyroom.common.PageResult;
import com.studyroom.config.AppProperties;
import com.studyroom.entity.User;
import com.studyroom.entity.Violation;
import com.studyroom.enums.ViolationType;
import com.studyroom.mapper.UserMapper;
import com.studyroom.mapper.ViolationMapper;
import com.studyroom.vo.ViolationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 违约与信用：记录违约，并在统计窗口内违约次数达到上限时自动禁约。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    private final ViolationMapper violationMapper;
    private final UserMapper userMapper;
    private final AppProperties props;

    /**
     * 记录一次违约。由预约状态流转方法在同一事务中调用。
     */
    @Transactional
    public void record(Long userId, Long reservationId, ViolationType type, String remark, LocalDateTime now) {
        Violation v = new Violation();
        v.setUserId(userId);
        v.setReservationId(reservationId);
        v.setType(type);
        v.setRemark(remark);
        v.setCreatedAt(now);
        violationMapper.insert(v);

        AppProperties.Credit credit = props.getCredit();
        long recent = countRecent(userId, now);
        if (recent >= credit.getMaxViolations()) {
            LocalDateTime banUntil = now.plusDays(credit.getBanDays());
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getBanUntil, banUntil)
                    .set(User::getUpdatedAt, now));
            log.info("用户 {} 在 {} 天内违约 {} 次，禁约至 {}", userId, credit.getWindowDays(), recent, banUntil);
        }
    }

    /** 统计窗口内的违约次数 */
    public long countRecent(Long userId, LocalDateTime now) {
        return violationMapper.selectCount(new LambdaQueryWrapper<Violation>()
                .eq(Violation::getUserId, userId)
                .ge(Violation::getCreatedAt, now.minusDays(props.getCredit().getWindowDays())));
    }

    /** 分页查询；userId 不为空时只查该用户（学生查看自己的记录） */
    public PageResult<ViolationVO> page(int page, int size, Long userId, String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return PageResult.of(violationMapper.selectPageVO(new Page<>(page, size), userId, kw));
    }

    /** 管理员撤销一条违约（申诉成立）。是否解除禁约由管理员在用户管理中另行操作 */
    public void revoke(Long id) {
        if (violationMapper.deleteById(id) == 0) {
            throw BusinessException.notFound("违约记录不存在");
        }
    }
}
