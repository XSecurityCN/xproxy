# pyright: reportUndefinedVariable=false

import re
import traceback
import sys

try:
    unicode
except NameError:
    unicode = str

try:
    _setdefaultencoding = getattr(sys, "setdefaultencoding", None)
    if _setdefaultencoding is not None:
        _setdefaultencoding("utf-8")
except:
    pass


def _xproxy_register_matcher(func, matcher):
    matchers = getattr(func, "__xproxy_matchers", None)
    if matchers is None:
        matchers = []
        setattr(func, "__xproxy_matchers", matchers)
    matchers.append(matcher)
    return func


def MatchStatus(*codes):
    expected = set([int(c) for c in codes])

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: int(ctx.response.status) in expected)

    return decorator


def FilterStatus(*codes):
    blocked = set([int(c) for c in codes])

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: int(ctx.response.status) not in blocked)

    return decorator


def MatchMimeType(*mime_types):
    expected = set([unicode(x).strip().lower() for x in mime_types])

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: unicode(ctx.response.mime_type).strip().lower() in expected)

    return decorator


def MatchMethod(*methods):
    expected = set([unicode(x).strip().upper() for x in methods])

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: unicode(ctx.request.method).strip().upper() in expected)

    return decorator


def MatchHostRegex(pattern):
    reg = re.compile(unicode(pattern), re.IGNORECASE)

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: reg.search(unicode(ctx.request.host) or "") is not None)

    return decorator


def MatchPathRegex(pattern):
    reg = re.compile(unicode(pattern), re.IGNORECASE)

    def decorator(func):
        return _xproxy_register_matcher(func, lambda ctx: reg.search(unicode(ctx.request.path) or "") is not None)

    return decorator


def _xproxy_dispatch(handler_name, ctx):
    handler = globals().get(handler_name)
    if handler is None:
        return

    matchers = getattr(handler, "__xproxy_matchers", [])
    for matcher in matchers:
        try:
            if not matcher(ctx):
                return
        except Exception as ex:
            try:
                ctx.log("[matcher-error] " + unicode(ex))
            except:
                pass
            return

    try:
        handler(ctx)
    except BaseException as ex:
        try:
            ctx.log("[handler-error] " + unicode(ex))
            tb = traceback.format_exc()
            if tb:
                for line in tb.splitlines():
                    if len(line.strip()) == 0:
                        continue
                    ctx.log("[handler-trace] " + unicode(line))
        except:
            pass


def __xproxy_dispatch_proxy_message(ctx):
    _xproxy_dispatch("on_proxy_http_message", ctx)


def __xproxy_dispatch_http_message(ctx):
    _xproxy_dispatch("on_http_message", ctx)


def __xproxy_dispatch_before_request(ctx):
    _xproxy_dispatch("on_before_request", ctx)


def __xproxy_dispatch_after_request(ctx):
    _xproxy_dispatch("on_after_request", ctx)
