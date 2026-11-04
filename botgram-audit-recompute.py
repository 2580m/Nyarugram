"""Read-only reproduction of the independent Botgram performance audit.
Requires the existing D:/BotgramBuild/tmp evidence; never starts a server.
"""
import json
import re
import pathlib
import hashlib
import statistics

root = pathlib.Path('D:/BotgramBuild/tmp')
p = root / 'p6-http-runs-fe35c2158a3040cfa48bbfb29bc72e54'
raw = json.loads((p / 'benchmark-tls-samples.json').read_text(encoding='utf-8-sig'))
summary = json.loads((p / 'benchmark-tls.json').read_text(encoding='utf-8-sig'))
cells = {}
for row in raw:
    if 'requests' not in row or row['Warmup']:
        continue
    key = (row['round'], row['concurrency'], row['operation'], row['protocol'])
    cell = cells.setdefault(key, {'times': [], 'cpu': 0, 'cycles': 0, 'errors': 0})
    cell['times'] += [v['httpMilliseconds'] for v in row['requests']]
    cell['cpu'] += row['batchCpuMilliseconds']
    cell['cycles'] += row['batchCpuCycles']
    cell['errors'] += sum(v['status'] != 200 for v in row['requests'])
assert len(cells) == 36
for key, cell in cells.items():
    cell['p95'] = sorted(cell['times'])[189]
    match = next(s for s in summary['results'] if
                 (s['round'], s['concurrency'], s['operation'], s['protocol']) == key)
    assert len(cell['times']) == 200
    assert abs(cell['p95'] - match['p95Ms']) < 1e-8
    assert abs(cell['cpu'] - match['cpuMilliseconds']) < 1e-8
pairs = []
for key, legacy in cells.items():
    if key[-1] != 'json':
        continue
    binary = cells[key[:-1] + ('protobuf',)]
    pairs.append({'cell': key[:-1], 'jsonP95': legacy['p95'], 'pbP95': binary['p95'],
                  'latencyRatio': binary['p95'] / legacy['p95'],
                  'cpuRatio': binary['cpu'] / legacy['cpu'],
                  'cyclesRatio': binary['cycles'] / legacy['cycles']})
print('FORMAL', json.dumps({'pairs': pairs,
      'latencyFailures': sum(v['latencyRatio'] > 1.1 for v in pairs),
      'cpuFailures': sum(v['cpuRatio'] > 1.1 for v in pairs),
      'errors': sum(c['errors'] for c in cells.values())}))

log = (root / 'p6-next-diagnostic.log').read_text(encoding='utf-8-sig')
stages = []
for match in re.finditer(r'Backend started: pid=(\d+);[^\n]*data=(.*tls-bench-r(\d+)-c(\d+)-(\w+)-(json|protobuf)\.json)', log):
    pid, path, round_number, concurrency, operation, protocol = match.groups()
    source = pathlib.Path(path.strip()).parent / f'backend-{pid}.stdout.log'
    rows = []
    for line in source.read_text(encoding='utf-8-sig').splitlines():
        parsed = re.search(r'RPC_STAGES trace=(\S+) elapsed_ms=([\d.]+) stages=(.*?) process_gc0_delta=(\d+) process_gc1_delta=(\d+) process_gc2_delta=(\d+) pending=(\d+)', line)
        if not parsed:
            continue
        trace, elapsed, tags, gc0, gc1, gc2, pending = parsed.groups()
        values = {k: float(v) for k, v in re.findall(r'diag\.([a-z_]+_ms)=([\d.]+)', tags)}
        rows.append({'cell': [int(round_number), int(concurrency), operation, protocol],
                     'trace': trace, 'elapsed': float(elapsed), 'stages': values,
                     'gc': [int(gc0), int(gc1), int(gc2)], 'source': str(source)})
    assert len(rows) == 220, (source, len(rows))
    stages += rows[20:]
assert len(stages) == 7200
output = []
for protocol in ['json', 'protobuf']:
    rows = [v for v in stages if v['cell'][-1] == protocol]
    slowest = sorted(rows, key=lambda v: v['elapsed'])[-180:]
    def stats(records):
        ratios = [(v['stages'].get('lock_ms', 0) + v['stages'].get('flush_ms', 0)) / v['elapsed'] for v in records]
        return {'n': len(records), 'medianLockFlushShare': statistics.median(ratios),
                'overHalf': sum(v > .5 for v in ratios),
                'over80pct': sum(v > .8 for v in ratios)}
    output.append({'protocol': protocol, 'all': stats(rows),
                   'slowest5pct': stats(slowest),
                   'slowest': max(rows, key=lambda v: v['elapsed'])})
print('DIAGNOSTIC', json.dumps(output))
print('HASHES', json.dumps({str(p / name): hashlib.sha256((p / name).read_bytes()).hexdigest()
      for name in ['benchmark-tls.json', 'benchmark-tls-samples.json']}))
