# Demo: probe throttling behavior and latency spikes.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 100
MAX_QUEUE_SIZE = 1000
TOTAL_REQUESTS = 120
THROTTLE_STATUS_CODES = [429, 503]
SLOW_RESPONSE_US = 1500


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
        max_queue_size=MAX_QUEUE_SIZE,
    )

    for i in range(0, TOTAL_REQUESTS):
        engine.queue(target.req, str(i), label="rl")


# Keep explicit rate-limit responses and slow responses.
def handle_response(req, interesting):
    if req.status in THROTTLE_STATUS_CODES:
        table.add(req)
        return

    if req.time >= SLOW_RESPONSE_US:
        table.add(req)
