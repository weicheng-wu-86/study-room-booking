package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyroom.common.BusinessException;
import com.studyroom.common.PageResult;
import com.studyroom.dto.Requests;
import com.studyroom.entity.User;
import com.studyroom.enums.Role;
import com.studyroom.mapper.UserMapper;
import com.studyroom.security.JwtUtil;
import com.studyroom.vo.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户：注册、登录、个人资料，以及管理端的用户管理。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String PASSWORD_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final ViolationService violationService;
    private final Clock clock;

    public Views.UserInfo register(Requests.Register req) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setRealName(req.realName().trim());
        user.setStudentNo(blankToNull(req.studentNo()));
        user.setPhone(blankToNull(req.phone()));
        user.setRole(Role.STUDENT);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            // 不先查再插：账号唯一性交给唯一索引保证，并发注册同一账号也只有一个成功
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该账号已被注册");
        }
        return toInfo(user, now);
    }

    public Views.Login login(Requests.Login req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.username()));
        // 账号不存在与密码错误返回同一提示，避免被用来探测账号是否存在
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw BusinessException.badRequest("账号或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw BusinessException.forbidden("账号已被停用，请联系管理员");
        }
        return new Views.Login(jwtUtil.generate(user), toInfo(user, LocalDateTime.now(clock)));
    }

    public Views.UserInfo info(Long userId) {
        return toInfo(require(userId), LocalDateTime.now(clock));
    }

    public Views.UserInfo updateProfile(Long userId, Requests.Profile req) {
        LocalDateTime now = LocalDateTime.now(clock);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getRealName, req.realName().trim())
                .set(User::getPhone, blankToNull(req.phone()))
                .set(User::getUpdatedAt, now));
        return info(userId);
    }

    public void changePassword(Long userId, Requests.ChangePassword req) {
        User user = require(userId);
        if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
            throw BusinessException.badRequest("原密码不正确");
        }
        updatePassword(userId, req.newPassword());
    }

    // ---------- 管理端 ----------

    public PageResult<Views.UserInfo> page(int page, int size, String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().orderByDesc(User::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getRealName, kw)
                    .or().like(User::getStudentNo, kw));
        }
        Page<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        LocalDateTime now = LocalDateTime.now(clock);
        List<Views.UserInfo> records = result.getRecords().stream().map(u -> toInfo(u, now)).toList();
        return new PageResult<>(result.getTotal(), records);
    }

    public void setEnabled(Long operatorId, Long userId, boolean enabled) {
        if (operatorId.equals(userId)) {
            throw BusinessException.badRequest("不能停用自己的账号");
        }
        require(userId);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getEnabled, enabled)
                .set(User::getUpdatedAt, LocalDateTime.now(clock)));
    }

    /** 解除禁约 */
    public void unban(Long userId) {
        require(userId);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getBanUntil, null)
                .set(User::getUpdatedAt, LocalDateTime.now(clock)));
    }

    /** 重置密码：生成 8 位随机密码并返回给管理员，由管理员转告用户 */
    public String resetPassword(Long userId) {
        require(userId);
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        updatePassword(userId, sb.toString());
        return sb.toString();
    }

    // ---------- 内部方法 ----------

    private void updatePassword(Long userId, String rawPassword) {
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(rawPassword))
                .set(User::getUpdatedAt, LocalDateTime.now(clock)));
    }

    private User require(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }

    private Views.UserInfo toInfo(User u, LocalDateTime now) {
        boolean banned = u.getBanUntil() != null && u.getBanUntil().isAfter(now);
        long recent = u.getId() == null ? 0 : violationService.countRecent(u.getId(), now);
        return new Views.UserInfo(u.getId(), u.getUsername(), u.getRealName(), u.getStudentNo(), u.getPhone(),
                u.getRole(), u.getEnabled(), u.getBanUntil(), banned, recent, u.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
