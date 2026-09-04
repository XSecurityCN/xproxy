# Demo: fuzz one JSON value placeholder in request body.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 60
FUZZ_PAYLOADS = [
    "' OR '1'='1",
    '" OR "1"="1',
    "../../../../etc/passwd",
    "<script>alert(1)</script>",
    "${jndi:ldap://127.0.0.1/a}",
]
MAX_ERROR_STATUS = 500
ERROR_KEYWORDS = ["exception", "stack", "trace"]
KEEP_STATUS_CODES = [200, 201, 202]


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    for payload in FUZZ_PAYLOADS:
        engine.queue(target.req, payload, label="json")


# Keep server errors and responses that look like stack traces.
def handle_response(req, interesting):
    if req.status >= MAX_ERROR_STATUS:
        table.add(req)
        return

    body = req.response.lower()
    if any(keyword in body for keyword in ERROR_KEYWORDS):
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES and req.length > 0:
        table.add(req)
