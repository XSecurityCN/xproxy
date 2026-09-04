package org.jjgroup.xproxy.kits.ui

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

internal fun KitsPanel.buildApiTab(rootLabel: String, docs: List<KitsPanel.ApiDocEntry>): JPanel {
    val panel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    val root = DefaultMutableTreeNode(KitsPanel.ApiTreeNode(rootLabel))
    val categories = LinkedHashMap<String, DefaultMutableTreeNode>()
    docs.forEach { doc ->
        val categoryNode = categories.getOrPut(doc.category) {
            DefaultMutableTreeNode(KitsPanel.ApiTreeNode(doc.category)).also { root.add(it) }
        }
        categoryNode.add(DefaultMutableTreeNode(KitsPanel.ApiTreeNode(doc.name, doc)))
    }

    val treeModel = DefaultTreeModel(root)
    val tree = javax.swing.JTree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    val codeArea = RSyntaxTextArea(28, 100).apply {
        isEditable = false
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_PYTHON
        setCodeFoldingEnabled(true)
        antiAliasingEnabled = true
        isBracketMatchingEnabled = true
    }
    val codeScroll = RTextScrollPane(codeArea).apply {
        lineNumbersEnabled = true
    }

    tree.addTreeSelectionListener {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
        val data = node.userObject as? KitsPanel.ApiTreeNode ?: return@addTreeSelectionListener
        val entry = data.entry ?: return@addTreeSelectionListener
        codeArea.text = entry.code
        codeArea.caretPosition = 0
    }

    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(tree), codeScroll).apply {
        resizeWeight = 0.32
    }
    panel.add(split, BorderLayout.CENTER)

    val firstCategory = root.getChildAt(0) as? DefaultMutableTreeNode
    val firstLeaf = firstCategory?.getChildAt(0) as? DefaultMutableTreeNode
    if (firstLeaf != null) {
        val path = javax.swing.tree.TreePath(firstLeaf.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }
    return panel
}

internal fun apiDocEntries(): List<KitsPanel.ApiDocEntry> = listOf(
    KitsPanel.ApiDocEntry(
        category = "xapp",
        name = "XappLifecycle",
        code = """
            # xapp lifecycle and loading contract.
            # xapp 生命周期与加载约定。
            #
            # Plugin directory:
            # 插件目录：
            # ~/.xproxy/xapp/<plugin-folder>/
            #   - xapp.json
            #   - xapp.py (entry)
            #
            # Handler priority:
            # 1) on_before_request(ctx)      -> rewrite outbound request (match/replace style)
            # 2) on_after_request(ctx)       -> rewrite inbound response (match/replace style)
            # 3) on_proxy_http_message(ctx)  -> full proxy traffic stream
            # 4) on_http_message(ctx)        -> deduplicated traffic stream
            # 处理器优先级：先改请求/响应，再走流量事件。
            #
            #
            # --- Demo ---
            # Demonstrates two common handlers: deduplicated analysis and full-stream auditing.
            # 演示两类常见处理器：去重分析与全量流量审计。
            # def on_http_message(ctx):
            #     ctx.log("plugin loaded: {}".format(ctx.plugin_name))
            #
            # def on_proxy_http_message(ctx):
            #     if ctx.method == "OPTIONS":
            #         return
            #     ctx.log("proxy event {} {}".format(ctx.method, ctx.path))
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "events",
        name = "OnBeforeRequest",
        code = """
            def on_before_request(ctx):
                # Called before request is sent to upstream.
                # 在请求发往上游前触发。
                #
                # This event is designed for request rewriting and mirrors
                # the effect of proxy HTTP match-and-replace on requests.
                #
                # You can modify ctx.request fields directly.
                # 可直接修改 ctx.request 中的字段。
                #
                # --- Demo ---
                # Rewrites API requests by adding a debug header/query parameter.
                # 通过添加 debug 头和参数改写 API 请求。
                # if ctx.path.startswith("/api/"):
                #     ctx.request.headers["X-Debug"] = "1"
                #     if "debug=1" not in ctx.request.path:
                #         sep = "&" if "?" in ctx.request.path else "?"
                #         ctx.request.path = ctx.request.path + sep + "debug=1"
                pass
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "events",
        name = "OnAfterRequest",
        code = """
            def on_after_request(ctx):
                # Called after upstream response is received, before
                # response is returned to the browser/client.
                # 在收到上游响应后、返回客户端前触发。
                #
                # This event is designed for response rewriting and mirrors
                # the effect of proxy HTTP match-and-replace on responses.
                #
                # You can modify ctx.response fields directly.
                # 可直接修改 ctx.response 中的字段。
                #
                # --- Demo ---
                # Rewrites JSON response body on successful requests.
                # 在成功请求时改写 JSON 响应体。
                # if ctx.response.status_code == 200 and ctx.response.mime_type == "json":
                #     body = ctx.response.body
                #     ctx.response.body = body.replace("\"debug\":false", "\"debug\":true")
                pass
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "events",
        name = "OnHttpMessage",
        code = """
            def on_http_message(ctx):
                # Deduplicated traffic event.
                # 去重后的流量事件（同特征只处理一次）。
                #
                # Dedup key:
                # method + protocol + host + port + path
                #
                # No status-code filtering.
                # This handler is recommended when you want to avoid
                # repeated analysis on highly duplicated traffic.
                # 不按状态码过滤，适合做低噪音巡检。
                #
                # --- Demo ---
                # Reports stack-trace disclosures and logs deduplicated API traffic.
                # 上报堆栈泄露并记录去重后的 API 流量。
            # if ctx.response_regex(r"(?i)stack\\s*trace"):
                #     ctx.report_issuse(
                #         ctx.request,
                #         ctx.response,
                #         "Stack Trace Disclosure",
                #         "Response body appears to contain stack trace.",
                #         "Medium",
                #         "Firm",
                #         "Disable detailed exception output in production.",
                #         "error,disclosure"
                #     )
                #
                # if ctx.method == "GET" and ctx.path.startswith("/api/"):
                #     ctx.log("dedup api call: {}".format(ctx.url))
                pass
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "events",
        name = "OnProxyHttpMessage",
        code = """
            def on_proxy_http_message(ctx):
                # Full proxy traffic event (non-deduplicated).
                # 全量流量事件（不去重）。
                #
                # This receives every proxied HTTP message and is useful
                # for complete traffic auditing and correlation workflows.
                # 适合做完整审计、关联分析。
                #
                # --- Demo ---
                # Audits sensitive headers and reports upstream 5xx responses.
                # 审计敏感头并上报上游 5xx 响应。
            # if ctx.request_contains("Authorization:"):
                #     ctx.log("auth header seen on {}".format(ctx.path))
                #
                # if ctx.status_code >= 500:
                #     ctx.report_issuse(
                #         ctx.request,
                #         ctx.response,
                #         "Server Error Response",
                #         "Server returned {} for {}".format(ctx.status_code, ctx.path),
                #         "Low",
                #         "Tentative",
                #         "Review upstream logs and error handling.",
                #         "ops,5xx"
                #     )
                pass
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "context",
        name = "XappContext",
        code = """
            class XappContext:
                # Context passed to plugin handlers.
                # 传入插件处理函数的上下文对象。
                #
                # Core identity and request metadata fields are exposed
                # as python-friendly snake_case properties.
                # 核心字段使用 Python 友好的 snake_case 命名。
                #
                # --- Demo ---
                # Shows common context fields used for quick runtime diagnostics.
                # 展示运行时排查常用的上下文字段。
                # def on_http_message(ctx):
                #     ctx.log("{} {} {}".format(ctx.method, ctx.host, ctx.path))
                #     ctx.log("status={} mime={}".format(ctx.status_code, ctx.mime_type))
                #     ctx.log("request bytes={}".format(len(ctx.request_raw)))
                #     ctx.log("response bytes={}".format(len(ctx.response_raw)))

                plugin_id: str
                plugin_name: str

                method: str
                host: str
                path: str
                url: str

                status_code: int
                mime_type: str
                history_id: int

                request_raw: str
                response_raw: str

                request: "XappHttpRequest"
                response: "XappHttpResponse"
                codec: "XappCodecHelper"
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "helpers",
        name = "ContextMenus",
        code = """
            def register_context_menu(api):
                # Called once when the plugin is loaded or reloaded.
                # 插件加载或重新加载时调用一次。
                #
                # Use this hook to register HTTP message context-menu items.
                # The menu is shown in request/response viewers such as
                # Fuzzer, Proxy, Target and table detail panels.
                # 用于注册 HTTP 请求/响应查看器中的右键菜单项。
                #
                # label supports either slash-separated text or a list.
                # Both forms create nested menus:
                # label 支持字符串或列表，两者都可创建二级/多级菜单：
                #   "Header / Set custom header"
                #   ["Header", "Set custom header"]
                #
                # Editable viewers can apply request/response changes.
                # Read-only viewers can inspect, copy and send derived data.
                # 可编辑视图可写回；只读视图只能读取、复制或发送派生数据。
                #
                # --- Demo ---
                # Adds a nested editable action and a read-only safe codec action.
                # 演示嵌套菜单、输入参数和只读安全操作。
                # api.add_menu_item(
                #     label=["Header", "Set custom header"],
                #     contexts=["request"],
                #     tools=["fuzzer", "proxy"],
                #     requires_editable=True,
                #     handler="set_custom_header"
                # )
                #
                # api.add_menu_item(
                #     label="Codec / Copy selected as base64",
                #     contexts=["request", "response"],
                #     tools=[],
                #     requires_editable=False,
                #     handler="copy_selected_base64"
                # )
                pass

            api.add_menu_item(
                label: str | list[str],
                contexts: list[str] = [],       # request, response
                tools: list[str] = [],          # fuzzer, proxy, target, table
                requires_editable: bool = False,
                handler: str
            ) -> None

            class XappHttpMenuContext:
                # Context passed to context-menu handlers.
                # 传入右键菜单处理函数的上下文对象。
                #
                # --- Demo ---
                # def set_custom_header(ctx):
                #     values = ctx.prompt_fields(
                #         "Set custom header",
                #         [
                #             {"name": "header", "label": "Header", "default": "X-Debug"},
                #             {"name": "value", "label": "Value", "default": "1"},
                #         ]
                #     )
                #     if values is None:
                #         return
                #     ctx.request.headers[values["header"]] = values["value"]
                #     ctx.apply_request()
                #
                # def copy_selected_base64(ctx):
                #     text = ctx.selected_text or ctx.request_raw or ctx.response_raw
                #     ctx.copy_to_clipboard(ctx.codec.to_base64(text))

                tool: str                   # fuzzer / proxy / target / table / unknown
                message_part: str           # request / response
                editable: bool

                selection_start: int
                selection_end: int
                selected_text: str

                request_raw: str
                response_raw: str
                request: "XappHttpRequest"
                response: "XappHttpResponse"
                codec: "XappCodecHelper"

            ctx.apply_request(raw: str = None) -> bool
            ctx.apply_response(raw: str = None) -> bool
            ctx.replace_selection(text: str) -> bool
            ctx.copy_to_clipboard(text: str) -> None
            ctx.send_to_fuzzer(request_raw: str = None) -> None
            ctx.send_to_codec(text: str, tab_title: str = None) -> None

            ctx.prompt_text(title: str, message: str, default: str = None) -> str | None
            ctx.prompt_choice(title: str, message: str, choices: list[str], default: str = None) -> str | None
            ctx.prompt_fields(title: str, fields: list[dict]) -> dict | None
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "helpers",
        name = "RequestHelpers",
        code = """
            # helper methods on ctx
            # ctx 上的便捷辅助方法。
            #
            # request_contains / response_contains / response_regex are
            # convenience methods for fast content checks.
            #
            # send(request) sends follow-up traffic and records a
            # history row tagged with tool=xapp.
            # send(request) 会发起后续请求并写入历史（tool=xapp）。
            #
            # --- Demo ---
            # Demonstrates request mutation + follow-up probe via ctx.send().
            # 演示请求改写与 ctx.send() 后续探测。
            # def on_http_message(ctx):
            #     req = ctx.request
            #     req.path = "/health?debug=1"
            #     req.headers["debug"] = "1"
            #     probe = ctx.send(req)
            #     ctx.log("probe status={} path={}".format(probe.status_code, req.path))

            ctx.request_contains(token: str) -> bool
            ctx.response_contains(token: str) -> bool
            ctx.response_regex(pattern: str) -> bool
            ctx.log(message: str) -> None

            # sends follow-up request
            # history record is tagged as tool=xapp
            ctx.send(request: XappHttpRequest) -> XappHttpResponse
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "helpers",
        name = "CodecHelpers",
        code = """
            # codec helper namespace on ctx
            # 编解码工具命名空间：ctx.codec.*
            #
            # all methods below are available via ctx.codec.*
            # and reuse the same algorithms as the codec module.
            # 下列方法与主 Codec 模块算法保持一致。
            #
            # --- Demo ---
            # Demonstrates base64 decode + URL-safe re-encode workflow.
            # 演示 base64 解码与 URL 安全重编码流程。
            # def on_http_message(ctx):
            #     token = "dXNlcjpwYXNz"
            #     plain = ctx.codec.from_base64(token)
            #     ctx.log("decoded={}".format(plain))
            #     ctx.log(ctx.codec.to_base64_url("a+b/c?d"))

            ctx.codec.to_base64(input: str) -> str
            ctx.codec.from_base64(input: str) -> str
            ctx.codec.to_base64_url(input: str) -> str
            ctx.codec.from_base64_url(input: str) -> str

            ctx.codec.url_encode(input: str) -> str
            ctx.codec.url_encode_all(input: str) -> str
            ctx.codec.url_decode(input: str) -> str

            ctx.codec.to_hex(input: str, delimiter: str = "None") -> str
            ctx.codec.from_hex(input: str, delimiter: str = "None") -> str

            ctx.codec.html_encode(input: str) -> str
            ctx.codec.html_decode(input: str) -> str
            ctx.codec.jwt_decode_payload(input: str) -> str

            ctx.codec.md5(input: str) -> str
            ctx.codec.sha1(input: str) -> str
            ctx.codec.sha256(input: str) -> str
            ctx.codec.sha512(input: str) -> str

            ctx.codec.hmac(input: str, key: str, algorithm: str = "SHA-256", output: str = "hex") -> str

            ctx.codec.aes_encrypt(
                input: str,
                key: str,
                mode: str = "CBC",
                iv: str = "0000000000000000",
                output: str = "base64"
            ) -> str

            ctx.codec.aes_decrypt(
                input: str,
                key: str,
                mode: str = "CBC",
                iv: str = "0000000000000000",
                input_format: str = "base64"
            ) -> str

            ctx.codec.rot13(input: str) -> str
            ctx.codec.reverse(input: str) -> str
            ctx.codec.uppercase(input: str) -> str
            ctx.codec.lowercase(input: str) -> str
            ctx.codec.strip(input: str) -> str
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "reporting",
        name = "IssueReporting",
        code = """
            # reporting methods on ctx
            # 漏洞/问题上报方法。
            #
            # Recommended API name (正确拼写):
            # ctx.report_issue(...)
            # 推荐使用 report_issue(...) 进行上报。
            #
            # Legacy alias (兼容旧脚本,等价转发):
            # ctx.report_issuse(...)
            # 旧名 report_issuse(...) 仍可用,等价转发到 report_issue。
            #
            # --- Demo ---
            # Reports potential admin endpoint exposure with severity/confidence/remediation.
            # 上报潜在管理端点暴露，并附带严重度/置信度/修复建议。
            # def on_http_message(ctx):
            #     if "admin" in ctx.path.lower() and ctx.status_code == 200:
            #         ctx.report_issue(
            #             ctx.request,
            #             ctx.response,
            #             "Potential Admin Endpoint Exposure",
            #             "Publicly reachable admin-like route.",
            #             "High",
            #             "Firm",
            #             "Restrict route with authN/authZ and network controls.",
            #             "exposure,admin"
            #         )

            ctx.report_issue(request, response, name, detail)

            ctx.report_issue(
                request,
                response,
                name,
                detail,
                severity,
                confidence,
                remediation,
                tagsCsv
            )
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "annotation",
        name = "Highlight",
        code = """
            # traffic row highlight (mark/correlate traffic by color)
            # 流量行高亮:用颜色标记/关联流量。
            #
            # Color names: red, orange, yellow, green, cyan, blue, pink, gray.
            # "none"/"clear"/empty clears the highlight.
            # 颜色名见上;传 "none"/"clear"/空 即清除高亮。
            #
            # Only HTTP history entries carry a usable history_id (passive scan);
            # rewrite hooks (on_before_request/on_after_response) have no id yet -> ignored.
            # 仅 HTTP 历史条目有可用 history_id(被动扫描入口);改写钩子无 id,调用将被忽略。
            #
            # --- Demo ---
            # Marks any response carrying an auth token in red for later triage.
            # 将携带 token 的响应标红,便于后续定位。
            # def on_proxy_http_message(ctx):
            #     if ctx.response_contains("token"):
            #         ctx.highlight("red")
            #     # ctx.highlight("red", history_id=123L)  # mark an arbitrary entry by id

            ctx.highlight(color="red")
            ctx.highlight(color="red", history_id=123L)

            ctx.history_id  # int: current proxy history entry id (0 in rewrite hooks)
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "decorators",
        name = "BuiltInDecorators",
        code = """
            # decorators are auto-injected by runtime
            # 装饰器由运行时自动注入。
            #
            # Multiple decorators on the same handler are combined with AND logic.
            # 多个装饰器叠加时采用 AND 逻辑。
            #
            # --- Demo ---
            # Demonstrates combining decorators for request/response filtering.
            # 演示通过组合装饰器完成请求响应过滤。
            # @MatchMethod("GET")
            # @MatchStatus(200, 204)
            # @MatchMimeType("json", "text")
            # @MatchPathRegex(r"^/api/")
            # def on_http_message(ctx):
            #     if ctx.response_contains("debug"):
            #         ctx.log("api debug marker found: {}".format(ctx.url))

            @MatchStatus(200, 204)
            @FilterStatus(404)
            @MatchMimeType("json", "text")
            @MatchMethod("GET", "POST")
            @MatchHostRegex(r"...")
            @MatchPathRegex(r"...")
            def on_http_message(ctx):
                pass
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "models",
        name = "XappHttpRequest",
        code = """
            class XappHttpRequest:
                # Mutable request object for follow-up traffic.
                # 用于后续请求的可变请求对象。
                #
                # --- Demo ---
                # Shows how to build a follow-up request by mutating fields.
                # 展示如何通过修改字段构造后续请求。
                # req = ctx.request
                # req.method = "GET"
                # req.path = "/api/version?debug=1"
                # req.headers["debug"] = "1"
                # req.headers["X-Trace"] = "xapp"
                # req.body = ""

                method: str
                path: str
                host: str
                port: int
                tls: bool
                headers: dict[str, str]
                body: str
        """.trimIndent()
    ),
    KitsPanel.ApiDocEntry(
        category = "models",
        name = "XappHttpResponse",
        code = """
            class XappHttpResponse:
                # Parsed response object returned from ctx.send().
                # ctx.send() 返回的解析后响应对象。
                #
                # --- Demo ---
                # Demonstrates response inspection after ctx.send().
                # 演示 ctx.send() 后的响应检查。
                # resp = ctx.send(ctx.request)
                # if resp.status_code == 200 and "version" in resp.body:
                #     ctx.log("version endpoint exposed")
                #
                # if "set-cookie" in "\n".join(resp.headers.keys()).lower():
                #     ctx.log("response sets cookie")

                status: int
                status_code: int
                mime_type: str
                headers: dict[str, str]
                body: str
                raw: str
        """.trimIndent()
    )
)
