# Intruder Script Demos

These demos target the current XProxy Intruder runtime (`IntruderRuntime.py`).

## Script rules (quick)

- Required entrypoint: `queue_requests(target, wordlists)`
- Placeholder syntax: use named placeholders like `{{username}}`, `{{path}}`
- Optional callbacks: `handle_response(req, interesting)` and `completed(results)`
- Provided globals:
  - `target.req` / `target.rawreq` / `target.endpoint` / `target.base_input`
  - `wordlists.bruteforce`, `wordlists.observed_words`, `wordlists.clipboard`
  - `table` (same as output handler), `handler`, `requests`, `host`
- Request engine:
  - `RequestEngine(...)`
  - `engine.queue(template, payloads=None, learn=0, callback=None, gate=None, label="", pause_before=0, pause_time=1000, pause_marker=[], delay=0, endpoint=None, fix_content_length=True)`
- Response fields commonly used in filtering:
  - `req.status`, `req.length`, `req.wordcount`, `req.response`

## Included demos

- `01_path_discovery.py`: path brute-force (status/size filtering)
- `02_header_auth_bypass.py`: auth header bypass payloads
- `03_json_value_fuzz.py`: JSON body value fuzzing
- `04_rate_limit_probe.py`: rate-limit and latency probing
- `05_race_gate_attack.py`: gated race attack baseline
