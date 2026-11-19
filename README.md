# 自习室预约系统

[![CI](https://github.com/weicheng-wu-86/study-room-booking/actions/workflows/ci.yml/badge.svg)](https://github.com/weicheng-wu-86/study-room-booking/actions/workflows/ci.yml)

基于 Spring Boot + Vue 的自习室预约系统（毕业设计）。学生在线选座预约、到场扫码签到、暂离与签退；
系统自动释放超时未签到的座位并记录违约；管理员维护自习室与座位、查看使用统计。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5、MyBatis-Plus 3.5、MySQL 8.0、Redis 7、JJWT |
| 前端 | Vue 3、Vite、Element Plus、Pinia、Vue Router、Axios、ECharts |

## 目录结构

```
backend/        Spring Boot 后端（源码、建表脚本 schema.sql、JUnit 集成测试）
frontend/       Vue 3 前端；mock/server.js 是接口一致的模拟后端
experiments/    并发预约对照实验脚本及实验结果
tools/          SQL 校验脚本、接口冒烟测试脚本
.github/        GitHub Actions：编译、集成测试、启动后端做接口冒烟测试、构建前端
```

## 快速开始

### 方式一：只看前端效果（不需要 Java、MySQL、Redis）

```bash
cd frontend
npm install
npm run mock      # 终端 1：启动模拟后端（8080 端口，内存数据）
npm run dev       # 终端 2：启动前端，打开 http://localhost:5173
```

### 方式二：完整运行

需要 JDK 17+、Maven 3.9+、MySQL 8.0、Redis、Node.js 20+。

```bash
# 1. 启动 MySQL 和 Redis。数据库 studyroom 会在首次启动时自动创建并建表
# 2. 启动后端（数据库账号密码默认为 root/root，可用环境变量覆盖）
cd backend
DB_USER=root DB_PASSWORD=你的密码 mvn spring-boot:run

# 3. 启动前端
cd frontend
npm install
npm run dev
```

首次启动且数据库为空时会自动写入演示数据：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | admin | admin123 |
| 学生 | stu001 ~ stu120 | 123456 |

不需要演示数据时，把 `application.yml` 中的 `studyroom.demo-data` 改为 `false`。

## 测试与验证

```bash
# 后端集成测试（30 个用例，需要本机已启动 MySQL 和 Redis，使用独立的 studyroom_test 库）
cd backend && mvn test

# 接口冒烟测试：先启动后端并等演示数据写入完成，再通过 HTTP 走一遍主要流程（43 项检查）
python tools/api_smoke.py --base http://localhost:8080/api

# SQL 校验：建表脚本、实体与表字段对照、全部手写 SQL
python tools/verify_sql.py --user root --password 你的密码

# 并发预约对照实验（三种并发控制方案 × 三个场景）
python experiments/concurrency_experiment.py --users 100 --rounds 20 --user root --password 你的密码
```

## 核心设计

- **并发预约**：Redis 分布式锁（先用户锁、后座位锁，锁包在事务外面）+ `seat_occupancy` 表的两个唯一索引兜底，
  保证“一座一时一人、一人一时一座”。见 `ReservationService`。
- **状态流转**：所有状态变更都用带条件的 UPDATE（`WHERE status = 旧状态`），
  用户操作与定时任务同时发生时只有一方生效。见 `ReservationMapper.transit`。
- **定时释放**：每 30 秒扫描到期预约，处理爽约、暂离超时、到点结束，可重复执行。见 `ReservationScheduler`。
- **可测试的时间**：业务代码通过注入的 `Clock` 取当前时间，测试中拨动时钟即可验证与时间有关的规则。
- **预约规则可配置**：时长上限、签到时间窗、违约与禁约阈值都在 `application.yml` 的 `studyroom.*` 下。

## 生产部署提示

- 通过环境变量 `JWT_SECRET` 设置不少于 32 字节的密钥，并关闭演示数据。
- 前端执行 `npm run build`，把 `dist/` 交给 Nginx，并将 `/api` 反向代理到后端。
- 座位二维码的内容是 `https://你的域名/#/checkin?code=签到码`，在管理端“自习室管理 → 座位与二维码”中生成和打印。
