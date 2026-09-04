# pyright: reportUndefinedVariable=false

@MatchMethod("GET", "POST")
def on_http_message(ctx):
    p = ctx.path.lower()
    if p.startswith("http://") or p.startswith("https://"):
        marker = p.find("://")
        slash = p.find("/", marker + 3)
        p = p[slash:] if slash >= 0 else "/"

    indicators = ["/.git", "/.env", "/admin", "/backup", "/swagger", "/actuator", "/internal", "/debug"]

    for marker in indicators:
        if marker in p:
            ctx.report_issuse(
                ctx.request,
                ctx.response,
                "Sensitive Endpoint Exposure",
                "Request path contains sensitive marker: " + marker,
                "High",
                "Firm",
                "Restrict external exposure and enforce authentication.",
                "exposure,path"
            )
            return

    if p.startswith("/api/") and "actuator" not in p:
        original_path = ctx.request.path
        try:
            ctx.request.path = "/actuator/health"
            probe_response = ctx.send(ctx.request)
            body = probe_response.body.lower()
            if probe_response.status == 200 and "up" in body and "status" in body:
                ctx.log("[probe] exposed management endpoint via /actuator/health")
                ctx.report_issuse(
                    ctx.request,
                    probe_response,
                    "Management Endpoint Exposed",
                    "Follow-up probe '/actuator/health' returned status=200 with UP indicator.",
                    "High",
                    "Firm",
                    "Restrict management endpoints to internal network and require strong auth.",
                    "actuator,exposure,follow-up"
                )
        finally:
            ctx.request.path = original_path
