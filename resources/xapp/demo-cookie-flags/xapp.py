# pyright: reportUndefinedVariable=false

@MatchStatus(200, 201, 202, 204, 301, 302)
def on_http_message(ctx):
    if "set-cookie:" not in ctx.response_raw.lower():
        return

    lines = ctx.response_raw.splitlines()
    weak = []

    for line in lines:
        lower = line.lower()
        if not lower.startswith("set-cookie:"):
            continue
        missing = []
        if "httponly" not in lower:
            missing.append("HttpOnly")
        if "secure" not in lower:
            missing.append("Secure")
        if len(missing) > 0:
            weak.append(line + " (missing " + "/".join(missing) + ")")

    if len(weak) == 0:
        return

    if "/login" not in ctx.path.lower() and "/auth" not in ctx.path.lower() and "/session" not in ctx.path.lower():
        severity = "Low"
    else:
        severity = "Medium"

    ctx.log("[cookies] weak attributes: " + str(len(weak)))
    ctx.report_issuse(
        ctx.request,
        ctx.response,
        "Weak Cookie Attributes",
        "\n".join(weak),
        severity,
        "Firm",
        "Set Secure and HttpOnly on sensitive cookies.",
        "cookie,session"
    )
