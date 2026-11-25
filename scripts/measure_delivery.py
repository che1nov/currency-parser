#!/usr/bin/env python3
"""Run the real app and a byte-counting TCP relay, then measure its page in Chromium."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'results/delivery'

class Relay:
    def __init__(self, target_port):
        self.target_port = target_port
        self.active = False
        self.sent = self.received = 0
        self.events = []
        self.connections = 0

    async def client(self, reader, writer):
        self.connections += 1
        connection = self.connections
        try:
            upstream_reader, upstream_writer = await asyncio.open_connection('127.0.0.1', self.target_port)
        except OSError:
            writer.close()
            return

        async def copy(source, target, direction):
            try:
                while data := await source.read(65536):
                    if self.active:
                        if direction == 'upload': self.sent += len(data)
                        else: self.received += len(data)
                        self.events.append({'connection': connection, 'direction': direction,
                                            'bytes': len(data), 'offsetMs': round((time.monotonic()-self.started)*1000, 3)})
                    target.write(data)
                    await target.drain()
            except (ConnectionError, OSError):
                pass
            finally:
                target.close()
        await asyncio.gather(copy(reader, upstream_writer, 'upload'), copy(upstream_reader, writer, 'download'))

    async def control(self, reader, writer):
        try:
            header = await reader.readuntil(b'\r\n\r\n')
            path = header.split(b' ')[1]
            if path == b'/start':
                self.sent = self.received = 0
                self.events = []
                self.started = time.monotonic()
                self.active = True
                result = {'started': True}
            elif path == b'/stop':
                self.active = False
                result = {'uploadBytes': self.sent, 'downloadBytes': self.received,
                          'totalBytes': self.sent+self.received, 'events': self.events}
            else:
                result = {'error': 'unknown command'}
            body = json.dumps(result).encode()
            writer.write(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: '
                         + str(len(body)).encode() + b'\r\n\r\n' + body)
            await writer.drain()
        finally:
            writer.close()

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', default=os.environ.get('BROWSER_EXECUTABLE', ''))
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--port', type=int, default=18084)
    args = parser.parse_args()
    if args.repetitions < 1: parser.error('repetitions must be positive')
    version = ET.parse(ROOT/'pom.xml').findtext('{http://maven.apache.org/POM/4.0.0}version')
    jar = ROOT/'target'/f'currency-parser-{version}.jar'
    if not jar.exists(): raise RuntimeError('Run mvn verify first')
    OUT.mkdir(parents=True, exist_ok=True)
    relay = Relay(args.port)
    proxy = await asyncio.start_server(relay.client, '127.0.0.1', args.port+1)
    admin = await asyncio.start_server(relay.control, '127.0.0.1', args.port+2)
    try:
        with tempfile.TemporaryDirectory(prefix='currency-delivery-') as temp, (OUT/'application.log').open('w') as log:
            command = ['java', '-Xms128m', '-Xmx256m', '-jar', str(jar),
                       f'--server.port={args.port}', '--server.address=127.0.0.1',
                       '--collector.scheduling-enabled=false',
                       '--spring.datasource.url=jdbc:h2:mem:delivery;DB_CLOSE_DELAY=-1',
                       f'--collector.files-directory={temp}']
            app = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if app.poll() is not None: raise RuntimeError('Application failed; see results/delivery/application.log')
                    try:
                        def ready():
                            with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/api/currencies', timeout=1) as response:
                                return response.status == 200
                        if await asyncio.to_thread(ready): break
                    except OSError: pass
                    await asyncio.sleep(.2)
                else: raise RuntimeError('Application startup timed out')
                env = {**os.environ, 'DELIVERY_BASE_URL': f'http://127.0.0.1:{args.port+1}',
                       'DELIVERY_CONTROL_URL': f'http://127.0.0.1:{args.port+2}',
                       'DELIVERY_REPETITIONS': str(args.repetitions), 'BROWSER_EXECUTABLE': args.browser}
                child = await asyncio.create_subprocess_exec('node', 'scripts/measure_delivery.mjs', cwd=ROOT, env=env)
                code = await child.wait()
                if code: raise RuntimeError(f'Browser measurement exited with {code}')
                environment = {'measuredAt': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'os': platform.platform(),
                               'java': subprocess.run(['java','-version'], capture_output=True, text=True).stderr.strip(),
                               'node': subprocess.check_output(['node','--version'], text=True).strip(),
                               'jvmArgs': command[1:3], 'repetitions': args.repetitions,
                               'source': 'Live CBR XML, 2024-03-05 and 2024-03-04',
                               'wireScope': 'Bidirectional TCP payload at relay: HTTP/1.1 headers and bodies, chunk framing, WebSocket handshake and frames including close. Excludes TCP/IP/TLS, page load, CBR traffic.'}
                (OUT/'environment.json').write_text(json.dumps(environment, ensure_ascii=False, indent=2)+'\n')
            finally:
                app.send_signal(signal.SIGTERM)
                try: await asyncio.to_thread(app.wait, 25)
                except subprocess.TimeoutExpired:
                    app.kill(); await asyncio.to_thread(app.wait)
    finally:
        proxy.close(); admin.close()
        await proxy.wait_closed(); await admin.wait_closed()

if __name__ == '__main__':
    asyncio.run(main())
