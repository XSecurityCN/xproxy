import org.jjgroup.xproxy.RequestEngine, string, random, time, math, re

def _coerce_text(value, default=""):
    if value is None:
        return default
    try:
        return unicode(value)
    except:
        return default

def report_issue(name, severity="Information", detail="", request=None, response="", confidence="Tentative", remediation="", url="", host="", path="", method="", tags=None):
    if name is None:
        return
    issue_name = _coerce_text(name).strip()
    if len(issue_name) == 0:
        return

    severity = _coerce_text(severity, "Information")
    detail = _coerce_text(detail)
    confidence = _coerce_text(confidence, "Tentative")
    remediation = _coerce_text(remediation)
    url = _coerce_text(url)
    host = _coerce_text(host)
    path = _coerce_text(path)
    method = _coerce_text(method)

    request_raw = ""
    response_raw = ""

    if request is not None:
        try:
            request_raw = request.getRequest()
        except:
            try:
                request_raw = _coerce_text(request)
            except:
                request_raw = ""

        try:
            if request.response is not None:
                response_raw = request.response
        except:
            pass

        if len(method) == 0:
            try:
                first_line = request_raw.split('\n', 1)[0].strip()
                if len(first_line) > 0:
                    method = first_line.split(' ', 1)[0]
            except:
                pass

        if len(path) == 0:
            try:
                first_line = request_raw.split('\n', 1)[0].strip()
                parts = first_line.split(' ')
                if len(parts) >= 2:
                    path = parts[1]
            except:
                pass

    if len(response_raw) == 0 and response is not None:
        try:
            response_raw = _coerce_text(response)
        except:
            response_raw = ""

    tags_csv = ""
    if tags is not None:
        if isinstance(tags, list) or isinstance(tags, tuple):
            values = []
            for entry in tags:
                if entry is None:
                    continue
                token = _coerce_text(entry).strip()
                if len(token) > 0:
                    values.append(token)
            tags_csv = ",".join(values)
        else:
            tags_csv = _coerce_text(tags)

    handler.reportIssue(
        issue_name,
        _coerce_text(severity),
        _coerce_text(detail),
        _coerce_text(request_raw),
        _coerce_text(response_raw),
        _coerce_text(confidence),
        _coerce_text(remediation),
        _coerce_text(url),
        _coerce_text(host),
        _coerce_text(path),
        _coerce_text(method),
        _coerce_text(tags_csv),
        "python"
    )

def MatchRegex(regex):
    m = re.compile(unicode(regex), re.UNICODE|re.DOTALL|re.MULTILINE|re.IGNORECASE)
    def decorator(func):
        def handle_response(req, interesting):
            if m.match(req.response):
                func(req, interesting)
        return handle_response
    return decorator

def MatchStatus(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.status in args:
                func(req, interesting)
        return handle_response
    return decorator

def MatchSize(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.length in args:
                func(req, interesting)
        return handle_response
    return decorator

def MatchSizeRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            if ((req.length >= min) and (req.length <= max)):
                func(req, interesting)
        return handle_response
    return decorator

def MatchWordCount(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.wordcount in args:
                func(req, interesting)
        return handle_response
    return decorator

def MatchWordCountRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            if ((req.wordcount >= min) and (req.wordcount <= max)):
                func(req, interesting)
        return handle_response
    return decorator

def MatchLineCount(*args):
    def decorator(func):
        def handle_response(req, interesting):
            linecount = len(req.response.split('\n'))
            if linecount in args:
                func(req, interesting)
        return handle_response
    return decorator

def MatchLineCountRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            linecount = len(req.response.split('\n'))
            if ((linecount >= min) and (linecount <= max)):
                func(req, interesting)
        return handle_response
    return decorator

def FilterStatus(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.status in args:
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterSize(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.length in args:
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterRegex(regex):
    m = re.compile(unicode(regex), re.UNICODE|re.DOTALL|re.MULTILINE|re.IGNORECASE)
    def decorator(func):
        def handle_response(req, interesting):
            if not m.match(req.response):
                func(req, interesting)
        return handle_response
    return decorator

def FilterSizeRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            if ((req.length >= min) and (req.length <= max)):
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterWordCount(*args):
    def decorator(func):
        def handle_response(req, interesting):
            if req.wordcount in args:
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterWordCountRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            if ((req.wordcount >= min) and (req.wordcount <= max)):
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterLineCount(*args):
    def decorator(func):
        def handle_response(req, interesting):
            linecount = len(req.response.split('\n'))
            if linecount in args:
                return
            func(req, interesting)
        return handle_response
    return decorator

def FilterLineCountRange(min, max):
    def decorator(func):
        def handle_response(req, interesting):
            linecount = len(req.response.split('\n'))
            if ((linecount >= min) and (linecount <= max)):
                return
            func(req, interesting)
        return handle_response
    return decorator

def UniqueWordCount(instances=1):
    def decorator(func):
        def handle_response(req, interesting):
            global CodeWords
            try:
                CodeWords
            except:
                CodeWords = {}

            if "lastreq" in CodeWords:
                currreqs = req.engine.engine.successfulRequests.intValue()
                lastreqs = CodeWords["lastreq"]
                if currreqs < lastreqs:
                    CodeWords = {}
                    CodeWords["lastreq"] = currreqs
            CodeWords["lastreq"] = req.engine.engine.successfulRequests.intValue()

            codeword = str(req.status) + str(req.wordcount)
            if codeword in CodeWords:
                if CodeWords[codeword] >= instances:
                    return
                else:
                    CodeWords[codeword] += 1
            else:
                CodeWords[codeword] = 1
            func(req, interesting)
        return handle_response
    return decorator

def UniqueLineCount(instances=1):
    def decorator(func):
        def handle_response(req, interesting):
            global CodeLines
            try:
                CodeLines
            except:
                CodeLines = {}

            if "lastreq" in CodeLines:
                currreqs = req.engine.engine.successfulRequests.intValue()
                lastreqs = CodeLines["lastreq"]
                if currreqs < lastreqs:
                    CodeLines = {}
                    CodeLines["lastreq"] = currreqs
            CodeLines["lastreq"] = req.engine.engine.successfulRequests.intValue()

            linecount = len(req.response.split('\n'))
            codeline = str(req.status) + str(linecount)
            if codeline in CodeLines:
                if CodeLines[codeline] >= instances:
                    return
                else:
                    CodeLines[codeline] += 1
            else:
                CodeLines[codeline] = 1
            func(req, interesting)
        return handle_response
    return decorator

def UniqueSize(instances=1):
    def decorator(func):
        def handle_response(req, interesting):
            global CodeLength
            try:
                CodeLength
            except:
                CodeLength = {}

            if "lastreq" in CodeLength:
                currreqs = req.engine.engine.successfulRequests.intValue()
                lastreqs = CodeLength["lastreq"]
                if currreqs < lastreqs:
                    CodeLength = {}
                    CodeLength["lastreq"] = currreqs

            CodeLength["lastreq"] = req.engine.engine.successfulRequests.intValue()

            codelen = str(req.status) + str(req.length)
            if codelen in CodeLength:
                if CodeLength[codelen] >= instances:
                    return
                else:
                    CodeLength[codelen] += 1
            else:
                CodeLength[codelen] = 1
            func(req, interesting)
        return handle_response
    return decorator

def mean(data):
    return sum(data)/len(data)

def stddev(data):
    if len(data) == 1:
        return 0
    avg = mean(data)
    base = sum((entry-avg)**2 for entry in data)
    return math.sqrt(base/(len(data)-1))

def randstr(length=12, allow_digits=True):
    candidates = string.ascii_lowercase
    if allow_digits:
        candidates += string.digits
    return ''.join(random.choice(candidates) for x in range(length))

def queue_forever(engine, req):
    # infinitely-running bruteforce (a, b ... aaa, aab etc)
    seed = 0
    while True:
        batch = []
        seed = wordlists.bruteforce.generate(seed, 5000, batch)
        for word in batch:
            engine.queue(target.req, word)

class Engine:
    HTTP = 1
    HTTP2 = 2
    SPIKE = 3

class RequestEngine:

    def __init__(self, endpoint, callback=None, engine=Engine.HTTP, concurrent_connections=50, requests_per_connection=100, pipeline=False, max_queue_size=100, timeout=10, max_retries_per_request=3, idle_timeout=0, read_callback=None, read_size=1024, resume_ssl=True, auto_start=True, explode_on_early_read=False, warm_local_connection=True, fat_packet=False):
        concurrent_connections = int(concurrent_connections)
        requests_per_connection = int(requests_per_connection)

        if not callback:
            callback = handle_response

        if pipeline > 1:
            readFreq = int(pipeline)
        elif pipeline:
            readFreq = requests_per_connection
        else:
            readFreq = 1

        if(engine == Engine.HTTP):
            self.engine = org.jjgroup.xproxy.HttpRequestEngine(endpoint, concurrent_connections, max_queue_size, readFreq, requests_per_connection, max_retries_per_request, idle_timeout, callback, timeout, read_callback, read_size, resume_ssl, explode_on_early_read)
        elif(engine == Engine.HTTP2):
            self.engine = org.jjgroup.xproxy.HTTP2RequestEngine(endpoint, concurrent_connections, max_queue_size, requests_per_connection, max_retries_per_request, idle_timeout, callback, read_callback)
        elif(engine == Engine.SPIKE):
            self.engine = org.jjgroup.xproxy.SpikeEngine(endpoint, concurrent_connections, max_queue_size, requests_per_connection, max_retries_per_request, idle_timeout, callback, read_callback, warm_local_connection, fat_packet)
        else:
            print('Unrecognised engine. Valid engines are Engine.HTTP, Engine.HTTP2, Engine.SPIKE')

        handler.setRequestEngine(self.engine)
        self.engine.setOutput(outputHandler)
        self.user_state = self.engine.userState
        self.auto_start = False
        if auto_start:
            self.auto_start = True
            self.engine.start(5)


    def queue(self, template, payloads=None, learn=0, callback=None, gate=None, label="", pause_before=0, pause_time=1000, pause_marker=[], delay=0, endpoint=None, fix_content_length=True):
        if payloads == None:
            payloads = []
        elif not isinstance(payloads, list):
            payloads = [str(payloads)]
        self.engine.queue(template, payloads, learn, callback, gate, label, pause_before, pause_time, pause_marker, delay, endpoint, self, fix_content_length)


    def open_gate(self, gate):
        self.engine.openGate(gate)

    def apply_setting(self, setting_name, setting_value):
        self.engine.applySetting(setting_name, setting_value)

    def start(self, timeout=5):
        if self.auto_start or self.engine.attackState.get() != 0:
            print('The engine has already started - you no longer need to invoke engine.start() manually. If you prefer to invoke engine.start() manually, set auto_start=False in the constructor')
            return
        self.engine.start(timeout)

    def complete(self, timeout=-1):
        self.engine.showStats(timeout)

    def cancel(self):
        self.engine.cancel()

    def report_issue(self, name, severity="Information", detail="", request=None, response="", confidence="Tentative", remediation="", url="", host="", path="", method="", tags=None):
        report_issue(name, severity, detail, request, response, confidence, remediation, url, host, path, method, tags)

def completed(ignored):
    pass
