# Use this to debug issues with XProxy connecting to sites
# Use this to debug issues with XProxy connecting to sites
def queue_requests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrent_connections=1,
                           requests_per_connection=1,
                           pipeline=False,
                           max_retries_per_request=0,
                           engine=Engine.HTTP
                           )

    engine.queue(target.req)
    engine.queue(target.req)
    engine.queue(target.req)


def handle_response(req, interesting):
    table.add(req)
