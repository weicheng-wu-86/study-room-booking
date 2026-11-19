package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.common.BusinessException;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Notice;
import com.studyroom.mapper.NoticeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告：管理员发布，学生端首页展示。
 */
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeMapper noticeMapper;
    private final Clock clock;

    /** 置顶优先，其次按发布时间倒序 */
    public List<Notice> list() {
        return noticeMapper.selectList(new LambdaQueryWrapper<Notice>()
                .orderByDesc(Notice::getPinned)
                .orderByDesc(Notice::getCreatedAt));
    }

    public Notice create(Long publisherId, Requests.SaveNotice req) {
        LocalDateTime now = LocalDateTime.now(clock);
        Notice n = new Notice();
        n.setTitle(req.title().trim());
        n.setContent(req.content());
        n.setPinned(Boolean.TRUE.equals(req.pinned()));
        n.setPublisherId(publisherId);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        noticeMapper.insert(n);
        return n;
    }

    public Notice update(Long id, Requests.SaveNotice req) {
        Notice n = noticeMapper.selectById(id);
        if (n == null) {
            throw BusinessException.notFound("公告不存在");
        }
        n.setTitle(req.title().trim());
        n.setContent(req.content());
        n.setPinned(Boolean.TRUE.equals(req.pinned()));
        n.setUpdatedAt(LocalDateTime.now(clock));
        noticeMapper.updateById(n);
        return n;
    }

    public void delete(Long id) {
        if (noticeMapper.deleteById(id) == 0) {
            throw BusinessException.notFound("公告不存在");
        }
    }
}
