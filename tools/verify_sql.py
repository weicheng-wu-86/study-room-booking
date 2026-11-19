#!/usr/bin/env python3
"""
SQL 校验脚本：在真实的 MySQL 上验证后端的数据库层。

检查三件事：
  1. schema.sql 能否完整执行（按 Spring 的方式以分号切分语句）；
  2. 每个实体类的字段是否都能在对应的表中找到同名（下划线形式）的列；
  3. Mapper 接口中手写的每一条 SQL（@Select/@Insert/@Update/@Delete）能否执行，
     以及查询返回的列是否都能映射到返回类型的字段上。

用法：
    pip install pymysql
    python tools/verify_sql.py [--host 127.0.0.1 --port 3306 --user root --password root]

脚本使用独立的数据库 studyroom_verify，每次运行都会重建，不影响正式数据。
"""
import argparse
import datetime as dt
import re
import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend" / "src" / "main"
JAVA = BACKEND / "java" / "com" / "studyroom"
DB = "studyroom_verify"

failures = []


def check(ok, label, detail=""):
    print(f"  [{'OK' if ok else 'FAIL'}] {label}{(' — ' + detail) if detail else ''}")
    if not ok:
        failures.append(label)


def snake(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def camel(name):
    parts = name.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def class_fields(source, class_name=None):
    """提取类中的 private 字段名；class_name 用于定位嵌套静态类"""
    if class_name:
        m = re.search(r"class\s+" + class_name + r"\s*\{(.*?)\n    \}", source, re.S)
        source = m.group(1) if m else ""
    return set(re.findall(r"private\s+(?!static)[\w<>.,\s]+?\s+(\w+)\s*;", source))


# ---------------------------------------------------------------- 1. 建表
def run_schema(conn):
    print("1. 执行 schema.sql")
    text = (BACKEND / "resources" / "schema.sql").read_text(encoding="utf8")
    text = "\n".join(line for line in text.splitlines() if not line.strip().startswith("--"))
    statements = [s.strip() for s in text.split(";") if s.strip()]
    with conn.cursor() as cur:
        for stmt in statements:
            cur.execute(stmt)
        cur.execute("SHOW TABLES")
        tables = sorted(r[0] for r in cur.fetchall())
    check(len(tables) == 7, f"建表 {len(statements)} 条语句全部执行成功", "表：" + ", ".join(tables))
    # 再执行一遍，验证脚本可重复运行（IF NOT EXISTS）
    with conn.cursor() as cur:
        for stmt in statements:
            cur.execute(stmt)
    check(True, "重复执行不报错")


# ---------------------------------------------------------------- 2. 实体与表结构
def check_entities(conn):
    print("2. 实体字段与表字段对照")
    for path in sorted((JAVA / "entity").glob("*.java")):
        src = path.read_text(encoding="utf8")
        table = re.search(r'@TableName\("(\w+)"\)', src).group(1)
        with conn.cursor() as cur:
            cur.execute("SELECT column_name FROM information_schema.columns "
                        "WHERE table_schema = %s AND table_name = %s", (DB, table))
            columns = {r[0].lower() for r in cur.fetchall()}
        fields = {snake(f) for f in class_fields(src)}
        check(fields == columns, f"{path.stem} ↔ {table}（{len(fields)} 个字段）",
              "" if fields == columns else f"实体多出 {fields - columns}，表多出 {columns - fields}")


# ---------------------------------------------------------------- 3. Mapper SQL
def seed(conn):
    """写入少量数据，让查询有结果可返回"""
    now = dt.datetime(2026, 11, 20, 9, 0, 0)
    day = now.date()
    with conn.cursor() as cur:
        for i, (name, role) in enumerate([("admin", "ADMIN"), ("stu01", "STUDENT"), ("stu02", "STUDENT")], 1):
            cur.execute("INSERT INTO sys_user (id, username, password, real_name, student_no, role, enabled, created_at, updated_at) "
                        "VALUES (%s, %s, 'x', %s, %s, %s, 1, %s, %s)", (i, name, "用户" + name, "2023000" + str(i), role, now, now))
        cur.execute("INSERT INTO study_room (id, name, location, open_hour, close_hour, row_count, col_count, enabled, created_at) "
                    "VALUES (1, '一号自习室', '图书馆', 8, 22, 1, 2, 1, %s)", (now,))
        cur.execute("INSERT INTO seat (id, room_id, seat_no, row_no, col_no, checkin_code) VALUES (1, 1, 'A01', 1, 1, 'CODE000001'), (2, 1, 'A02', 1, 2, 'CODE000002')")
        rows = [(1, 2, 1, day, 9, 11, "IN_USE"), (2, 3, 2, day, 9, 10, "NO_SHOW"), (3, 3, 2, day, 14, 16, "RESERVED"),
                (4, 2, 1, day - dt.timedelta(days=1), 9, 12, "COMPLETED"), (5, 2, 2, day - dt.timedelta(days=1), 13, 14, "CANCELLED")]
        for rid, uid, sid, d, s, e, status in rows:
            cur.execute("INSERT INTO reservation (id, user_id, seat_id, room_id, reserve_date, start_hour, end_hour, status, away_count, created_at, updated_at) "
                        "VALUES (%s, %s, %s, 1, %s, %s, %s, %s, 0, %s, %s)", (rid, uid, sid, d, s, e, status, now, now))
        cur.execute("INSERT INTO seat_occupancy (seat_id, user_id, use_date, hour_slot, reservation_id) VALUES "
                    "(1, 2, %s, 9, 1), (1, 2, %s, 10, 1), (2, 3, %s, 14, 3), (2, 3, %s, 15, 3)", (day, day, day, day))
        cur.execute("INSERT INTO violation (user_id, reservation_id, type, remark, created_at) VALUES (3, 2, 'NO_SHOW', '超时未签到', %s)", (now,))
    conn.commit()
    return day


def resolve_expression(expr, constants):
    """把注解里的 Java 字符串表达式（文本块、字符串字面量、常量名用 + 拼接）还原成 SQL"""
    out = []
    for kind_text, kind_str, ident in re.findall(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"|([A-Z_][A-Z0-9_]*)', expr, re.S):
        if kind_text:
            out.append(kind_text)
        elif ident:
            out.append(constants[ident])
        else:
            out.append(kind_str)
    return "".join(out)


def expand_script(sql):
    """把 <script> 里的 <foreach> 展开成两行数据，用于执行验证"""
    sql = sql.replace("<script>", "").replace("</script>", "")
    m = re.search(r"<foreach[^>]*>(.*?)</foreach>", sql, re.S)
    if m:
        body = m.group(1).strip()
        first = re.sub(r"#\{o\.(\w+)\}", r"#{o0_\1}", body)
        second = re.sub(r"#\{o\.(\w+)\}", r"#{o1_\1}", body)
        sql = sql[:m.start()] + first + ", " + second + sql[m.end():]
    return sql


def check_mappers(conn, day):
    print("3. Mapper 手写 SQL")
    vo_sources = {p.stem: p.read_text(encoding="utf8") for p in (JAVA / "vo").glob("*.java")}
    vo_sources.update({p.stem: p.read_text(encoding="utf8") for p in (JAVA / "entity").glob("*.java")})
    # 每个参数给两组值：全为空（测“条件可选”的分支）和全有值
    base = {"userId": 2, "seatId": 1, "roomId": 1, "id": 1, "reservationId": 99, "hour": 9, "date": day,
            "start": 9, "end": 12, "from": day - dt.timedelta(days=6), "to": day,
            "o0_seatId": 2, "o0_userId": 2, "o0_useDate": day, "o0_hourSlot": 20, "o0_reservationId": 99,
            "o1_seatId": 2, "o1_userId": 2, "o1_useDate": day, "o1_hourSlot": 21, "o1_reservationId": 99}
    variants = [dict(base, status=None, keyword=None), dict(base, status="RESERVED", keyword="stu")]
    nullable = {"selectPageVO": ["userId"], "selectAdminPage": ["date", "roomId"]}

    total = 0
    for path in sorted((JAVA / "mapper").glob("*.java")):
        src = path.read_text(encoding="utf8")
        constants = {m.group(1): m.group(2) for m in re.finditer(r'String\s+([A-Z_]+)\s*=\s*"""(.*?)"""\s*;', src, re.S)}
        # 注解参数 = 若干个“文本块 / 字符串字面量 / 常量名”用 + 连接；这样写才不会被 SQL 里的括号干扰
        piece = r'"""(?:.*?)"""|"(?:[^"\\]|\\.)*"|[A-Z_][A-Z0-9_]*|\+'
        pattern = (r"@(Select|Insert|Update|Delete)\(((?:\s*(?:" + piece + r"))+)\s*\)"
                   r"\s*\n\s*([\w<>.,\s]+?)\s+(\w+)\(")
        for kind, expr, ret, method in re.findall(pattern, src, re.S):
            sql = resolve_expression(expr, constants).strip()
            if sql.startswith("<script>"):
                sql = expand_script(sql)
            names = re.findall(r"#\{([\w.]+)\}", sql)
            sql_py = re.sub(r"#\{([\w.]+)\}", r"%(\1)s", sql.replace("%", "%%"))
            is_page = "IPage" in ret
            for vi, params in enumerate(variants):
                params = dict(params)
                if vi == 0:
                    for n in nullable.get(method, []):
                        params[n] = None
                try:
                    with conn.cursor() as cur:
                        if is_page:
                            # 分页插件会生成 COUNT 语句并追加 LIMIT，这里分别验证
                            cur.execute(f"SELECT COUNT(*) FROM ({sql_py}) t", params)
                            cur.execute(sql_py + " LIMIT 10", params)
                        else:
                            cur.execute(sql_py, params)
                        cols = [d[0] for d in cur.description] if cur.description else []
                        n_rows = cur.rowcount
                    conn.rollback()   # 写操作不保留
                    detail = f"返回 {n_rows} 行" if kind == "Select" else f"影响 {n_rows} 行"
                    ok = True
                    # 查询列 → 返回类型字段 的映射检查
                    inner = re.findall(r"[\w.]+", ret)[-1]
                    if kind == "Select" and inner not in ("int", "long", "Integer", "Long"):
                        outer, _, nested = inner.partition(".")
                        fields = class_fields(vo_sources.get(outer, ""), nested or None)
                        missing = [c for c in cols if camel(c.lower()) not in fields and c not in fields]
                        if missing:
                            ok, detail = False, f"列 {missing} 在 {inner} 中没有对应字段"
                    optional = any(n in ("status", "keyword") or n in nullable.get(method, []) for n in names)
                    variant = ("（可选条件为空）" if vi == 0 else "（可选条件有值）") if optional else ""
                    if vi == 0 or optional:
                        check(ok, f"{path.stem}.{method}{variant}", detail)
                        total += 1
                except Exception as e:   # noqa: BLE001
                    conn.rollback()
                    check(False, f"{path.stem}.{method}", str(e))
                    total += 1
    print(f"  共执行 {total} 次")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=3306)
    ap.add_argument("--user", default="root")
    ap.add_argument("--password", default="root")
    args = ap.parse_args()

    conn = pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password, charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("SELECT VERSION()")
        print("MySQL 版本：", cur.fetchone()[0])
        cur.execute(f"DROP DATABASE IF EXISTS {DB}")
        cur.execute(f"CREATE DATABASE {DB} DEFAULT CHARACTER SET utf8mb4")
        cur.execute(f"USE {DB}")
    run_schema(conn)
    check_entities(conn)
    day = seed(conn)
    check_mappers(conn, day)
    conn.close()

    print()
    if failures:
        print(f"校验未通过：{len(failures)} 项失败")
        sys.exit(1)
    print("全部校验通过")


if __name__ == "__main__":
    main()
