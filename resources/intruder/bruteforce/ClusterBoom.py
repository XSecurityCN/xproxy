"""
ClusterBomb mode demo (Burp Intruder equivalent, named ClusterBoom).

使用说明：
- 该脚本模拟 ClusterBomb（笛卡尔积）模式。
- 每个占位符使用独立字典，最终会组合出所有可能组合进行测试。

How to use:
1) Prepare a request with multiple {{placeholder}} placeholders.
   Example: {"user":"{{user}}","pass":"{{pass}}"}
2) Configure WORDLIST_PATHS. Each file maps to one placeholder position.
   - Position 1 uses WORDLIST_PATHS[0]
   - Position 2 uses WORDLIST_PATHS[1], etc.
3) Provide fallback lists in FALLBACK_WORDLISTS if files are missing.
4) Run attack. The script performs Cartesian product across all payload sets.
   For two placeholders, this is classic username x password combination brute-force.
5) Tune KEEP_STATUS_CODES and ERROR_STATUS_THRESHOLD for your target behavior.

Behavior:
- Marker count N = number of {{placeholder}} markers in target.req
- Use first N payload sets
- Queue one request for every combination from product(set1, set2, ... setN)
"""

import re

# 参数说明（前几行核心配置）
# CONCURRENT_CONNECTIONS: 并发连接数，影响扫描速度与目标压力。
CONCURRENT_CONNECTIONS = 20
# REQUESTS_PER_CONNECTION: 单连接请求复用上限，影响吞吐效率。
REQUESTS_PER_CONNECTION = 100
# WORDLIST_PATHS: 各占位符对应的字典文件路径列表（按位置对应）。
WORDLIST_PATHS = [
    "resources/intruder/wordlists/cluster_user.txt",
    "resources/intruder/wordlists/cluster_pass.txt",
]
# FALLBACK_WORDLISTS: 字典文件缺失时的兜底词表（按位置对应）。
FALLBACK_WORDLISTS = [
    ["admin", "guest", "test"],
    ["admin123", "password", "123456"],
]
# KEEP_STATUS_CODES: 默认保留的状态码白名单。
KEEP_STATUS_CODES = [200, 201, 202, 301, 302, 400, 401, 403, 404, 429]
# ERROR_STATUS_THRESHOLD: 高于该阈值的状态码视为异常优先保留。
ERROR_STATUS_THRESHOLD = 500
# PLACEHOLDER_PATTERN: 匹配请求模板中 {{placeholder}} 标记的正则。
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


def cartesian_product(lists):
    if not lists:
        return []
    result = [[]]
    for values in lists:
        next_result = []
        for prefix in result:
            for value in values:
                next_result.append(prefix + [value])
        result = next_result
    return result


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

    for combo in cartesian_product(payload_sets):
        engine.queue(target.req, combo, label="cluster")


def handle_response(req, interesting):
    if req.status >= ERROR_STATUS_THRESHOLD:
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES:
        table.add(req)
