package org.jjgroup.xproxy.kits.ui

internal fun intruderApiDocEntries(): List<KitsPanel.ApiDocEntry> {
    return listOf(
        KitsPanel.ApiDocEntry(
            category = "intruder",
            name = "Lifecycle",
            code = """
                # intruder attack script lifecycle
                # intruder 攻击脚本生命周期。
                #
                # Script directory:
                # 脚本目录：
                # ~/.xproxy/intruder/<category>/<script>.py
                #
                # Required handlers:
                # - queue_requests(target, wordlists)
                # - handle_response(req, interesting)
                # 必需入口：queue_requests 与 handle_response。
                #
                # Runtime bootstrap order:
                # 1) load IntruderRuntime.py
                # 2) exec your script
                # 3) invoke queue_requests(target, wordlists)
                # 4) callback handle_response(req, interesting)
                # 运行顺序：先加载运行时，再执行脚本并回调处理函数。

                # Minimal intruder flow: queue requests, then handle interesting responses.
                # 最小化 intruder 流程：先投递请求，再处理有价值响应。
                def queue_requests(target, wordlists):
                    engine = RequestEngine(endpoint=target.endpoint, concurrent_connections=5)
                    engine.queue(target.req)

                def handle_response(req, interesting):
                    if interesting:
                        table.add(req)
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "entrypoints",
            name = "QueueRequests",
            code = """
                def queue_requests(target, wordlists):
                    # Build and schedule attack requests here.
                    # 在这里构造并投递攻击请求。
                    #
                    # target.req       -> base request template (str)
                    # target.rawreq    -> base request bytes
                    # target.endpoint  -> protocol://host:port
                    # target.base_input -> selected base input (if any)
                    # wordlists        -> helpers: bruteforce / observed_words / clipboard
                    # wordlists 提供爆破字典、观测词与剪贴板词。
                    #
                    # Queues fixed payloads + clipboard payloads for path probing.
                    # 使用固定 payload 与剪贴板 payload 进行路径探测。
                    engine = RequestEngine(
                        endpoint=target.endpoint,
                        concurrent_connections=8,
                        requests_per_connection=100,
                        max_queue_size=200
                    )

                    for payload in ["admin", "debug", "health"]:
                        engine.queue(target.req, [payload], label="path-fuzz")

                    for token in wordlists.clipboard:
                        engine.queue(target.req, [token], label="clipboard")

                    engine.complete(timeout=-1)
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "entrypoints",
            name = "HandleResponse",
            code = """
                def handle_response(req, interesting):
                    # Called for each attack response.
                    # 每个响应返回后都会调用。
                    #
                    # req.status / req.code
                    # req.response
                    # req.wordcount
                    # req.length
                    # req.time
                    # req.label
                    # req.engine
                    # 可结合 status/length/内容特征进行筛选。

                    # Filters token hits and reports server-error anomalies.
                    # 过滤 token 命中，并上报服务端错误异常。
                    body = (req.response or "").lower()
                    if req.status == 200 and "token" in body:
                        req.label = "token-hit"
                        table.add(req)

                    if req.status >= 500:
                        report_issue(
                            name="Intruder 5xx response",
                            severity="Medium",
                            detail="Server returned {} on fuzzed input".format(req.status),
                            request=req,
                            response=req.response,
                            confidence="Firm",
                            tags=["intruder", "5xx"]
                        )
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "models",
            name = "TargetAndWordlists",
            code = """
                class Target:
                    # 攻击目标与基准请求信息。
                    req: str
                    rawreq: bytes
                    endpoint: str
                    base_input: str

                class Wordlists:
                    # 运行时注入的字典工具集合。
                    bruteforce: object
                    observed_words: set[str]
                    clipboard: list[str]

                # Demo
                # Uses bruteforce + observed words as two payload sources.
                # 使用爆破词与已观测词两类 payload 来源。
                def queue_requests(target, wordlists):
                    engine = RequestEngine(endpoint=target.endpoint)
                    seed = 0
                    batch = []
                    seed = wordlists.bruteforce.generate(seed, 100, batch)
                    for word in batch:
                        engine.queue(target.req, [word], label="bruteforce")

                    for word in wordlists.observed_words:
                        engine.queue(target.req, [word], label="observed")
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "models",
            name = "RequestResult",
            code = """
                class RequestResult:
                    # handle_response 中的 req 对象字段。
                    template: str
                    words: list[str]
                    label: str

                    response: str
                    status: int
                    code: int
                    length: int
                    wordcount: int
                    time: int
                    order: int

                    engine: object

                # Demo
                # Keeps non-404 non-empty responses and adds readable labels.
                # 保留非 404 且非空响应，并附加可读标签。
                def handle_response(req, interesting):
                    if req.status != 404 and req.length > 0:
                        req.label = "status-{}-len-{}".format(req.status, req.length)
                        table.add(req)
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "engine",
            name = "RequestEngineConstructor",
            code = """
                class Engine:
                    HTTP = 1
                    HTTP2 = 2
                    SPIKE = 3

                # Constructor (high-frequency options)
                # 构造函数（常用参数）。
                RequestEngine(
                    endpoint,
                    callback=None,
                    engine=Engine.HTTP,
                    concurrent_connections=50,
                    requests_per_connection=100,
                    pipeline=False,
                    max_queue_size=100,
                    timeout=10,
                    max_retries_per_request=3,
                    idle_timeout=0,
                    read_callback=None,
                    read_size=1024,
                    resume_ssl=True,
                    auto_start=True,
                    explode_on_early_read=False
                )

                # Demo
                # Creates HTTP/2 engine and starts attack manually.
                # 创建 HTTP/2 引擎并手动启动攻击。
                def queue_requests(target, wordlists):
                    engine = RequestEngine(
                        endpoint=target.endpoint,
                        engine=Engine.HTTP2,
                        concurrent_connections=20,
                        requests_per_connection=500,
                        auto_start=False
                    )
                    engine.queue(target.req)
                    engine.start()
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "engine",
            name = "RequestEngineMethods",
            code = """
                # request scheduling
                # 请求投递方法。
                engine.queue(
                    template,
                    payloads=None,
                    learn=0,
                    callback=None,
                    gate=None,
                    label="",
                    pause_before=0,
                    pause_time=1000,
                    pause_marker=[],
                    delay=0,
                    endpoint=None,
                    fix_content_length=True
                )

                # runtime controls
                # 运行控制方法。
                engine.open_gate(gate_name)
                engine.apply_setting(setting_name, setting_value)
                engine.start(timeout=5)
                engine.complete(timeout=-1)
                engine.cancel()

                # Demo: simple race gate
                # Queues multiple payloads behind one gate, then releases together.
                # 将多个 payload 挂到同一 gate，再统一放行。
                def queue_requests(target, wordlists):
                    engine = RequestEngine(endpoint=target.endpoint, concurrent_connections=10)
                    for payload in ["A", "B", "C"]:
                        engine.queue(target.req, [payload], gate="race-1", label="race")
                    engine.open_gate("race-1")
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "decorators",
            name = "BuiltInDecorators",
            code = """
                # matching decorators
                # 组合匹配装饰器，仅在目标特征全部命中时保留结果。
                @MatchStatus(200, 302)
                @MatchRegex(r"(?i)token|secret")
                @MatchSizeRange(100, 4000)
                @MatchWordCountRange(10, 500)
                @MatchLineCountRange(5, 200)
                def handle_response(req, interesting):
                    table.add(req)

                # filtering decorators
                # 组合过滤装饰器，去除噪音响应后输出剩余结果。
                @FilterStatus(404)
                @FilterRegex(r"(?i)not\s+found")
                @FilterSizeRange(0, 100)
                @FilterWordCount(0, 1)
                @FilterLineCountRange(0, 2)
                def handle_response(req, interesting):
                    table.add(req)

                # uniqueness decorators
                # 去重装饰器用于限制重复样本数量。
                @UniqueWordCount(instances=1)
                @UniqueLineCount(instances=1)
                @UniqueSize(instances=1)
                def handle_response(req, interesting):
                    table.add(req)
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "helpers",
            name = "CodecHelpers",
            code = """
                # codec helpers are available in intruder scripts
                # intruder 脚本可直接使用 codec 工具。
                #
                # access style:
                # - codec.to_base64(...)
                # - ctx.codec.to_base64(...)
                # 两种访问方式都可用。
                #
                # --- Demo ---
                # Encodes payload with codec and queues it for execution.
                # 使用 codec 编码 payload 并入队执行。
                # def queue_requests(target, wordlists):
                #     payload = codec.to_base64("admin:admin")
                #     # or: payload = ctx.codec.to_base64("admin:admin")
                #     engine = RequestEngine(endpoint=target.endpoint, concurrent_connections=3)
                #     engine.queue(target.req, [payload])
                #     engine.start()

                codec.to_base64(input: str) -> str
                codec.from_base64(input: str) -> str
                codec.to_base64_url(input: str) -> str
                codec.from_base64_url(input: str) -> str

                codec.url_encode(input: str) -> str
                codec.url_encode_all(input: str) -> str
                codec.url_decode(input: str) -> str

                codec.to_hex(input: str, delimiter: str = "None") -> str
                codec.from_hex(input: str, delimiter: str = "None") -> str

                codec.html_encode(input: str) -> str
                codec.html_decode(input: str) -> str
                codec.jwt_decode_payload(input: str) -> str

                codec.md5(input: str) -> str
                codec.sha1(input: str) -> str
                codec.sha256(input: str) -> str
                codec.sha512(input: str) -> str

                codec.hmac(input: str, key: str, algorithm: str = "SHA-256", output: str = "hex") -> str

                codec.aes_encrypt(
                    input: str,
                    key: str,
                    mode: str = "CBC",
                    iv: str = "0000000000000000",
                    output: str = "base64"
                ) -> str

                codec.aes_decrypt(
                    input: str,
                    key: str,
                    mode: str = "CBC",
                    iv: str = "0000000000000000",
                    input_format: str = "base64"
                ) -> str

                codec.rot13(input: str) -> str
                codec.reverse(input: str) -> str
                codec.uppercase(input: str) -> str
                codec.lowercase(input: str) -> str
                codec.strip(input: str) -> str
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "helpers",
            name = "UtilityHelpers",
            code = """
                # utility helpers from IntruderRuntime.py
                # IntruderRuntime.py 提供的通用辅助函数。
                randstr(length=12, allow_digits=True) -> str
                mean(data: list[number]) -> float
                stddev(data: list[number]) -> float
                queue_forever(engine, req) -> None

                # Demo
                # Uses randstr to generate probe payload and keeps timed responses.
                # 使用 randstr 生成探测 payload，并保留有耗时的响应。
                def queue_requests(target, wordlists):
                    engine = RequestEngine(endpoint=target.endpoint)
                    marker = randstr(8)
                    engine.queue(target.req, [marker], label="rand")

                def handle_response(req, interesting):
                    if req.time > 0:
                        table.add(req)
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "helpers",
            name = "TableOutput",
            code = """
                # output handler injected as `table`
                # `table` 是注入的结果输出对象。
                table.add(req)
                table.getAllRquests() -> list[req]

                # Demo
                # Stores interesting/success responses and stops on oversized output.
                # 保存 interesting/成功响应，并在结果过大时停止攻击。
                def handle_response(req, interesting):
                    if interesting or req.status in (200, 302):
                        table.add(req)
                    if len(table.getAllRquests()) > 5000:
                        req.engine.cancel()
            """.trimIndent()
        ),
        KitsPanel.ApiDocEntry(
            category = "reporting",
            name = "IssueReporting",
            code = """
                # issue reporting from intruder scripts
                # intruder 脚本中的问题上报接口。
                #
                # report_issue(name, severity, detail, request, response, ...)
                # 传入 request/response 可自动补齐上下文信息。
                #
                # --- Demo ---
                # Reports debug-marker disclosures with structured issue fields.
                # 对调试标记泄露进行结构化上报。
                # def handle_response(req, interesting):
                #     if req.status == 200 and "debug" in req.response.lower():
                # Issue payload fields below are examples of common report metadata.
                # 下方字段为常见问题上报元数据示例。
                # report_issue(
                #     name="Debug Artifact",
                #     severity="Low",
                #     detail="Response contains debug marker",
                #     request=req,
                #     response=req.response,
                #     confidence="Firm",
                #     remediation="Disable verbose debug output in production.",
                #     url="",
                #     host="",
                #     path="",
                #     method="",
                #     tags=["intruder", "debug"]
                # )

                # equivalent method on RequestEngine instance
                # engine.report_issue(...)
            """.trimIndent()
        )
    )
}
