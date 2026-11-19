package com.studyroom.enums;

/**
 * 预约状态。数据库中以枚举名（字符串）存储。
 *
 * <pre>
 * RESERVED ──签到──▶ IN_USE ◀──返回── AWAY
 *    │                 │  └──暂离──▶  │
 *    │取消             │签退/到点      │超时
 *    ▼                 ▼              ▼
 * CANCELLED        COMPLETED     AWAY_TIMEOUT
 *    │超时未签到
 *    ▼
 * NO_SHOW
 * </pre>
 */
public enum ReservationStatus {
    /** 已预约，待签到 */
    RESERVED,
    /** 已签到，使用中 */
    IN_USE,
    /** 暂离中 */
    AWAY,
    /** 正常结束（主动签退或到点自动结束） */
    COMPLETED,
    /** 已取消（用户取消或管理员取消） */
    CANCELLED,
    /** 超时未签到，座位已释放，记违约 */
    NO_SHOW,
    /** 暂离超时，座位已释放，记违约 */
    AWAY_TIMEOUT;

    /** 是否仍占用座位 */
    public boolean isActive() {
        return this == RESERVED || this == IN_USE || this == AWAY;
    }
}
