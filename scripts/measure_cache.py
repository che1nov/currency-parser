#!/usr/bin/env python3
"""Two real app containers, shared H2 and Redis; curl timings plus actual DB-read counters."""
import argparse
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'results/cache'
DATE = '2024-03-05'
QUERY = '/answer?date=' + DATE + '&currency=USD&sort=rate&direction=asc'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pairs', type=int, default=20)
    parser.add_argument('--port-a', type=int, default=18091)
    parser.add_argument('--port-b', type=int, default=18092)
    parser.add_argument('--skip-build', action='store_true')
    args = parser.parse_args()
    if args.pairs < 3: parser.error('Use at least 3 pairs')
    OUT.mkdir(parents=True, exist_ok=True)
    project = 'rates-cache-' + uuid.uuid4().hex[:10]
    env = {**os.environ, 'CACHE_PORT_A': str(args.port_a), 'CACHE_PORT_B': str(args.port_b)}
    command = ['docker', 'compose', '-p', project, '-f', str(ROOT/'compose.cache.yml')]
    urls = {'A': f'http://127.0.0.1:{args.port_a}', 'B': f'http://127.0.0.1:{args.port_b}'}
    results = {'project': project, 'date': DATE, 'query': QUERY, 'phases': []}

    with (OUT/'docker.log').open('w') as log:
        def compose(*arguments, capture=False):
            call = subprocess.run(command + list(arguments), cwd=ROOT, env=env, text=True,
                                  stdout=subprocess.PIPE if capture else log, stderr=log, check=True)
            return call.stdout if capture else None

        def api(instance, path='/api/lab/cache', method='GET', body=None):
            payload = None if body is None else json.dumps(body).encode()
            request = urllib.request.Request(urls[instance]+path, data=payload, method=method,
                                             headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=25) as response:
                data = response.read()
                return json.loads(data) if data else None

        def counters():
            return {instance: api(instance)['databaseReads'] for instance in urls}

        def clear():
            for instance in urls: api(instance, method='DELETE')

        def collect(instance='A'):
            result = api(instance, '/api/collect', 'POST', {'date': DATE})
            assert result['state'] == 'SUCCEEDED' and result['savedCount'] == 5, result
            return result

        def ready():
            for _ in range(120):
                try:
                    for instance in urls: api(instance)
                    return
                except OSError: time.sleep(.5)
            raise RuntimeError('Apps did not become ready; see results/cache/docker.log')

        def timed(instance):
            with tempfile.NamedTemporaryFile() as body:
                result = subprocess.run(['curl','--silent','--show-error','--fail','--max-time','20',
                                         '--output',body.name,'--write-out','%{time_total}',urls[instance]+QUERY],
                                        check=True, capture_output=True, text=True)
                rates = json.loads(Path(body.name).read_text())
            assert len(rates) == 1 and rates[0]['currency'] == 'USD', rates
            return {'instance': instance, 'milliseconds': round(float(result.stdout)*1000, 3), 'rates': rates}

        def persist():
            (OUT/'measurements.json').write_text(json.dumps(results, ensure_ascii=False, indent=2)+'\n')

        try:
            if not args.skip_build: compose('build', 'app-a')
            for backend in ['caffeine', 'redis']:
                env.update(CACHE_TYPE=backend, CACHE_TTL_SECONDS='600')
                compose('up', '-d', '--no-build')
                ready()
                collect()
                phase = {'backend': backend, 'instances': {i:api(i) for i in urls}, 'pairs': []}
                assert all(backend in info['cacheManager'].lower() for info in phase['instances'].values())
                # Warm up MVC/JIT, Redis serialization and both database connection pools.
                for _ in range(15):
                    clear(); timed('A'); timed('A'); timed('B')
                clear()
                before = counters()
                first_a, second_a, first_b, second_b = timed('A'), timed('A'), timed('B'), timed('B')
                after = counters()
                delta = {i:after[i]-before[i] for i in urls}
                assert delta == {'A':1, 'B':1 if backend == 'caffeine' else 0}, delta
                assert first_a['rates'] == first_b['rates'] == second_a['rates'] == second_b['rates']
                phase['twoInstances'] = {'before':before, 'after':after, 'databaseReadsDelta':delta,
                                         'calls':[first_a, second_a, first_b, second_b]}
                for pair in range(args.pairs):
                    clear()
                    count = counters()['A']
                    miss = timed('A')
                    after_miss = counters()['A']
                    hit = timed('A')
                    after_hit = counters()['A']
                    assert after_miss-count == 1 and after_hit-after_miss == 0
                    assert miss['rates'] == hit['rates']
                    phase['pairs'].append({'missMs':miss['milliseconds'], 'hitMs':hit['milliseconds'],
                                           'missDbReads':after_miss-count, 'hitDbReads':after_hit-after_miss})
                # Populate two variants on both instances before updating the authoritative database.
                clear()
                old = {i:timed(i)['rates'] for i in urls}
                alternate = '/answer?date='+DATE+'&sort=change&direction=desc'
                old_alternate = {i:api(i, alternate) for i in urls}
                collect()
                before = counters()
                fresh = {i:timed(i)['rates'] for i in urls}
                new_alternate = {i:api(i, alternate) for i in urls}
                after = counters()
                assert fresh['A'][0]['fetchedAt'] != old['A'][0]['fetchedAt']
                if backend == 'redis':
                    assert fresh['B'] == fresh['A'] and new_alternate['B'] == new_alternate['A']
                    assert new_alternate['B'] != old_alternate['B']
                else:
                    assert fresh['B'] == old['B'] and new_alternate['B'] == old_alternate['B']
                phase['invalidation'] = {'databaseReadsDelta':{i:after[i]-before[i] for i in urls},
                    'fetchedAtBefore':{i:old[i][0]['fetchedAt'] for i in urls},
                    'fetchedAtAfter':{i:fresh[i][0]['fetchedAt'] for i in urls},
                    'instanceAUpdated':True, 'instanceBUpdated':backend == 'redis', 'allQueryVariantsChecked':True}
                if backend == 'redis':
                    keys = compose('exec','-T','redis','redis-cli','--scan','--pattern','currency-parser:v1:rates::*',capture=True).splitlines()
                    phase['redisKeys'] = keys
                    phase['redisTtlMs'] = int(compose('exec','-T','redis','redis-cli','PTTL',keys[0],capture=True).strip())
                    assert 500000 < phase['redisTtlMs'] <= 600000
                phase['summary'] = {name: {'medianMs':statistics.median(row[name+'Ms'] for row in phase['pairs']),
                                          'minMs':min(row[name+'Ms'] for row in phase['pairs']),
                                          'maxMs':max(row[name+'Ms'] for row in phase['pairs'])}
                                    for name in ['miss','hit']}
                results['phases'].append(phase)
                persist()
                print(backend, json.dumps(phase['summary']), 'two-instance DB reads:', delta, flush=True)

                # Accelerated TTL check on the same code: restart only apps with a 2-second TTL.
                env['CACHE_TTL_SECONDS'] = '2'
                compose('up','-d','--no-build','app-a','app-b')
                ready(); clear()
                before = counters()
                first = timed('A'); repeat = timed('A')
                middle = counters()
                assert middle['A']-before['A'] == 1
                time.sleep(2.2)
                timed('A')
                after = counters()
                assert after['A']-middle['A'] == 1
                phase['ttlCheck'] = {'configuredSeconds':2, 'sleepSeconds':2.2,
                                     'readsBeforeExpiry':1, 'readsAfterExpiry':1}
                if backend == 'caffeine':
                    # Both local caches eventually converge after an update when their TTL expires.
                    timed('B'); old_b = api('B', QUERY)
                    collect(); time.sleep(2.2)
                    new_b = api('B', QUERY)
                    assert new_b[0]['fetchedAt'] != old_b[0]['fetchedAt']
                    phase['ttlCheck']['otherInstanceRefreshedAfterExpiry'] = True
                persist()
            results['environment'] = {
                'measuredAt':time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'host':platform.platform(),
                'curl':subprocess.check_output(['curl','--version'],text=True).splitlines()[0],
                'docker':subprocess.check_output(['docker','version','--format','{{.Server.Version}}'],text=True).strip(),
                'java':compose('exec','-T','app-a','java','--version',capture=True).strip(),
                'redis':compose('exec','-T','redis','redis-server','--version',capture=True).strip(),
                'h2':'2.3.232, shared TCP server', 'appLimits':{'cpus':1,'memoryMiB':512,'heapMiB':256},
                'images':compose('images','--format','json',capture=True), 'pairsPerBackend':args.pairs,
                'source':'Live CBR XML, historical date; no artificial DB delay'}
            reports = [ET.parse(p).getroot() for p in (ROOT/'target/surefire-reports').glob('TEST-*.xml')]
            results['tests'] = {k:sum(int(r.get(k,0)) for r in reports) for k in ['tests','failures','errors','skipped']}
            assert results['tests']['tests'] and results['tests']['failures'] == results['tests']['errors'] == 0
            persist()
        finally:
            try:
                logs = compose('logs','--no-color',capture=True)
                (OUT/'applications.log').write_text(logs)
            finally:
                # The unique project belongs only to this experiment. No shared containers/volumes are removed.
                compose('down','--remove-orphans')


if __name__ == '__main__':
    main()
