#!/usr/bin/env python3
"""
接口冒烟测试：对一个已经启动、并写入了演示数据的后端，通过 HTTP 走一遍主要业务流程。

与 JUnit 集成测试互补：JUnit 直接调用业务层，这里覆盖控制层、拦截器、JSON 序列化、
全局异常处理和演示数据初始化，验证的是“系统作为一个整体能否正常对外服务”。

用法：
    python tools/api_smoke.py [--base http://localhost:8080/api]
只依赖 Python 标准库。任何一项不符合预期都会以非零状态退出。
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

failures = []


def call(base, method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except ValueError:
            return e.code, {}


def check(ok, label, detail=""):
    print(f"  [{'OK' if ok else 'FAIL'}] {label}{(' — ' + str(detail)) if detail else ''}")
    if not ok:
        failures.append(label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080/api")
    base = ap.parse_args().base

    # ---------- 认证与权限 ----------
    status, _ = call(base, "GET", "/rooms")
    check(status == 401, "未携带令牌访问接口返回 401", status)
    status, res = call(base, "POST", "/auth/login", body={"username": "admin", "password": "wrong"})
    check(status == 400 and res.get("message") == "账号或密码错误", "错误密码返回 400 与统一提示", status)
    status, res = call(base, "POST", "/auth/login", body={"username": "", "password": ""})
    check(status == 400, "空账号被参数校验拦截", res.get("message"))

    status, res = call(base, "POST", "/auth/login", body={"username": "admin", "password": "admin123"})
    check(status == 200 and res["data"]["user"]["role"] == "ADMIN", "演示管理员登录成功", status)
    admin = res["data"]["token"]
    status, res = call(base, "POST", "/auth/register", body={
        "username": "smoke_user", "password": "secret123", "realName": "冒烟测试", "studentNo": "", "phone": ""})
    check(status == 200, "注册新学生", res.get("message"))
    status, res = call(base, "POST", "/auth/register", body={
        "username": "smoke_user", "password": "secret123", "realName": "冒烟测试"})
    check(status == 409, "重复注册返回 409", status)
    status, res = call(base, "POST", "/auth/login", body={"username": "smoke_user", "password": "secret123"})
    student = res["data"]["token"]
    check(status == 200 and "password" not in res["data"]["user"], "学生登录成功，响应中不含密码", status)
    status, _ = call(base, "GET", "/admin/users", student)
    check(status == 403, "学生访问管理端接口返回 403", status)

    # ---------- 演示数据与查询 ----------
    status, res = call(base, "GET", "/rules", student)
    rules = res["data"]
    today = rules["serverTime"][:10]
    hour = int(rules["serverTime"][11:13])
    check(status == 200 and rules["maxHoursPerReservation"] == 4, "读取预约规则与服务器时间", rules["serverTime"])
    status, res = call(base, "GET", "/rooms", student)
    rooms = res["data"]
    check(status == 200 and len(rooms) == 3, "演示数据：3 间自习室", [r["name"] for r in rooms])
    status, res = call(base, "GET", "/notices", student)
    check(status == 200 and len(res["data"]) == 3 and res["data"][0]["pinned"], "演示数据：3 条公告，置顶在前", status)
    status, res = call(base, "GET", "/admin/users?size=1", admin)
    check(status == 200 and res["data"]["total"] == 122, "演示数据：120 名学生 + 管理员 + 新注册学生", res["data"]["total"])

    # 选一个明天在 9-11 点空闲的座位（用明天，避免测试结果受当前时刻影响）
    from datetime import date, timedelta
    tomorrow = (date.fromisoformat(today) + timedelta(days=1)).isoformat()
    room = rooms[0]
    status, res = call(base, "GET", f"/rooms/{room['id']}/seats?date={tomorrow}", student)
    seats = res["data"]["seats"]
    check(status == 200 and len(seats) == room["rowCount"] * room["colCount"], "座位图返回全部位置", len(seats))
    check(all("checkinCode" not in s for s in seats), "学生端座位图不暴露签到码")
    free = [s for s in seats if s["enabled"] and not set(s["occupiedHours"]) & {9, 10}]
    seat = free[0]

    # ---------- 预约流程 ----------
    status, res = call(base, "POST", "/reservations", student,
                       {"seatId": seat["id"], "date": tomorrow, "startHour": 9, "endHour": 11})
    check(status == 200 and res["data"]["status"] == "RESERVED", "创建预约", res.get("message"))
    rid = res["data"]["id"]
    check(res["data"]["checkinDeadline"] == f"{tomorrow}T09:15:00", "返回签到截止时间", res["data"]["checkinDeadline"])

    call(base, "POST", "/auth/register", body={"username": "smoke_other", "password": "secret123", "realName": "另一位"})
    _, res = call(base, "POST", "/auth/login", body={"username": "smoke_other", "password": "secret123"})
    other = res["data"]["token"]
    status, res = call(base, "POST", "/reservations", other,
                       {"seatId": seat["id"], "date": tomorrow, "startHour": 10, "endHour": 12})
    check(status == 409, "他人预约交叠时段返回 409", res.get("message"))
    status, res = call(base, "POST", "/reservations", student,
                       {"seatId": seat["id"], "date": tomorrow, "startHour": 12, "endHour": 17})
    check(status == 400, "单次超过 4 小时返回 400", res.get("message"))
    status, res = call(base, "POST", "/reservations", student,
                       {"seatId": seat["id"], "date": tomorrow, "startHour": "abc", "endHour": 11})
    check(status == 400, "请求体格式错误返回 400", res.get("message"))

    status, res = call(base, "GET", f"/rooms/{room['id']}/seats?date={tomorrow}", student)
    mine = next(s for s in res["data"]["seats"] if s["id"] == seat["id"])
    check(mine["myHours"] == [9, 10], "座位图标出自己的预约时段", mine["myHours"])
    status, res = call(base, "POST", f"/reservations/{rid}/cancel", other)
    check(status == 404, "不能取消他人的预约", status)
    status, res = call(base, "GET", "/reservations/active", student)
    check(status == 200 and [r["id"] for r in res["data"]] == [rid], "当前有效预约", len(res["data"]))
    status, res = call(base, "POST", f"/reservations/{rid}/cancel", student)
    check(status == 200, "取消预约", res.get("message"))
    status, res = call(base, "POST", "/reservations", other,
                       {"seatId": seat["id"], "date": tomorrow, "startHour": 10, "endHour": 12})
    check(status == 200, "取消后座位可被他人预约", res.get("message"))

    # 当天签到：只在自习室开放、且当前小时还能预约时执行
    if room["openHour"] <= hour < room["closeHour"] - 1:
        status, res = call(base, "GET", f"/rooms/{room['id']}/seats?date={today}", student)
        free_now = [s for s in res["data"]["seats"] if s["enabled"] and hour not in s["occupiedHours"]]
        target = free_now[0]
        status, res = call(base, "POST", "/reservations", student,
                           {"seatId": target["id"], "date": today, "startHour": hour, "endHour": hour + 1})
        check(status == 200, "预约当前这个小时", res.get("message"))
        rid2 = res["data"]["id"]
        _, res = call(base, "GET", f"/admin/rooms/{room['id']}/seats", admin)
        code = next(s["checkinCode"] for s in res["data"] if s["id"] == target["id"])
        status, res = call(base, "POST", "/reservations/checkin", student, {"code": "WRONGCODE1"})
        check(status == 400, "错误签到码返回 400", res.get("message"))
        status, res = call(base, "POST", "/reservations/checkin", student, {"code": code})
        check(status == 200 and res["data"]["status"] == "IN_USE", "扫码签到", res.get("message"))
        status, res = call(base, "POST", f"/reservations/{rid2}/away", student)
        check(status == 200 and res["data"]["status"] == "AWAY", "暂离", res.get("message"))
        status, res = call(base, "POST", f"/reservations/{rid2}/back", student)
        check(status == 200 and res["data"]["status"] == "IN_USE", "暂离返回", res.get("message"))
        status, res = call(base, "POST", f"/reservations/{rid2}/checkout", student)
        check(status == 200 and res["data"]["status"] == "COMPLETED", "签退", res.get("message"))
    else:
        print("  [SKIP] 当前不在开放时间内，跳过当天签到流程")

    status, res = call(base, "GET", "/reservations/mine?status=CANCELLED", student)
    check(status == 200 and res["data"]["total"] == 1, "按状态分页查询我的预约", res["data"]["total"])
    status, res = call(base, "GET", "/reservations/mine?status=NOPE", student)
    check(status == 400, "未知状态参数返回 400", res.get("message"))

    # ---------- 管理端 ----------
    status, res = call(base, "GET", "/admin/stats/overview", admin)
    check(status == 200 and res["data"]["seatCount"] > 0, "统计概览", res["data"])
    status, res = call(base, "GET", "/admin/stats/trend?days=7", admin)
    check(status == 200 and len(res["data"]) == 7 and sum(p["total"] for p in res["data"]) > 0, "每日趋势（7 天）",
          [p["total"] for p in res["data"]])
    status, res = call(base, "GET", "/admin/stats/hourly?days=7", admin)
    check(status == 200 and max(p["avgSeats"] for p in res["data"]) > 0, "各时段热度", len(res["data"]))
    status, res = call(base, "GET", "/admin/stats/rooms?days=7", admin)
    check(status == 200 and all(0 < r["rate"] < 1 for r in res["data"]), "各自习室使用率",
          [r["rate"] for r in res["data"]])
    status, res = call(base, "GET", f"/admin/reservations?date={tomorrow}&keyword=smoke", admin)
    check(status == 200 and res["data"]["total"] == 2, "管理端按日期与关键字查询预约", res["data"]["total"])
    status, res = call(base, "GET", "/admin/violations?size=5", admin)
    check(status == 200 and res["data"]["total"] > 0, "演示数据中的违约记录", res["data"]["total"])
    status, res = call(base, "POST", "/admin/rooms", admin, {
        "name": "冒烟测试自习室", "location": "测试楼", "description": "", "openHour": 8, "closeHour": 22,
        "rowCount": 2, "colCount": 3})
    check(status == 200, "新建自习室", res.get("message"))
    new_room = res["data"]["id"]
    _, res = call(base, "GET", f"/admin/rooms/{new_room}/seats", admin)
    check(len(res["data"]) == 6 and res["data"][0]["seatNo"] == "A01", "按行列自动生成座位", len(res["data"]))
    status, res = call(base, "DELETE", f"/admin/rooms/{new_room}", admin)
    check(status == 200, "删除没有预约记录的自习室", res.get("message"))
    status, res = call(base, "DELETE", f"/admin/rooms/{room['id']}", admin)
    check(status == 400, "有预约记录的自习室不能删除", res.get("message"))
    status, res = call(base, "GET", "/admin/does-not-exist", admin)
    check(status == 404, "不存在的接口返回 404 且格式统一", res)

    print()
    if failures:
        print(f"冒烟测试未通过：{len(failures)} 项失败")
        sys.exit(1)
    print("冒烟测试全部通过")


if __name__ == "__main__":
    main()
