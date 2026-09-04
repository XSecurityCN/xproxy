"""
Sniper mode demo (Burp Intruder equivalent).

使用说明：
- 该脚本模拟 Sniper 模式：一次只变更一个占位符，其余位置保持基线值。
- 适合定位“某个单独参数”对响应行为的影响。

How to use:
1) In the request editor, select multiple fields one by one and mark them as {{placeholder}} placeholders.
   Example body: {"username":"{{username}}","email":"{{email}}","role":"{{role}}"}
2) Set WORDLIST_PATH to your payload file (one payload per line), or rely on fallback payloads.
3) Optionally set BASE_VALUES so non-active placeholders keep stable values.
   - If BASE_VALUES is shorter than placeholder count, remaining values default to empty string.
4) Start attack. The script mutates one placeholder at a time while other placeholders keep base values.
5) Inspect response table and adjust KEEP_STATUS_CODES / ERROR_STATUS_THRESHOLD for your target.

Behavior:
- Marker count N = number of {{placeholder}} markers in target.req
- For each position i in [0, N): for each payload p in wordlist, queue one request
- Payload vector for request = base values with index i replaced by p
"""

import re

# 参数说明（前几行核心配置）
# CONCURRENT_CONNECTIONS: 并发连接数，控制请求发送速度。
CONCURRENT_CONNECTIONS = 20
# REQUESTS_PER_CONNECTION: 单连接请求复用数量，影响吞吐与稳定性。
REQUESTS_PER_CONNECTION = 100
# WORDLIST_PATH: 主 payload 字典路径（每行一个 payload）。
WORDLIST_PATH = "resources/intruder/wordlists/sniper.txt"
# FALLBACK_PAYLOADS: 字典读取失败时使用的后备 payload 列表。
FALLBACK_PAYLOADS = ["admin", "test", "guest", "root", "null"]
# BASE_VALUES: 非当前变更位置使用的基线值（按占位符顺序）。
BASE_VALUES = ["baseline-1", "baseline-2", "baseline-3"]
# KEEP_STATUS_CODES: 默认展示到结果表的状态码。
KEEP_STATUS_CODES = [200, 201, 202, 400, 401, 403, 404, 409, 429]
# ERROR_STATUS_THRESHOLD: 大于等于该状态码按异常优先保留。
ERROR_STATUS_THRESHOLD = 500
# PLACEHOLDER_PATTERN: 识别请求中占位符标记的正则表达式。
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


def build_base_values(marker_count):
    values = list(BASE_VALUES)
    while len(values) < marker_count:
        values.append("")
    return values[:marker_count]


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

    base_values = build_base_values(marker_count)

    for position in range(0, marker_count):
        for payload in payloads:
            active = list(base_values)
            active[position] = payload
            label = "sniper-pos{0}".format(position + 1)
            engine.queue(target.req, active, label=label)


def handle_response(req, interesting):
    if req.status >= ERROR_STATUS_THRESHOLD:
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES:
        table.add(req)
