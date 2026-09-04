# pyright: reportUndefinedVariable=false

def _ensure_query_param(path, key, value):
    token = key + "="
    if token in path:
        return path
    sep = "&" if "?" in path else "?"
    return path + sep + key + "=" + value


def on_before_request(ctx):
    req = ctx.request

    req.path = _ensure_query_param(req.path, "xproxy_demo", "1")
    req.headers["X-Xproxy-Rewrite-Request"] = "1"

    if req.method.upper() in ("POST", "PUT", "PATCH") and "application/json" in ctx.request_raw.lower():
        body = req.body or ""
        if '"xproxy_demo":true' not in body:
            if body.strip().startswith("{") and body.strip().endswith("}"):
                payload = body.strip()
                if payload == "{}":
                    req.body = '{"xproxy_demo":true}'
                else:
                    req.body = payload[:-1] + ',"xproxy_demo":true}'

    ctx.log("[rewrite][before] " + req.method + " " + req.path)


def on_after_request(ctx):
    resp = ctx.response
    resp.headers["X-Xproxy-Rewrite-Response"] = "1"

    body = resp.body or ""
    mime = (resp.mime_type or "").lower()

    if mime in ("json", "text", "html"):
        updated = body.replace('"debug":false', '"debug":true')
        updated = updated.replace("<!-- xproxy-demo -->", "<!-- xproxy-demo:rewritten -->")
        resp.body = updated

    ctx.log("[rewrite][after] status=" + str(resp.status_code) + " mime=" + resp.mime_type)
