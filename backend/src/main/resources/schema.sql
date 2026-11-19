-- 自习室预约系统 数据库结构（MySQL 8.0，InnoDB，utf8mb4）
-- 全部使用 IF NOT EXISTS，应用每次启动都会执行，可重复运行

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(32)  NOT NULL COMMENT '登录账号',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    real_name   VARCHAR(32)  NOT NULL COMMENT '姓名',
    student_no  VARCHAR(32)  NULL COMMENT '学号',
    phone       VARCHAR(20)  NULL COMMENT '手机号',
    role        VARCHAR(16)  NOT NULL DEFAULT 'STUDENT' COMMENT '角色 STUDENT/ADMIN',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '账号是否启用',
    ban_until   DATETIME     NULL COMMENT '禁约截止时间，为空表示未被禁约',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

CREATE TABLE IF NOT EXISTS study_room (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)  NOT NULL COMMENT '自习室名称',
    location    VARCHAR(100) NOT NULL COMMENT '位置',
    description VARCHAR(255) NULL COMMENT '简介',
    open_hour   TINYINT      NOT NULL COMMENT '开放开始整点 0-23',
    close_hour  TINYINT      NOT NULL COMMENT '开放结束整点 1-24',
    row_count   INT          NOT NULL COMMENT '座位行数',
    col_count   INT          NOT NULL COMMENT '座位列数',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否开放',
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '自习室表';

CREATE TABLE IF NOT EXISTS seat (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    room_id      BIGINT      NOT NULL COMMENT '所属自习室',
    seat_no      VARCHAR(16) NOT NULL COMMENT '座位号，如 A03',
    row_no       INT         NOT NULL COMMENT '所在行，从 1 开始',
    col_no       INT         NOT NULL COMMENT '所在列，从 1 开始',
    has_socket   TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否有电源插座',
    near_window  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否靠窗',
    enabled      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否可预约（维修时停用）',
    checkin_code VARCHAR(32) NOT NULL COMMENT '签到码，印在座位二维码中',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_seat (room_id, seat_no),
    UNIQUE KEY uk_checkin_code (checkin_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '座位表';

CREATE TABLE IF NOT EXISTS reservation (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '预约人',
    seat_id         BIGINT       NOT NULL COMMENT '座位',
    room_id         BIGINT       NOT NULL COMMENT '自习室（冗余，便于统计）',
    reserve_date    DATE         NOT NULL COMMENT '使用日期',
    start_hour      TINYINT      NOT NULL COMMENT '开始整点（含）',
    end_hour        TINYINT      NOT NULL COMMENT '结束整点（不含）',
    status          VARCHAR(16)  NOT NULL COMMENT 'RESERVED/IN_USE/AWAY/COMPLETED/CANCELLED/NO_SHOW/AWAY_TIMEOUT',
    checkin_time    DATETIME     NULL COMMENT '签到时间',
    checkout_time   DATETIME     NULL COMMENT '签退时间',
    away_start_time DATETIME     NULL COMMENT '本次暂离开始时间',
    away_count      INT          NOT NULL DEFAULT 0 COMMENT '已暂离次数',
    remark          VARCHAR(200) NULL COMMENT '备注（取消原因等）',
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_date (user_id, reserve_date),
    KEY idx_seat_date (seat_id, reserve_date),
    KEY idx_room_date (room_id, reserve_date),
    KEY idx_status_date (status, reserve_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '预约记录表（只增不删，保留全部历史）';

-- 座位占用表：一条预约按小时拆成多行，是并发控制的核心
--   uk_seat_slot 保证“一个座位在一个小时内只属于一个人”
--   uk_user_slot 保证“一个人在一个小时内只占一个座位”
-- 预约取消、爽约、提前签退时删除对应行，座位随即可被再次预约
CREATE TABLE IF NOT EXISTS seat_occupancy (
    id             BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    seat_id        BIGINT  NOT NULL COMMENT '座位',
    user_id        BIGINT  NOT NULL COMMENT '占用人',
    use_date       DATE    NOT NULL COMMENT '使用日期',
    hour_slot      TINYINT NOT NULL COMMENT '被占用的整点小时，如 9 表示 9:00-10:00',
    reservation_id BIGINT  NOT NULL COMMENT '来源预约',
    PRIMARY KEY (id),
    UNIQUE KEY uk_seat_slot (seat_id, use_date, hour_slot),
    UNIQUE KEY uk_user_slot (user_id, use_date, hour_slot),
    KEY idx_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '座位占用表';

CREATE TABLE IF NOT EXISTS violation (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id        BIGINT       NOT NULL COMMENT '违约人',
    reservation_id BIGINT       NOT NULL COMMENT '对应预约',
    type           VARCHAR(16)  NOT NULL COMMENT 'NO_SHOW 超时未签到 / AWAY_TIMEOUT 暂离超时',
    remark         VARCHAR(200) NULL COMMENT '说明',
    created_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '违约记录表';

CREATE TABLE IF NOT EXISTS notice (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title        VARCHAR(100) NOT NULL COMMENT '标题',
    content      TEXT         NOT NULL COMMENT '正文',
    pinned       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否置顶',
    publisher_id BIGINT       NOT NULL COMMENT '发布人',
    created_at   DATETIME     NOT NULL,
    updated_at   DATETIME     NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公告表';
