# Demo: fuzz common path segments using one {{placeholder}} marker.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 50
COMMON_PATHS = [
    "admin",
    "login",
    "dashboard",
    "api",
    "backup",
    "debug",
    "swagger",
    ".git/config",
    "robots.txt",
    "sitemap.xml",
]
KEEP_STATUS_CODES = [200, 201, 202, 204, 301, 302, 307, 401, 403]
IGNORE_LENGTHS = [0, 11, 19]


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    for path in COMMON_PATHS:
        engine.queue(target.req, path, label="path")


# Keep status-code outliers and body-length anomalies.
def handle_response(req, interesting):
    if req.status in KEEP_STATUS_CODES:
        table.add(req)
        return

    if req.length not in IGNORE_LENGTHS:
        table.add(req)
