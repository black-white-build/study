#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HeartPilot 在线压测脚本（完整规范版）
=====================================
目标：对 http://82.157.205.6:8081 上部署的 HeartPilot（心旅）后端做黑盒压测，
覆盖四类场景：

  1) 登录态持续负载（sustained）
     多测试用户分布式并发，尊重系统"每用户 120 req/min、认证接口每 IP 20 req/min"
     的 Redis 分布式限流设计（/health 免限流），统计：
     QPS / P50 / P95 / P99 / 最大时延 / 平均时延 / 错误率 / 状态码分布。
  2) 单用户突发超限验证（burst）
     单用户高频请求，验证超过配额后由限流过滤器返回 429（RATE_LIMITED）。
  3) SSE 流式对话首字延迟（TTFT）
     POST /conversations/{id}/messages/stream，统计从发起请求到收到首个
     delta 事件的耗时（对应服务端 Prometheus 指标 heartpilot.chat.time_to_first_token）。
  4) 容量测试·多用户等效解除限流（capacity）
     在不修改线上限流配置的前提下，注册一批 rl_ 前缀临时测试用户，
     每个用户以 119 req/min（配额内安全阈值）打满自己的限流窗口，
     只压只读接口（GET /conversations、GET /agent-tasks，纯 SELECT 零业务写入），
     聚合总吞吐逼近 N 用户 × 2 QPS 的配额聚合上限，从而等效测量"解除限流"
     后系统在配额模型内可支撑的业务容量；429 出现即说明配额边界被触及。
     注意：注册会短暂写入 app_user 表（可控污染），压测结束后需按输出的
     清理 SQL 删除 rl_ 前缀测试用户，方可恢复数据库原状。

运行方式（Python 3.8+，仅标准库，零第三方依赖）：
    python heartpilot_stress_test.py \
        --base-url http://82.157.205.6:8081 \
        --users 8 --duration 60 --ramp 15
    # 容量测试（只读接口、等效解除限流）：
    python heartpilot_stress_test.py \
        --capacity-only --capacity-users 40 --capacity-duration 60 --capacity-ramp 15

输出：控制台汇总表 + results/ 目录下 JSON 明细与 CSV 原始记录。
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------- 常量与全局

DEFAULT_BASE_URL = "http://82.157.205.6:8081"
API_PREFIX = "/api"

# 服务器限流设计（源码 SecurityConfig / RateLimitFilter）
AUTH_LIMIT_PER_MIN = 20      # 认证接口每 IP 每分钟
DEFAULT_LIMIT_PER_MIN = 120  # 普通接口每用户每分钟

# 压测阶段默认参数
DEFAULT_USERS = 8            # 测试用户数（受 20 req/min/IP 注册限流约束，不宜过大）
DEFAULT_DURATION = 60        # 持续负载时长（秒）
DEFAULT_RAMP = 15            # 爬坡时长（秒）：线程逐渐加入
DEFAULT_TTFT_SAMPLES = 10    # SSE 首字延迟样本数
DEFAULT_CAPACITY_USERS = 40  # 容量测试用户数（注册受 20 req/min/IP 限流，40 个约需 2.2 分钟）
DEFAULT_CAPACITY_DURATION = 60  # 容量测试稳态时长（秒）

RESULTS_DIR = Path(__file__).resolve().parent / "results"

# 容量测试临时用户前缀（压测结束后按清理 SQL 删除）
CAPACITY_USER_PREFIX = "rl"
CAPACITY_CLEANUP_SQL = (
    "DELETE FROM app_user WHERE username LIKE 'rl\\_%' ESCAPE '\\\\';"
)


# ---------------------------------------------------------------- 工具函数

def now_ms() -> float:
    """当前时间戳（毫秒）。"""
    return time.time() * 1000.0


def percentile(values: list[float], p: float) -> float:
    """计算百分位时延；空列表返回 0。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(math.ceil(p / 100.0 * len(ordered))) - 1))
    return ordered[idx]


def summarize_latency(values: list[float]) -> dict:
    """时延汇总：数量、均值、P50/P90/P95/P99、最大。"""
    if not values:
        return {"samples": 0, "mean_ms": 0.0, "p50_ms": 0.0, "p90_ms": 0.0,
                "p95_ms": 0.0, "p99_ms": 0.0, "max_ms": 0.0}
    return {
        "samples": len(values),
        "mean_ms": round(statistics.fmean(values), 2),
        "p50_ms": round(percentile(values, 50), 2),
        "p90_ms": round(percentile(values, 90), 2),
        "p95_ms": round(percentile(values, 95), 2),
        "p99_ms": round(percentile(values, 99), 2),
        "max_ms": round(max(values), 2),
    }


def http_request(method: str, url: str, token: str | None = None,
                 body: dict | None = None, timeout: float = 30.0,
                 stream: bool = False,
                 return_headers: bool = False) -> tuple[int, bytes]:
    """发起一次 HTTP 请求；返回 (状态码, 响应体字节)；return_headers=True 时返回 (状态码, 响应体, 响应头)。"""
    headers = {
        "User-Agent": "HeartPilotStress/1.0",
        "Accept": "text/event-stream" if stream else "application/json",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if return_headers:
                return resp.status, resp.read(), dict(resp.headers)
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        if return_headers:
            return exc.code, exc.read(), dict(exc.headers)
        return exc.code, exc.read()
    except Exception as exc:  # 网络级异常记作 0
        if return_headers:
            return 0, str(exc).encode("utf-8"), {}
        return 0, str(exc).encode("utf-8")


def register_user(base: str, username: str) -> str:
    """注册一个测试用户并返回 JWT；认证接口按 IP 限流，调用方须自行限速。
    准备阶段增加退避重试：连接超时/瞬时 5xx 时最多重试 3 次，避免一次抖动终止整轮压测。"""
    payload = {"username": username, "password": "Stress#Test#2026", "nickname": username}
    last_code, last_body = 0, b""
    for attempt in range(1, 4):
        code, body = http_request("POST", base + API_PREFIX + "/auth/register", body=payload)
        if code == 200:
            data = json.loads(body.decode("utf-8"))
            token = data.get("accessToken") or data.get("token")
            if not token:
                raise RuntimeError(f"注册响应缺少令牌: {body[:300].decode('utf-8', 'replace')}")
            return token
        last_code, last_body = code, body
        if code == 429:
            # 认证接口 20 req/min/IP：触顶时等待窗口再试（按 3.5s 退避）
            time.sleep(3.5)
            continue
        if code == 0 or 500 <= code < 600:
            # 连接层超时或服务端瞬时错误：退避重试
            time.sleep(4 * attempt)
            continue
        break  # 4xx（409 重名等）不重试
    raise RuntimeError(
        f"注册失败 {username}: HTTP {last_code} "
        f"{last_body[:200].decode('utf-8', 'replace')}")


# ---------------------------------------------------------------- 记录结构

@dataclass
class RequestRecord:
    """单次请求记录。"""
    endpoint: str
    user: int
    status: int
    latency_ms: float
    ok: bool
    detail: str = ""


@dataclass
class Scenario:
    """单个压测场景的汇总结果。"""
    name: str
    records: list[RequestRecord] = field(default_factory=list)
    start_wall: float = 0.0
    end_wall: float = 0.0

    def summary(self) -> dict:
        lat = [r.latency_ms for r in self.records]
        completed = [r.latency_ms for r in self.records if r.status != 0]  # 请求确实到达服务器
        ok = [r for r in self.records if r.ok]
        elapsed = max(0.0001, (self.end_wall - self.start_wall) / 1000.0)
        by_status: dict[int, int] = {}
        for r in self.records:
            by_status[r.status] = by_status.get(r.status, 0) + 1
        summary = {
            "scenario": self.name,
            "requests": len(self.records),
            "success": len(ok),
            "error": len(self.records) - len(ok),
            "error_rate": round((len(self.records) - len(ok)) / max(1, len(self.records)), 4),
            "qps": round(len(self.records) / elapsed, 2),
            "elapsed_sec": round(elapsed, 2),
            "status_codes": {str(k): v for k, v in sorted(by_status.items())},
            "latency_all": summarize_latency(lat),
            "latency_completed": summarize_latency(completed),  # 排除连接层失败后的时延
        }
        return summary


# ---------------------------------------------------------------- 场景实现

def scenario_sustained(base: str, tokens: list[str], conversation_ids: list[int],
                       duration: int, ramp: int) -> Scenario:
    """登录态持续负载：每用户一个工作线程，按目标速率轮询业务接口。"""
    sc = Scenario(name="sustained_login_load")
    sc.start_wall = now_ms()

    # 每用户目标速率：略低于 120 req/min 配额，避免人为触发限流而污染时延
    per_user_interval = 60.0 / (DEFAULT_LIMIT_PER_MIN * 0.75)  # ≈0.67s/次
    endpoints = [
        lambda cid: ("GET", "/conversations", None),                          # 读：会话列表
        lambda cid: ("GET", "/agent-tasks", None),                            # 读：任务列表
        lambda cid: ("GET", f"/conversations/{cid}/messages", None),          # 读：历史消息
    ]
    stop = threading.Event()

    def worker(user_idx: int, token: str, cid: int) -> None:
        # 爬坡：按用户序号错峰启动
        if ramp > 0:
            time.sleep(ramp * user_idx / max(1, len(tokens) - 1))
        next_slot = time.time()
        idx = 0
        while not stop.is_set():
            method, path, body = endpoints[idx % len(endpoints)](cid)
            idx += 1
            start = now_ms()
            status, _ = http_request(method, base + API_PREFIX + path, token=token, body=body)
            sc.records.append(RequestRecord(
                endpoint=path, user=user_idx, status=status,
                latency_ms=now_ms() - start, ok=status == 200,
            ))
            # 节流：保持每用户目标速率
            next_slot += per_user_interval
            drift = next_slot - time.time()
            if drift > 0:
                stop.wait(drift)

    with ThreadPoolExecutor(max_workers=len(tokens)) as pool:
        futures = [
            pool.submit(worker, i, t, conversation_ids[i % len(conversation_ids)])
            for i, t in enumerate(tokens)
        ]
        stop.wait(duration)
        stop.set()
        for f in futures:
            f.result(timeout=max(30, duration))

    sc.end_wall = now_ms()
    return sc


def scenario_burst(base: str, token: str, total: int = 150) -> Scenario:
    """单用户突发超限验证：快速连发请求，验证 429 限流生效。"""
    sc = Scenario(name="single_user_burst_rate_limit")
    sc.start_wall = now_ms()
    path = "/conversations"

    def one(_: int) -> RequestRecord:
        start = now_ms()
        status, _ = http_request("GET", base + API_PREFIX + path, token=token)
        return RequestRecord(endpoint=path, user=0, status=status,
                             latency_ms=now_ms() - start, ok=status == 200)

    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = [pool.submit(one, i) for i in range(total)]
        for f in as_completed(futures):
            sc.records.append(f.result())
    sc.end_wall = now_ms()
    return sc


def scenario_sse_ttft(base: str, token: str, conversation_id: int,
                      samples: int, questions: list[str]) -> Scenario:
    """SSE 流式对话首字延迟：记录到首个 delta 事件的时间。"""
    sc = Scenario(name="sse_stream_ttft")
    sc.start_wall = now_ms()
    url = base + API_PREFIX + f"/conversations/{conversation_id}/messages/stream"
    headers = {
        "User-Agent": "HeartPilotStress/1.0",
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }

    for i in range(samples):
        question = questions[i % len(questions)]
        body = json.dumps({"content": question}, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=body, headers=headers, method="POST")
        start = now_ms()
        ttft: float | None = None
        total_ms: float = 0.0
        received_bytes = 0
        status_code = 0
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                status_code = resp.status
                buffer = ""
                for raw in resp:
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", "replace")
                    buffer += raw
                    received_bytes += len(raw)
                    if ttft is None and ("event: delta" in buffer or '"delta"' in buffer
                                         or "data:" in buffer):
                        ttft = now_ms() - start
                        buffer = ""  # 记录到首 token 后不再关心内容细节
            total_ms = now_ms() - start
        except Exception as exc:
            status_code = 0
            total_ms = now_ms() - start
            sc.records.append(RequestRecord(
                endpoint="messages/stream", user=i, status=status_code,
                latency_ms=total_ms, ok=False, detail=str(exc)[:200]))
            continue

        ok = status_code == 200 and ttft is not None
        sc.records.append(RequestRecord(
            endpoint="messages/stream", user=i, status=status_code,
            latency_ms=ttft if ttft is not None else total_ms, ok=ok,
            detail=f"ttft_ms={ttft:.0f}" if ttft else f"no_delta bytes={received_bytes}",
        ))

    sc.end_wall = now_ms()
    return sc


def scenario_capacity_no_limit(base: str, tokens: list[str],
                               duration: int, ramp: int) -> Scenario:
    """容量测试·多用户等效解除限流。

    每个测试用户一个工作线程，以 119 req/min（配额内安全阈值，60/119≈0.504s/次）
    稳定打满自己的限流窗口；只压只读接口（GET /conversations、GET /agent-tasks，
    均为分页 SELECT，零业务写入），聚合总吞吐逼近 N×2 QPS 的配额聚合上限。
    """
    sc = Scenario(name="capacity_no_limit_readonly")
    sc.start_wall = now_ms()

    per_user_interval = 60.0 / (DEFAULT_LIMIT_PER_MIN - 1)  # ≈0.504s/次，≤120/min 不触发 429
    endpoints = [
        lambda: ("GET", "/conversations", None),   # 读：会话列表（分页 SELECT）
        lambda: ("GET", "/agent-tasks", None),     # 读：Agent 任务列表（分页 SELECT）
    ]
    stop = threading.Event()

    def worker(user_idx: int, token: str) -> None:
        if ramp > 0:
            time.sleep(ramp * user_idx / max(1, len(tokens) - 1))
        next_slot = time.time()
        idx = 0
        while not stop.is_set():
            method, path, body = endpoints[idx % len(endpoints)]()
            idx += 1
            start = now_ms()
            status, _, headers = http_request(method, base + API_PREFIX + path,
                                              token=token, body=body, return_headers=True)
            remaining = (headers or {}).get("X-RateLimit-Remaining", "")
            sc.records.append(RequestRecord(
                endpoint=path, user=user_idx, status=status,
                latency_ms=now_ms() - start, ok=status == 200,
                detail=f"remain={remaining}",
            ))
            next_slot += per_user_interval
            drift = next_slot - time.time()
            if drift > 0:
                stop.wait(drift)

    with ThreadPoolExecutor(max_workers=len(tokens)) as pool:
        futures = [pool.submit(worker, i, t) for i, t in enumerate(tokens)]
        stop.wait(duration)
        stop.set()
        for f in futures:
            f.result(timeout=max(30, duration))

    sc.end_wall = now_ms()
    return sc


# ---------------------------------------------------------------- 主流程

def main() -> int:
    parser = argparse.ArgumentParser(description="HeartPilot 在线压测（完整规范版）")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="部署地址")
    parser.add_argument("--users", type=int, default=DEFAULT_USERS, help="测试用户数")
    parser.add_argument("--duration", type=int, default=DEFAULT_DURATION, help="持续负载时长（秒）")
    parser.add_argument("--ramp", type=int, default=DEFAULT_RAMP, help="爬坡时长（秒）")
    parser.add_argument("--ttft-samples", type=int, default=DEFAULT_TTFT_SAMPLES, help="SSE 首字延迟样本数")
    parser.add_argument("--burst-total", type=int, default=150, help="突发超限请求数")
    parser.add_argument("--smoke", action="store_true", help="只做 1 用户冒烟验证")
    parser.add_argument("--capacity-only", action="store_true",
                        help="只跑容量测试（多用户等效解除限流，只读接口，不污染业务数据）")
    parser.add_argument("--capacity-users", type=int, default=DEFAULT_CAPACITY_USERS,
                        help="容量测试注册的临时用户数（受认证接口 20 req/min/IP 限流约束）")
    parser.add_argument("--capacity-duration", type=int, default=DEFAULT_CAPACITY_DURATION,
                        help="容量测试稳态时长（秒）")
    parser.add_argument("--capacity-ramp", type=int, default=DEFAULT_RAMP,
                        help="容量测试爬坡时长（秒）")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    run_id = time.strftime("%Y%m%d-%H%M%S")
    print(f"[HeartPilot 压测] base={base} run={run_id}")

    # 0) 健康检查
    code, body = http_request("GET", base + API_PREFIX + "/health")
    print(f"[0] 健康检查 /api/health -> HTTP {code} {body.decode('utf-8', 'replace')[:50]}")
    if code != 200:
        print("[0] 服务不可达，终止。")
        return 2

    # 1) 准备测试用户（认证接口 20 req/min/IP，注册需错峰）
    if args.capacity_only:
        n = args.capacity_users
        user_prefix = CAPACITY_USER_PREFIX
        step_msg = (f"注册 {n} 个 rl_ 前缀临时用户（等效解除限流，20 req/min 限流错峰，"
                    f"约需 {n * 3.2 / 60:.1f} 分钟）")
    else:
        n = 1 if args.smoke else args.users
        user_prefix = "stress"
        step_msg = f"注册 {n} 个测试用户（按 20 req/min 限流错峰）"
    tokens: list[str] = []
    print(f"[1] {step_msg}...")
    for i in range(n):
        username = f"{user_prefix}{run_id.replace('-', '')[-8:]}{i:02d}"
        tokens.append(register_user(base, username))
        if i < n - 1:
            time.sleep(3.2)  # 3.2s/次 ≈ 18.75 req/min < 20，留安全余量
    print(f"[1] 完成，获得 {len(tokens)} 个 JWT。")
    # 2) 冒烟：单用户验证核心链路（登录态读接口 + SSE）
    smoke_token = tokens[0]
    code, body = http_request("GET", base + API_PREFIX + "/conversations", token=smoke_token)
    print(f"[2] 冒烟 GET /api/conversations -> HTTP {code}")
    if code != 200:
        print("[2] 登录态接口异常，终止。")
        return 3

    code, body = http_request("POST", base + API_PREFIX + "/conversations",
                              token=smoke_token, body={"title": "压测会话"})
    conversation_id = None
    if code == 200 and not args.capacity_only:
        conversation_id = json.loads(body.decode("utf-8")).get("id")
    print(f"[2] 冒烟 POST /api/conversations -> HTTP {code} id={conversation_id}")

    scenarios: list[Scenario] = []

    # 每个测试用户预建一个会话，供持续负载的"历史消息"读接口使用
    conversation_ids: list[int] = []
    if not args.capacity_only:
        for token in tokens:
            code, body = http_request("POST", base + API_PREFIX + "/conversations",
                                      token=token, body={"title": "压测会话"})
            if code == 200:
                conversation_ids.append(json.loads(body.decode("utf-8")).get("id"))
            else:
                conversation_ids.append(-1)

    if args.capacity_only:
        # 3) 容量测试：多用户等效解除限流（只读接口，零业务写入）
        print(f"[3] 容量测试：{args.capacity_users} 用户 × {args.capacity_duration}s"
              f"（每用户 119 req/min 打满配额窗口，聚合上限≈{args.capacity_users * 2} QPS）...")
        scenarios.append(scenario_capacity_no_limit(
            base, tokens, args.capacity_duration, args.capacity_ramp))
    elif not args.smoke:
        # 3) 登录态持续负载
        print(f"[3] 持续负载：{n} 用户 × {args.duration}s（爬坡 {args.ramp}s）...")
        scenarios.append(scenario_sustained(base, tokens, conversation_ids, args.duration, args.ramp))

        # 4) 单用户突发超限
        print(f"[4] 单用户突发 {args.burst_total} 次请求，验证 429 限流...")
        scenarios.append(scenario_burst(base, smoke_token, args.burst_total))

    # 5) SSE 首字延迟（用独立用户，避免前序突发测试耗尽同一用户限流配额）
    sse_token = smoke_token
    if not args.smoke and not args.capacity_only:
        sse_user = f"sse{run_id.replace('-', '')[-8:]}"
        time.sleep(3.2)
        sse_token = register_user(base, sse_user)
        code, body = http_request("POST", base + API_PREFIX + "/conversations",
                                  token=sse_token, body={"title": "SSE 压测会话"})
        if code == 200:
            conversation_id = json.loads(body.decode("utf-8")).get("id")
    if conversation_id is not None:
        questions = [
            "什么是非暴力沟通？",
            "如何向伴侣表达自己的需求？",
            "什么是尊重边界的请求？",
            "关系中出现冷战应该怎么办？",
            "如何提出对方能够明确回答的请求？",
            "沟通中如何倾听对方的感受？",
            "什么是关系中的边界？",
            "如何表达感谢更自然？",
            "发生争吵后如何修复关系？",
            "如何发起一次坦诚的对话？",
        ]
        print(f"[5] SSE 首字延迟测量 {args.ttft_samples} 次（会话 #{conversation_id}）...")
        scenarios.append(scenario_sse_ttft(
            base, sse_token, conversation_id, args.ttft_samples, questions))
    else:
        print("[5] 未创建到会话，跳过 SSE 测量。")

    # 6) 汇总输出
    report: dict = {"run_id": run_id, "base_url": base, "scenarios": []}
    if args.capacity_only:
        report["mode"] = "capacity_no_limit_readonly"
        report["capacity"] = {
            "users": args.capacity_users,
            "per_user_limit_per_min": DEFAULT_LIMIT_PER_MIN,
            "theoretical_aggregate_qps_cap": round(args.capacity_users * 2.0, 2),
            "endpoints": ["GET /conversations", "GET /agent-tasks"],
            "note": ("多用户等效解除限流：不修改线上限流配置，每用户以 119 req/min 打满自己的"
                     "配额窗口，聚合吞吐上限 = 用户数 × 2 QPS；只压只读接口，业务零写入"),
            "cleanup_sql": CAPACITY_CLEANUP_SQL,
        }
    print("\n" + "=" * 88)
    print("压测结果汇总")
    print("=" * 88)
    header = f"{'场景':<28}{'请求':>7}{'成功':>7}{'失败':>7}{'错误率':>9}{'QPS':>8}{'P50(ms)':>10}{'P95(ms)':>10}{'P99(ms)':>10}{'Max(ms)':>10}"
    print(header)
    print("-" * 88)
    for sc in scenarios:
        s = sc.summary()
        report["scenarios"].append(s)
        lc = s["latency_completed"]
        print(f"{s['scenario']:<28}{s['requests']:>7}{s['success']:>7}{s['error']:>7}"
              f"{s['error_rate']:>9.2%}{s['qps']:>8.2f}"
              f"{lc['p50_ms']:>10.1f}{lc['p95_ms']:>10.1f}"
              f"{lc['p99_ms']:>10.1f}{lc['max_ms']:>10.1f}")
        print(f"        状态码分布: {s['status_codes']}")
        if s["scenario"] == "sse_stream_ttft":
            ttft = [r.latency_ms for r in sc.records if r.ok]
            t = summarize_latency(ttft)
            print(f"        SSE 首字延迟(有效样本 {t['samples']}): "
                  f"P50={t['p50_ms']:.0f}ms P95={t['p95_ms']:.0f}ms "
                  f"P99={t['p99_ms']:.0f}ms Max={t['max_ms']:.0f}ms")
            for r in sc.records:
                print(f"        样本#{r.user}: status={r.status} ttft={r.latency_ms:.0f}ms {r.detail}")
    print("=" * 88)

    # 7) 落盘
    summary_path = RESULTS_DIR / f"stress-summary-{run_id}.json"
    summary_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    with (RESULTS_DIR / f"stress-records-{run_id}.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["scenario", "user", "endpoint", "status", "latency_ms", "ok", "detail"])
        for sc in scenarios:
            for r in sc.records:
                writer.writerow([sc.name, r.user, r.endpoint, r.status,
                                 f"{r.latency_ms:.1f}", r.ok, r.detail])
    print(f"[6] 结果已写入 {RESULTS_DIR}/stress-summary-{run_id}.json "
          f"与 stress-records-{run_id}.csv")
    if args.capacity_only:
        print("\n" + "=" * 88)
        print("[清理提示] 容量测试注册了 rl_ 前缀临时测试用户（仅写入 app_user 表，无业务数据）。")
        print("  请在 PostgreSQL 中执行以下 SQL 恢复数据库原状：")
        print("  " + CAPACITY_CLEANUP_SQL)
        print("=" * 88)
    return 0


if __name__ == "__main__":
    sys.exit(main())
