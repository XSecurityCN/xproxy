"""
Pitchfork mode demo (Burp Intruder equivalent).

使用说明：
- 该脚本模拟 Pitchfork 模式：多个字典按相同索引位“并行取值”注入。
- 典型场景是 user/pass/otp 等位置一一对应联动测试。

How to use:
1) Create a request with multiple {{placeholder}} placeholders.
   Example: {"user":"{{user}}","pass":"{{pass}}","otp":"{{otp}}"}
2) Configure WORDLIST_PATHS: one file per placeholder in positional order.
3) Provide FALLBACK_WORDLISTS as backup values.
4) Run attack. Payload lists are consumed in lock-step (same index across lists).
   - Request #1 uses list1[0], list2[0], list3[0]
   - Request #2 uses list1[1], list2[1], list3[1], etc.
5) Effective request count equals shortest list length among participating payload sets.

Behavior:
- Marker count N = number of {{placeholder}} markers in target.req
- Build N payload sets from configured files/fallbacks
- Queue zip(set1, set2, ... setN)
"""

import re

# 参数说明（前几行核心配置）
# CONCURRENT_CONNECTIONS: 并发连接数，控制总体请求并发压力。
CONCURRENT_CONNECTIONS = 20
# REQUESTS_PER_CONNECTION: 单连接可处理的请求数，影响连接复用效率。
REQUESTS_PER_CONNECTION = 100
# WORDLIST_PATHS: 多个字典文件路径，按占位符位置顺序映射。
WORDLIST_PATHS = [
    "resources/intruder/wordlists/pitchfork_user.txt",
    "resources/intruder/wordlists/pitchfork_pass.txt",
    "resources/intruder/wordlists/pitchfork_otp.txt",
]
# FALLBACK_WORDLISTS: 当对应字典文件不可用时的兜底词表（按位置对应）。
FALLBACK_WORDLISTS = [
    ["admin", "guest", "test"],
    ["admin123", "password", "123456"],
    ["000000", "111111", "222222"],
]
# KEEP_STATUS_CODES: 默认保留状态码白名单。
KEEP_STATUS_CODES = [200, 201, 202, 301, 302, 400, 401, 403, 404, 409, 429]
# ERROR_STATUS_THRESHOLD: 大于等于该值的状态码按异常优先保留。
ERROR_STATUS_THRESHOLD = 500
# PLACEHOLDER_PATTERN: 匹配请求模板中占位符的正则规则。
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
    if marker_count <= 0:
        return

    payload_sets = []
    for index in range(0, marker_count):
        path = WORDLIST_PATHS[index] if index < len(WORDLIST_PATHS) else ""
        fallback = (
            FALLBACK_WORDLISTS[index] if index < len(FALLBACK_WORDLISTS) else [""]
        )
        payload_sets.append(read_wordlist(path, fallback))

    request_count = min([len(values) for values in payload_sets]) if payload_sets else 0
    for index in range(0, request_count):
        vector = [values[index] for values in payload_sets]
        engine.queue(target.req, vector, label="pitchfork")


def handle_response(req, interesting):
    if req.status >= ERROR_STATUS_THRESHOLD:
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES:
        table.add(req)
