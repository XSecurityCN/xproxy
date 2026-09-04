# pyright: reportUndefinedVariable=false

@MatchStatus(200, 201, 202, 204, 301, 302, 304)
@MatchMimeType("html", "json", "text")
def on_http_message(ctx):
    if ctx.path.lower().endswith((".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".woff", ".woff2")):
        return

    response_raw = ctx.response_raw.lower()
    required = [
        "content-security-policy:",
        "strict-transport-security:",
        "x-frame-options:",
        "x-content-type-options:"
    ]

    if ctx.url.startswith("http://"):
        required = [h for h in required if h != "strict-transport-security:"]

    missing = []
    for header in required:
        if header not in response_raw:
            missing.append(header[:-1])

    if len(missing) == 0:
        return

    ctx.log("[headers] missing -> " + ", ".join(missing))
    ctx.report_issuse(
        ctx.request,
        ctx.response,
        "Missing Security Headers",
        "Missing headers: " + ", ".join(missing),
        "Low",
        "Firm",
        "Add security response headers at gateway/application level.",
        "headers,hardening,demo"
    )
