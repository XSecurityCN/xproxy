"""
BatteringRam mode demo (Burp Intruder equivalent).

使用说明：
- 该脚本模拟 BatteringRam 模式：同一条 payload 同时注入到所有占位符。
- 适合验证“多个字段共享同一值”场景（如用户名/密码/token 复用）。

How to use:
1) Put multiple {{placeholder}} placeholders in request where the same payload should be injected.
   Example: {"username":"{{username}}","password":"{{password}}","token":"{{token}}"}
2) Configure WORDLIST_PATH for your payload list.
3) Run attack. Each payload is copied to every placeholder in a single request.
4) This is useful for testing shared-value assumptions (same credential/token in multiple fields).
5) Adjust KEEP_STATUS_CODES and ERROR_STATUS_THRESHOLD to your target profile.

Behavior:
- Marker count N = number of {{placeholder}} markers in target.req
- For each payload p, queue one request with [p, p, ... p] (N times)
"""

import re

# 参数说明（前几行核心配置）
# CONCURRENT_CONNECTIONS: 并发连接数，越大速度越快但目标压力越高。
CONCURRENT_CONNECTIONS = 20
# REQUESTS_PER_CONNECTION: 单连接复用的请求数，影响吞吐和连接切换频率。
REQUESTS_PER_CONNECTION = 100
# WORDLIST_PATH: payload 字典文件路径（每行一个 payload）。
WORDLIST_PATH = "resources/intruder/wordlists/battering_ram.txt"
# FALLBACK_PAYLOADS: 字典文件不可用时的兜底 payload 列表。
FALLBACK_PAYLOADS = ["admin", "123456", "", "111111", "null"]
# KEEP_STATUS_CODES: 默认保留到结果表中的状态码白名单。
KEEP_STATUS_CODES = [200, 201, 202, 400, 401, 403, 404, 409, 429]
# ERROR_STATUS_THRESHOLD: 大于等于该状态码视为异常，优先保留。
ERROR_STATUS_THRESHOLD = 500
# PLACEHOLDER_PATTERN: 匹配请求模板中 {{placeholder}} 的正则。
PLACEHOLDER_PATTERN = r"\{\{\s*[A-Za-z0-9_.-]+\s*\}\}"


def read_wordlist(path, fallback):
    try:
        values = []
        for line in open(path):
            item = line.strip()
            if item:
                values.append(item)
        if values:
            return values
    except:
        pass
    return fallback


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    marker_count = len(re.findall(PLACEHOLDER_PATTERN, target.req))
    payloads = read_wordlist(WORDLIST_PATH, FALLBACK_PAYLOADS)

    if marker_count <= 0:
        return

    for payload in payloads:
        vector = [payload] * marker_count
        engine.queue(target.req, vector, label="battering-ram")


def handle_response(req, interesting):
    if req.status >= ERROR_STATUS_THRESHOLD:
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES:
        table.add(req)
