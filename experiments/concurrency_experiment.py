#!/usr/bin/env python3
"""
并发预约对照实验（数据库层）。

在真实的 MySQL 与 Redis 上，用多线程同时执行“预约事务”，比较三种并发控制方案：

  naive         先查后插：查询预约表没有冲突就插入，没有任何并发保护（常见的初学者写法）
  unique-index  唯一索引：按小时写入 seat_occupancy，靠 (seat_id, use_date, hour_slot) 唯一索引拒绝重复
  lock+index    分布式锁 + 唯一索引：本系统采用的方案，SQL 语句序列与后端 ReservationService.doCreate 完全一致

三个场景：
  S1 抢同一座位同一时段   N 个用户同时预约同一座位的 9:00-12:00，正确结果是恰好 1 人成功
  S2 抢同一座位重叠时段   N 个用户同时预约同一座位的随机时段，正确结果是任意一小时最多 1 人
  S3 无冲突             N 个用户同时预约各不相同的座位，全部应当成功，用来比较三种方案的耗时

用法：
    pip install pymysql redis
    python experiments/concurrency_experiment.py --users 100 --rounds 20 \
        [--host 127.0.0.1 --port 3306 --user root --password root --redis-host 127.0.0.1]

注意：这是对“并发控制方案”本身的验证，线程里执行的是与后端相同的 SQL，但不经过 Spring Boot。
后端代码层面的并发测试见 backend/src/test/java/com/studyroom/ConcurrentReserveTest.java。
"""
import argparse
import datetime as dt
import json
import random
import statistics
import threading
import time
import uuid
from pathlib import Path

import pymysql
import redis

ROOT = Path(__file__).resolve().parent.parent
DB = "studyroom_exp"
DAY = dt.date(2026, 11, 20)
NOW = dt.datetime(2026, 11, 20, 8, 0, 0)
STRATEGIES = ["naive", "unique-index", "lock+index"]
UNLOCK_LUA = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"


# --------------------------------------------------------------------------- 三种方案

def reserve_naive(cur, user_id, seat_id, start, end):
    """先查后插：只看预约表，两步之间没有任何保护"""
    cur.execute("SELECT COUNT(*) FROM reservation WHERE seat_id = %s AND reserve_date = %s "
                "AND status IN ('RESERVED', 'IN_USE', 'AWAY') AND start_hour < %s AND end_hour > %s",
                (seat_id, DAY, end, start))
    if cur.fetchone()[0] > 0:
        return "conflict"
    insert_reservation(cur, user_id, seat_id, start, end)
    return "ok"


def reserve_with_index(cur, user_id, seat_id, start, end):
    """与后端 doCreate 相同的语句序列：冲突检查 → 写预约 → 按小时写占用（唯一索引兜底）"""
    cur.execute("SELECT COUNT(*) FROM seat_occupancy WHERE seat_id = %s AND use_date = %s "
                "AND hour_slot >= %s AND hour_slot < %s", (seat_id, DAY, start, end))
    if cur.fetchone()[0] > 0:
        return "conflict"
    cur.execute("SELECT COUNT(*) FROM seat_occupancy WHERE user_id = %s AND use_date = %s "
                "AND hour_slot >= %s AND hour_slot < %s", (user_id, DAY, start, end))
    if cur.fetchone()[0] > 0:
        return "conflict"
    cur.execute("SELECT COALESCE(SUM(end_hour - start_hour), 0) FROM reservation WHERE user_id = %s "
                "AND reserve_date = %s AND status NOT IN ('CANCELLED', 'NO_SHOW')", (user_id, DAY))
    cur.fetchone()
    reservation_id = insert_reservation(cur, user_id, seat_id, start, end)
    rows = [(seat_id, user_id, DAY, h, reservation_id) for h in range(start, end)]
    placeholders = ", ".join(["(%s, %s, %s, %s, %s)"] * len(rows))
    cur.execute("INSERT INTO seat_occupancy (seat_id, user_id, use_date, hour_slot, reservation_id) VALUES " + placeholders,
                [v for row in rows for v in row])
    return "ok"


def insert_reservation(cur, user_id, seat_id, start, end):
    cur.execute("INSERT INTO reservation (user_id, seat_id, room_id, reserve_date, start_hour, end_hour, status, "
                "away_count, created_at, updated_at) VALUES (%s, %s, 1, %s, %s, %s, 'RESERVED', 0, %s, %s)",
                (user_id, seat_id, DAY, start, end, NOW, NOW))
    return cur.lastrowid


def run_transaction(conn, body, *args):
    """执行一个事务；唯一索引冲突与死锁都回滚，并分别计数"""
    try:
        conn.begin()
        with conn.cursor() as cur:
            result = body(cur, *args)
        if result == "ok":
            conn.commit()
        else:
            conn.rollback()
        return result
    except pymysql.err.IntegrityError as e:
        conn.rollback()
        return "conflict" if e.args[0] == 1062 else "error"
    except pymysql.err.OperationalError as e:
        conn.rollback()
        return "deadlock" if e.args[0] in (1213, 1205) else "error"


class RedisLock:
    """与后端 RedisLock 相同的实现：SET NX PX 加锁，自旋等待，Lua 脚本校验 token 后解锁"""

    def __init__(self, client):
        self.client = client
        self.unlock_script = client.register_script(UNLOCK_LUA)

    def try_lock(self, key, ttl_ms=10000, wait_s=3.0):
        token = str(uuid.uuid4())
        deadline = time.monotonic() + wait_s
        while True:
            if self.client.set(key, token, nx=True, px=ttl_ms):
                return token
            if time.monotonic() >= deadline:
                return None
            time.sleep(random.uniform(0.010, 0.030))

    def unlock(self, key, token):
        self.unlock_script(keys=[key], args=[token])


def attempt(strategy, conn, lock, user_id, seat_id, start, end):
    if strategy == "naive":
        return run_transaction(conn, reserve_naive, user_id, seat_id, start, end)
    if strategy == "unique-index":
        return run_transaction(conn, reserve_with_index, user_id, seat_id, start, end)

    # lock+index：锁外预检查 → 用户锁 → 座位锁 → 事务（锁包在事务外面）
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM seat_occupancy WHERE seat_id = %s AND use_date = %s "
                    "AND hour_slot >= %s AND hour_slot < %s", (seat_id, DAY, start, end))
        occupied = cur.fetchone()[0]
    conn.commit()
    if occupied > 0:
        return "conflict"
    user_key = f"lock:reserve:user:{user_id}"
    seat_key = f"lock:reserve:seat:{seat_id}:{DAY}"
    user_token = lock.try_lock(user_key)
    if user_token is None:
        return "busy"
    try:
        seat_token = lock.try_lock(seat_key)
        if seat_token is None:
            return "busy"
        try:
            return run_transaction(conn, reserve_with_index, user_id, seat_id, start, end)
        finally:
            lock.unlock(seat_key, seat_token)
    finally:
        lock.unlock(user_key, user_token)


# --------------------------------------------------------------------------- 场景与统计

def make_requests(scenario, users, rng):
    """返回每个用户的 (seat_id, start, end)"""
    if scenario == "S1":
        return [(1, 9, 12)] * users
    if scenario == "S2":
        out = []
        for _ in range(users):
            length = rng.randint(1, 4)
            start = rng.randint(8, 22 - length)
            out.append((1, start, start + length))
        return out
    return [(i + 1, 9, 12) for i in range(users)]   # S3：每人一个不同的座位


def run_round(strategy, scenario, conns, lock, admin, rng):
    users = len(conns)
    with admin.cursor() as cur:
        cur.execute("TRUNCATE TABLE seat_occupancy")
        cur.execute("TRUNCATE TABLE reservation")
    admin.commit()
    requests = make_requests(scenario, users, rng)
    outcomes = [None] * users
    latencies = [0.0] * users
    barrier = threading.Barrier(users)

    def worker(i):
        seat_id, start, end = requests[i]
        barrier.wait()                      # 所有线程就位后同时开始
        t0 = time.perf_counter()
        outcomes[i] = attempt(strategy, conns[i], lock, i + 1, seat_id, start, end)
        latencies[i] = (time.perf_counter() - t0) * 1000

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(users)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = (time.perf_counter() - t0) * 1000

    # 用数据库里的最终数据判断结果是否正确，而不是相信线程自己的返回值
    with admin.cursor() as cur:
        cur.execute("SELECT seat_id, start_hour, end_hour FROM reservation WHERE status = 'RESERVED'")
        saved = cur.fetchall()
        cur.execute("SELECT COUNT(*) FROM seat_occupancy")
        occupancy_rows = cur.fetchone()[0]
    admin.commit()
    per_slot = {}
    for seat_id, start, end in saved:
        for h in range(start, end):
            per_slot[(seat_id, h)] = per_slot.get((seat_id, h), 0) + 1
    double_booked = sum(c - 1 for c in per_slot.values() if c > 1)
    # 使用占用表的方案，占用行数必须等于成功预约的小时数之和（失败的事务不能留下残余数据）
    orphan = 0 if strategy == "naive" else abs(occupancy_rows - sum(e - s for _, s, e in saved))
    return {
        "success": len(saved),
        "double_booked_hours": double_booked,
        "orphan_rows": orphan,
        "deadlock": outcomes.count("deadlock"),
        "busy": outcomes.count("busy"),
        "error": outcomes.count("error"),
        "elapsed_ms": elapsed,
        "latency_avg_ms": statistics.mean(latencies),
        "latency_p95_ms": sorted(latencies)[int(users * 0.95) - 1],
    }


def summarize(rounds):
    success = [r["success"] for r in rounds]
    return {
        "rounds": len(rounds),
        "success_min": min(success),
        "success_max": max(success),
        "success_avg": round(statistics.mean(success), 2),
        "rounds_with_double_booking": sum(1 for r in rounds if r["double_booked_hours"] > 0),
        "double_booked_hours_total": sum(r["double_booked_hours"] for r in rounds),
        "orphan_rows_total": sum(r["orphan_rows"] for r in rounds),
        "deadlock_total": sum(r["deadlock"] for r in rounds),
        "busy_total": sum(r["busy"] for r in rounds),
        "error_total": sum(r["error"] for r in rounds),
        "elapsed_avg_ms": round(statistics.mean(r["elapsed_ms"] for r in rounds), 1),
        "latency_avg_ms": round(statistics.mean(r["latency_avg_ms"] for r in rounds), 1),
        "latency_p95_ms": round(statistics.mean(r["latency_p95_ms"] for r in rounds), 1),
    }


# --------------------------------------------------------------------------- 准备环境

def prepare_database(args):
    conn = pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password, charset="utf8mb4")
    schema = (ROOT / "backend" / "src" / "main" / "resources" / "schema.sql").read_text(encoding="utf8")
    schema = "\n".join(line for line in schema.splitlines() if not line.strip().startswith("--"))
    with conn.cursor() as cur:
        cur.execute("SELECT VERSION()")
        version = cur.fetchone()[0]
        cur.execute(f"DROP DATABASE IF EXISTS {DB}")
        cur.execute(f"CREATE DATABASE {DB} DEFAULT CHARACTER SET utf8mb4")
        cur.execute(f"USE {DB}")
        for stmt in schema.split(";"):
            if stmt.strip():
                cur.execute(stmt)
        cur.execute("INSERT INTO study_room (id, name, location, open_hour, close_hour, row_count, col_count, enabled, created_at) "
                    "VALUES (1, '实验自习室', '实验楼', 8, 22, 10, 20, 1, %s)", (NOW,))
        for i in range(1, args.users + 1):
            cur.execute("INSERT INTO sys_user (id, username, password, real_name, role, enabled, created_at, updated_at) "
                        "VALUES (%s, %s, 'x', %s, 'STUDENT', 1, %s, %s)", (i, f"user{i}", f"用户{i}", NOW, NOW))
            cur.execute("INSERT INTO seat (id, room_id, seat_no, row_no, col_no, checkin_code) VALUES (%s, 1, %s, %s, %s, %s)",
                        (i, f"S{i:03d}", (i - 1) // 20 + 1, (i - 1) % 20 + 1, f"CODE{i:06d}"))
    conn.commit()
    return conn, version


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--users", type=int, default=100, help="并发用户数（线程数）")
    ap.add_argument("--rounds", type=int, default=20, help="每个场景重复的轮数")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="root")
    ap.add_argument("--redis-host", default="127.0.0.1")
    ap.add_argument("--redis-port", type=int, default=6379)
    ap.add_argument("--seed", type=int, default=20261117)
    ap.add_argument("--out", default=str(ROOT / "experiments" / "results" / "concurrency_results.json"))
    args = ap.parse_args()

    admin, mysql_version = prepare_database(args)
    with admin.cursor() as cur:
        cur.execute("SELECT @@transaction_isolation")
        isolation = cur.fetchone()[0]
    client = redis.Redis(host=args.redis_host, port=args.redis_port, max_connections=args.users + 10)
    redis_version = client.info("server")["redis_version"]
    lock = RedisLock(client)
    conns = [pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password,
                             database=DB, charset="utf8mb4", autocommit=False) for _ in range(args.users)]
    print(f"MySQL {mysql_version}（隔离级别 {isolation}），Redis {redis_version}；"
          f"{args.users} 个并发用户，每个场景 {args.rounds} 轮\n")

    titles = {"S1": "S1 抢同一座位同一时段（正确结果：恰好 1 人成功）",
              "S2": "S2 抢同一座位重叠时段（正确结果：任一小时最多 1 人）",
              "S3": "S3 无冲突（正确结果：全部成功）"}
    results = {}
    for scenario in ["S1", "S2", "S3"]:
        print(titles[scenario])
        print(f"  {'方案':<14}{'成功数(最小/平均/最大)':<24}{'出现超卖的轮数':<12}{'重复占用小时':<10}"
              f"{'死锁':<6}{'等锁超时':<8}{'每轮耗时ms':<10}{'平均响应ms':<10}{'P95 ms':<8}")
        results[scenario] = {}
        for strategy in STRATEGIES:
            rng = random.Random(args.seed)      # 三种方案使用完全相同的请求序列
            rounds = []
            for _ in range(args.rounds):
                for key in client.scan_iter("lock:reserve:*"):
                    client.delete(key)
                rounds.append(run_round(strategy, scenario, conns, lock, admin, rng))
            s = summarize(rounds)
            results[scenario][strategy] = s
            print(f"  {strategy:<16}{str(s['success_min']) + ' / ' + str(s['success_avg']) + ' / ' + str(s['success_max']):<30}"
                  f"{str(s['rounds_with_double_booking']) + '/' + str(s['rounds']):<18}{s['double_booked_hours_total']:<16}"
                  f"{s['deadlock_total']:<8}{s['busy_total']:<12}{s['elapsed_avg_ms']:<14}{s['latency_avg_ms']:<14}{s['latency_p95_ms']:<8}")
            if s["orphan_rows_total"] or s["error_total"]:
                print(f"    ！残余数据 {s['orphan_rows_total']} 行，其他错误 {s['error_total']} 次")
        print()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "environment": {"mysql": mysql_version, "isolation": isolation, "redis": redis_version,
                        "users": args.users, "rounds": args.rounds, "seed": args.seed,
                        "run_at": dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
        "results": results}, ensure_ascii=False, indent=2), encoding="utf8")
    print("结果已写入", out)
    for c in conns:
        c.close()
    admin.close()


if __name__ == "__main__":
    main()
