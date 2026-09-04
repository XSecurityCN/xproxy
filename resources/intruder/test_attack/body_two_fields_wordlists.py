# Read one value per line from a wordlist file.
CONCURRENT_CONNECTIONS = 20
REQUESTS_PER_CONNECTION = 100
ACTIONS_WORDLIST_PATH = "resources/intruder/wordlists/actions.txt"
PATHS_WORDLIST_PATH = "resources/intruder/wordlists/paths.txt"
DEFAULT_ACTIONS = ["read", "write", "delete", "admin"]
DEFAULT_PATHS = ["/", "/api/user", "/api/admin", "../../../../etc/passwd"]
KEEP_STATUS_CODES = [200, 201, 202, 401, 403, 404, 409, 429]
ERROR_STATUS_THRESHOLD = 500


def read_wordlist(path, fallback):
    try:
        values = []
        for line in open(path):
            item = line.strip()
            if item:
                values.append(item)
        if values:
            return values
    except:
        pass
    return fallback


def queue_requests(target, wordlists):
    # Demo: fuzz two JSON/body placeholders with two independent wordlists.
    engine = RequestEngine(
        endpoint=target.endpoint,
        concurrent_connections=CONCURRENT_CONNECTIONS,
        requests_per_connection=REQUESTS_PER_CONNECTION,
        pipeline=False,
        engine=Engine.HTTP,
    )

    actions = read_wordlist(ACTIONS_WORDLIST_PATH, DEFAULT_ACTIONS)
    paths = read_wordlist(PATHS_WORDLIST_PATH, DEFAULT_PATHS)

    for action in actions:
        for path in paths:
            engine.queue(target.req, [action, path], label=action + "|" + path)


# Keep errors and key auth/business status responses.
def handle_response(req, interesting):
    if req.status >= ERROR_STATUS_THRESHOLD:
        table.add(req)
        return

    if req.status in KEEP_STATUS_CODES:
        table.add(req)
