package com.studyroom.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 全部请求体。请求体都是只读的数据载体，用 record 定义并集中在一个文件里，便于对照接口文档。
 * 字段上的校验注解由 Controller 的 @Valid 触发，校验失败统一返回 400。
 */
public final class Requests {

    private Requests() {
    }

    public record Login(
            @NotBlank(message = "请输入账号") String username,
            @NotBlank(message = "请输入密码") String password) {
    }

    public record Register(
            @NotBlank(message = "请输入账号")
            @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "账号需为 4-20 位字母、数字或下划线") String username,
            @NotBlank(message = "请输入密码")
            @Size(min = 6, max = 32, message = "密码长度需为 6-32 位") String password,
            @NotBlank(message = "请输入姓名")
            @Size(max = 20, message = "姓名不能超过 20 个字") String realName,
            @Size(max = 32, message = "学号不能超过 32 位") String studentNo,
            @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号格式不正确") String phone) {
    }

    public record Profile(
            @NotBlank(message = "请输入姓名")
            @Size(max = 20, message = "姓名不能超过 20 个字") String realName,
            @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号格式不正确") String phone) {
    }

    public record ChangePassword(
            @NotBlank(message = "请输入原密码") String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Size(min = 6, max = 32, message = "密码长度需为 6-32 位") String newPassword) {
    }

    /** 预约请求：时段为 [startHour, endHour) */
    public record CreateReservation(
            @NotNull(message = "请选择座位") Long seatId,
            @NotNull(message = "请选择日期") LocalDate date,
            @NotNull(message = "请选择开始时间") @Min(0) @Max(23) Integer startHour,
            @NotNull(message = "请选择结束时间") @Min(1) @Max(24) Integer endHour) {
    }

    public record Checkin(@NotBlank(message = "请输入或扫描座位签到码") String code) {
    }

    public record Cancel(@Size(max = 100, message = "原因不能超过 100 个字") String reason) {
    }

    public record SaveRoom(
            @NotBlank(message = "请输入自习室名称") @Size(max = 50) String name,
            @NotBlank(message = "请输入位置") @Size(max = 100) String location,
            @Size(max = 255) String description,
            @NotNull(message = "请选择开放时间") @Min(0) @Max(23) Integer openHour,
            @NotNull(message = "请选择关闭时间") @Min(1) @Max(24) Integer closeHour,
            @Min(value = 1, message = "行数至少为 1") @Max(value = 20, message = "行数不能超过 20") Integer rowCount,
            @Min(value = 1, message = "列数至少为 1") @Max(value = 20, message = "列数不能超过 20") Integer colCount) {
    }

    public record UpdateSeat(Boolean enabled, Boolean hasSocket, Boolean nearWindow) {
    }

    public record SetEnabled(@NotNull(message = "缺少 enabled 参数") Boolean enabled) {
    }

    public record SaveNotice(
            @NotBlank(message = "请输入标题") @Size(max = 100, message = "标题不能超过 100 个字") String title,
            @NotBlank(message = "请输入内容") String content,
            Boolean pinned) {
    }
}
