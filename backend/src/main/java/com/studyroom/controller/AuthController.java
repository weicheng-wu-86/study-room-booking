package com.studyroom.controller;

import com.studyroom.common.Result;
import com.studyroom.dto.Requests;
import com.studyroom.security.UserContext;
import com.studyroom.service.UserService;
import com.studyroom.vo.Views;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与个人资料。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<Views.UserInfo> register(@Valid @RequestBody Requests.Register req) {
        return Result.ok(userService.register(req));
    }

    @PostMapping("/login")
    public Result<Views.Login> login(@Valid @RequestBody Requests.Login req) {
        return Result.ok(userService.login(req));
    }

    /** 当前登录用户的信息，含禁约状态与近期违约次数 */
    @GetMapping("/me")
    public Result<Views.UserInfo> me() {
        return Result.ok(userService.info(UserContext.currentId()));
    }

    @PutMapping("/profile")
    public Result<Views.UserInfo> updateProfile(@Valid @RequestBody Requests.Profile req) {
        return Result.ok(userService.updateProfile(UserContext.currentId(), req));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody Requests.ChangePassword req) {
        userService.changePassword(UserContext.currentId(), req);
        return Result.ok();
    }
}
