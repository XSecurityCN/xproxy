# Demo: show decorator-based filtering with status and size constraints.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 80
START_VALUE = 1
END_VALUE_EXCLUSIVE = 51
MATCH_STATUS_CODES = (200, 201, 202, 301, 302, 401, 403)
FILTER_SIZE_VALUES = (0,)


def queue_requests(target, wordlists):
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
        callback=handle_response,
    )

    for n in range(START_VALUE, END_VALUE_EXCLUSIVE):
        engine.queue(target.req, str(n), label="decorator")


@MatchStatus(*MATCH_STATUS_CODES)
@FilterSize(*FILTER_SIZE_VALUES)
# Keep responses that pass both decorators.
def handle_response(req, interesting):
    table.add(req)
