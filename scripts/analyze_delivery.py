#!/usr/bin/env python3
"""Summarize repeated browser measurements without mixing in warmup runs."""
import json
from pathlib import Path
from statistics import median
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'results/delivery'
raw = json.loads((OUT/'measurements.json').read_text())
summary = []
for delay in (0, 5000):
    for mode in ('polling', 'websocket'):
        rows = [row for row in raw['measurements'] if row['mode'] == mode and row['delayMs'] == delay]
        assert rows and all(row['state'] == 'SUCCEEDED' for row in rows)
        summary.append({
            'mode': mode, 'delayMs': delay, 'runs': len(rows),
            'durationMedianMs': median(row['durationMs'] for row in rows),
            'durationMinMs': min(row['durationMs'] for row in rows),
            'durationMaxMs': max(row['durationMs'] for row in rows),
            'httpRequests': sorted({row['httpRequests'] for row in rows}),
            'pollRequests': sorted({row['pollRequests'] for row in rows}),
            'jsonBytesMedian': median(row['jsonBytes'] for row in rows),
            'wireUploadMedian': median(row['wire']['uploadBytes'] for row in rows),
            'wireDownloadMedian': median(row['wire']['downloadBytes'] for row in rows),
            'wireTotalMedian': median(row['wire']['totalBytes'] for row in rows),
        })
(OUT/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n')
reports = [ET.parse(path).getroot() for path in (ROOT/'target/surefire-reports').glob('TEST-*.xml')]
assert reports, 'Run mvn verify first'
tests = {key: sum(int(report.get(key, 0)) for report in reports) for key in ('tests', 'failures', 'errors', 'skipped')}
assert tests['failures'] == tests['errors'] == tests['skipped'] == 0
tests['suites'] = [{'name': report.get('name'), 'tests': int(report.get('tests'))} for report in reports]
(OUT/'tests.json').write_text(json.dumps(tests, ensure_ascii=False, indent=2)+'\n')
for row in summary:
    print(row)
print(tests)
