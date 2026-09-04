# pyright: reportUndefinedVariable=false

MAX_PREVIEW = 1200
MAX_HEADER_LINES = 80


def _split_http_message(raw):
    if raw is None:
        return "", ""
    text = _to_text(raw)
    if text.find("\r\n\r\n") >= 0:
        return text.split("\r\n\r\n", 1)
    if text.find("\n\n") >= 0:
        return text.split("\n\n", 1)
    return text, ""


def _preview(text, limit=MAX_PREVIEW):
    value = _to_text(text)
    if len(value) <= limit:
        return value
    return value[:limit] + "\n...[truncated {} chars]".format(len(value) - limit)


def _line_count(text):
    if text is None or len(text) == 0:
        return 0
    return len(_to_text(text).splitlines())


def _to_text(value):
    if value is None:
        return ""
    try:
        return u"%s" % value
    except Exception:
        try:
            return str(value)
        except Exception:
            return ""


def _safe_get(obj, name, default=None):
    tried = []

    def _attempt(attr_name):
        tried.append(attr_name)
        try:
            value = getattr(obj, attr_name)
            if value is None:
                return default
            return value
        except Exception:
            return None

    result = _attempt(name)
    if result is not None:
        return result

    camel = name
    if "_" in name:
        parts = name.split("_")
        if len(parts) > 1:
            camel = parts[0] + "".join([p[:1].upper() + p[1:] for p in parts[1:] if len(p) > 0])
            result = _attempt(camel)
            if result is not None:
                return result

    getter = "get" + camel[:1].upper() + camel[1:]
    tried.append(getter + "()")
    try:
        fn = getattr(obj, getter)
        value = fn()
        if value is None:
            return default
        return value
    except Exception:
        return default


def _safe_log(ctx, message):
    text = _to_text(message)
    try:
        ctx.log(text)
    except Exception:
        try:
            print(text)
        except Exception:
            pass


def _emit_section(ctx, title):
    _safe_log(ctx, "")
    _safe_log(ctx, "========== {} ==========".format(title))


def _emit_headers(ctx, raw_headers, prefix):
    lines = _to_text(raw_headers).splitlines()
    if len(lines) == 0:
        _safe_log(ctx, "{} headers: <empty>".format(prefix))
        return

    _safe_log(ctx, "{} headers count: {}".format(prefix, len(lines)))
    max_lines = min(len(lines), MAX_HEADER_LINES)
    index = 0
    while index < max_lines:
        _safe_log(ctx, "{} H[{}]: {}".format(prefix, index, lines[index]))
        index += 1

    if len(lines) > MAX_HEADER_LINES:
        _safe_log(ctx, "{} H[...]: {} more header lines hidden".format(prefix, len(lines) - MAX_HEADER_LINES))


def _extract_query(url_text):
    text = _to_text(url_text)
    qidx = text.find("?")
    if qidx < 0:
        return ""
    frag = text.find("#", qidx + 1)
    if frag < 0:
        return text[qidx + 1:]
    return text[qidx + 1:frag]


def _upsert_query_param(path_text, key, value):
    path = _to_text(path_text)
    if len(path) == 0:
        path = "/"

    hash_index = path.find("#")
    fragment = ""
    base = path
    if hash_index >= 0:
        fragment = path[hash_index:]
        base = path[:hash_index]

    qidx = base.find("?")
    if qidx < 0:
        query_pairs = []
        route = base
    else:
        route = base[:qidx]
        query = base[qidx + 1:]
        query_pairs = [p for p in query.split("&") if len(p) > 0]

    updated = []
    replaced = False
    key_lower = _to_text(key).lower()
    for pair in query_pairs:
        if "=" in pair:
            k, v = pair.split("=", 1)
        else:
            k, v = pair, ""
        if _to_text(k).lower() == key_lower:
            if not replaced:
                updated.append("{}={}".format(key, value))
                replaced = True
            continue
        updated.append(pair)

    if not replaced:
        updated.append("{}={}".format(key, value))

    rebuilt = route
    if len(updated) > 0:
        rebuilt += "?" + "&".join(updated)
    return rebuilt + fragment


def _set_header_case_insensitive(headers_obj, name, value):
    if headers_obj is None:
        return None

    existing_key = None
    existing_value = None
    keys = []

    try:
        keys = list(headers_obj.keys())
    except Exception:
        try:
            keys = list(headers_obj.keySet())
        except Exception:
            keys = []

    lower_name = _to_text(name).lower()
    for key in keys:
        if _to_text(key).lower() == lower_name:
            existing_key = key
            try:
                existing_value = headers_obj[key]
            except Exception:
                existing_value = None
            break

    target_key = existing_key if existing_key is not None else name
    try:
        headers_obj[target_key] = value
        return (target_key, existing_value, existing_key is not None)
    except Exception:
        return None


def _restore_header(headers_obj, token):
    if headers_obj is None or token is None:
        return
    key, old_value, existed = token
    try:
        if existed:
            headers_obj[key] = old_value
        else:
            del headers_obj[key]
    except Exception:
        pass


def _run_send_test(ctx):
    try:
        req_obj = getattr(ctx, "request")
    except Exception:
        req_obj = None
    if req_obj is None:
        _safe_log(ctx, "[send-test] skipped: ctx.request unavailable")
        return

    try:
        original_path = getattr(req_obj, "path")
    except Exception:
        original_path = ""
    try:
        headers_obj = getattr(req_obj, "headers")
    except Exception:
        headers_obj = None
    restore_token = _set_header_case_insensitive(headers_obj, "debug", "1")

    try:
        new_path = _upsert_query_param(original_path, "debug", "1")
        req_obj.path = new_path
        _safe_log(ctx, "[send-test] outgoing path={} header debug=1".format(new_path))
        probe = ctx.send(req_obj)
        probe_status = _safe_get(probe, "status", _safe_get(probe, "status_code", "0"))
        probe_mime = _safe_get(probe, "mime_type", "")
        probe_body = _safe_get(probe, "body", "")
        _safe_log(ctx, "[send-test] response status={} mime={} body_chars={}".format(probe_status, probe_mime, len(_to_text(probe_body))))
    except Exception as ex:
        _safe_log(ctx, "[send-test-error] {}".format(_to_text(ex)))
    finally:
        try:
            req_obj.path = original_path
        except Exception:
            pass
        _restore_header(headers_obj, restore_token)


def _emit_query(ctx, url_text):
    query = _extract_query(url_text)
    if len(query) == 0:
        _safe_log(ctx, "query params: <none>")
        return

    pairs = [p for p in query.split("&") if len(p) > 0]
    _safe_log(ctx, "query params count: {}".format(len(pairs)))
    idx = 0
    for pair in pairs:
        if "=" in pair:
            key, value = pair.split("=", 1)
        else:
            key, value = pair, ""
        _safe_log(ctx, "Q[{}]: {} = {}".format(idx, key, value))
        idx += 1


def _emit_cookie_summary(ctx, response_headers):
    lines = _to_text(response_headers).splitlines()
    cookies = []
    for line in lines:
        lower = line.lower()
        if lower.startswith("set-cookie:"):
            cookies.append(line)
    if len(cookies) == 0:
        _safe_log(ctx, "set-cookie: <none>")
        return

    _safe_log(ctx, "set-cookie count: {}".format(len(cookies)))
    for index, cookie_line in enumerate(cookies):
        lower = cookie_line.lower()
        attrs = []
        attrs.append("HttpOnly={}".format("yes" if "httponly" in lower else "no"))
        attrs.append("Secure={}".format("yes" if "secure" in lower else "no"))
        attrs.append("SameSite={}".format("yes" if "samesite" in lower else "no"))
        _safe_log(ctx, "cookie[{}]: {} | {}".format(index, _preview(cookie_line, 260), ", ".join(attrs)))


def _emit_security_header_summary(ctx, response_headers):
    lower = _to_text(response_headers).lower()
    expected = [
        "content-security-policy:",
        "strict-transport-security:",
        "x-frame-options:",
        "x-content-type-options:",
        "referrer-policy:"
    ]
    missing = []
    for token in expected:
        if token not in lower:
            missing.append(token[:-1])
    if len(missing) == 0:
        _safe_log(ctx, "security headers: complete")
    else:
        _safe_log(ctx, "security headers missing: {}".format(", ".join(missing)))


def on_proxy_http_message(ctx):
    try:
        request_raw = _safe_get(ctx, "request_raw", "")
        response_raw = _safe_get(ctx, "response_raw", "")
        req_headers, req_body = _split_http_message(request_raw)
        resp_headers, resp_body = _split_http_message(response_raw)

        _emit_section(ctx, "xapp debug http observer")
        _safe_log(ctx, "plugin: {} ({})".format(_safe_get(ctx, "plugin_name", "<unknown>"), _safe_get(ctx, "plugin_id", "<unknown>")))
        _safe_log(ctx, "request_line: {} {}".format(_safe_get(ctx, "method", ""), _safe_get(ctx, "url", "")))
        _safe_log(ctx, "host/path: {} {}".format(_safe_get(ctx, "host", ""), _safe_get(ctx, "path", "")))
        _safe_log(ctx, "response: status={} mime={}".format(_safe_get(ctx, "status_code", ""), _safe_get(ctx, "mime_type", "")))

        _emit_section(ctx, "request headers")
        _emit_headers(ctx, req_headers, "REQ")

        _emit_section(ctx, "request body")
        _safe_log(ctx, "req body chars: {} | lines: {}".format(len(req_body), _line_count(req_body)))
        _safe_log(ctx, _preview(req_body))

        _emit_section(ctx, "request query")
        _emit_query(ctx, _safe_get(ctx, "url", ""))

        _emit_section(ctx, "response headers")
        _emit_headers(ctx, resp_headers, "RESP")

        _emit_section(ctx, "response body")
        _safe_log(ctx, "resp body chars: {} | lines: {}".format(len(resp_body), _line_count(resp_body)))
        _safe_log(ctx, _preview(resp_body))

        _emit_section(ctx, "response security summary")
        _emit_cookie_summary(ctx, resp_headers)
        _emit_security_header_summary(ctx, resp_headers)

        _emit_section(ctx, "fingerprint")
        body_lower = _to_text(resp_body).lower()
        _safe_log(ctx, "contains_login: {}".format("yes" if body_lower.find("login") >= 0 else "no"))
        _safe_log(ctx, "contains_error: {}".format("yes" if body_lower.find("error") >= 0 else "no"))
        _safe_log(ctx, "contains_exception: {}".format("yes" if body_lower.find("exception") >= 0 else "no"))
        _safe_log(ctx, "contains_stacktrace: {}".format("yes" if body_lower.find("traceback") >= 0 or body_lower.find("stack trace") >= 0 else "no"))

        _emit_section(ctx, "send test")
        _run_send_test(ctx)
    except Exception as ex:
        _safe_log(ctx, "[debug-plugin-catch] {}".format(_to_text(ex)))
