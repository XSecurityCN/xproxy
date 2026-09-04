# Find more example scripts at https://github.com/XSecurityCN/xproxy/blob/master/resources/examples/default.py
def queue_requests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrent_connections=5,
                           requests_per_connection=100,
                           pipeline=False,
                           engine=Engine.HTTP
                           )

    for x in range(10, 20):
        engine.queue(target.req, x)

    for word in open('/usr/share/dict/words'):
        engine.queue(target.req, word.rstrip())


def handle_response(req, interesting):
    table.add(req)
