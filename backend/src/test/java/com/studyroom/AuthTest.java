package com.studyroom;

import com.studyroom.dto.Requests;
import com.studyroom.entity.User;
import com.studyroom.enums.Role;
import com.studyroom.security.JwtUtil;
import com.studyroom.security.LoginUser;
import com.studyroom.service.UserService;
import com.studyroom.vo.Views;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 注册、登录与令牌的集成测试（对应论文 6.2 节功能测试用例 TC-27 ~ TC-30）。
 */
class AuthTest extends BaseIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtUtil jwtUtil;

    private static Requests.Register registerRequest(String username) {
        return new Requests.Register(username, "secret123", "张三", "20230001", "13800000000");
    }

    @Test
    @DisplayName("TC-27 注册：密码以 BCrypt 密文保存；重复账号返回 409")
    void register() {
        Views.UserInfo info = userService.register(registerRequest("zhangsan"));
        assertEquals(Role.STUDENT, info.role());

        User saved = userMapper.selectById(info.id());
        assertNotEquals("secret123", saved.getPassword());
        assertEquals(60, saved.getPassword().length());   // BCrypt 密文固定 60 位

        assertBusinessError(409, () -> userService.register(registerRequest("zhangsan")));
    }

    @Test
    @DisplayName("TC-28 登录：密码正确返回令牌；密码错误、账号不存在返回 400；停用账号返回 403")
    void login() {
        Views.UserInfo info = userService.register(registerRequest("zhangsan"));

        Views.Login ok = userService.login(new Requests.Login("zhangsan", "secret123"));
        assertNotNull(ok.token());
        assertEquals("zhangsan", ok.user().username());

        assertBusinessError(400, () -> userService.login(new Requests.Login("zhangsan", "wrong-password")));
        assertBusinessError(400, () -> userService.login(new Requests.Login("nobody", "secret123")));

        User u = userMapper.selectById(info.id());
        u.setEnabled(false);
        userMapper.updateById(u);
        assertBusinessError(403, () -> userService.login(new Requests.Login("zhangsan", "secret123")));
    }

    @Test
    @DisplayName("TC-29 令牌：可解析出用户身份；被篡改或已过期的令牌解析失败")
    void token() {
        userService.register(registerRequest("zhangsan"));
        String token = userService.login(new Requests.Login("zhangsan", "secret123")).token();

        LoginUser user = jwtUtil.parse(token);
        assertNotNull(user);
        assertEquals("zhangsan", user.username());
        assertFalse(user.isAdmin());

        assertNull(jwtUtil.parse(token.substring(0, token.length() - 2) + "xx"));   // 篡改签名
        assertNull(jwtUtil.parse("not-a-jwt"));

        clock.advance(Duration.ofHours(25));                                        // 有效期 24 小时
        assertNull(jwtUtil.parse(token));
    }

    @Test
    @DisplayName("TC-30 修改密码：原密码错误拒绝；修改后旧密码失效、新密码可登录")
    void changePassword() {
        Views.UserInfo info = userService.register(registerRequest("zhangsan"));

        assertBusinessError(400, () -> userService.changePassword(info.id(),
                new Requests.ChangePassword("wrong-old", "newpass456")));
        userService.changePassword(info.id(), new Requests.ChangePassword("secret123", "newpass456"));

        assertBusinessError(400, () -> userService.login(new Requests.Login("zhangsan", "secret123")));
        assertNotNull(userService.login(new Requests.Login("zhangsan", "newpass456")).token());
    }
}
