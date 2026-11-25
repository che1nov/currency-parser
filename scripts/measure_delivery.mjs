import assert from 'node:assert/strict';
import {writeFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from '../.local-tools/node_modules/playwright/index.mjs';

const base = process.env.DELIVERY_BASE_URL;
const control = process.env.DELIVERY_CONTROL_URL;
if (!base || !control) throw new Error('Run scripts/measure_delivery.py');
const out = new URL('../results/delivery/', import.meta.url);
const images = new URL('../report/images/', import.meta.url);
await mkdir(out, {recursive:true}); await mkdir(images, {recursive:true});
const browser = await chromium.launch({headless:true,
  ...(process.env.BROWSER_EXECUTABLE ? {executablePath:process.env.BROWSER_EXECUTABLE} : {})});
const page = await browser.newPage({viewport:{width:1280,height:1000}, locale:'ru-RU', timezoneId:'Europe/Samara'});
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const cdp = await page.context().newCDPSession(page);
await cdp.send('Network.enable');
let network = [];
let recordNetwork = false;
const track = (type, event) => { if (recordNetwork) network.push({type, ...event}); };
cdp.on('Network.requestWillBeSent', event => track('httpRequest', {
  url:event.request.url, method:event.request.method, timestamp:event.timestamp, requestId:event.requestId}));
cdp.on('Network.responseReceived', event => track('httpResponse', {
  status:event.response.status, url:event.response.url, requestId:event.requestId,
  contentEncoding:event.response.headers['Content-Encoding'] || null}));
cdp.on('Network.webSocketWillSendHandshakeRequest', event => track('wsHandshake', {timestamp:event.timestamp}));
cdp.on('Network.webSocketHandshakeResponseReceived', event => track('wsUpgrade', {
  status:event.response.status, headers:event.response.headers}));
cdp.on('Network.webSocketFrameReceived', event => track('wsFrame', {
  opcode:event.response.opcode, bytes:Buffer.byteLength(event.response.payloadData), timestamp:event.timestamp}));

const measurements = [];
const warmups = [];
let expectedRates;
async function run(mode, delayMs, warmup = false) {
  await page.selectOption('#mode', mode);
  await page.selectOption('#delay', String(delayMs));
  await page.fill('#date', '2024-03-05');
  await fetch(control+'/start');
  network = []; recordNetwork = true;
  await page.click('#start');
  await page.waitForFunction(() => window.lastMeasurement !== null, null, {timeout:65000});
  const result = await page.evaluate(() => window.lastMeasurement);
  await page.waitForTimeout(150); // Include the WebSocket close exchange, outside measured UI time.
  recordNetwork = false;
  const wire = await (await fetch(control+'/stop')).json();
  const record = {...result, wire, network};
  (warmup ? warmups : measurements).push(record);
  await writeFile(new URL('measurements.json', out), JSON.stringify({browser:browser.version(), warmups, measurements}, null, 2)+'\n');
  assert.equal(errors.length, 0, errors.join('\n'));
  assert.equal(result.state, 'SUCCEEDED', JSON.stringify(result));
  assert.equal(result.result.rates.length, 5);
  const rates = result.result.rates.map(({fetchedAt, ...rate}) => rate);
  if (expectedRates) assert.deepEqual(rates, expectedRates); else expectedRates = rates;
  const requests = network.filter(e => e.type === 'httpRequest' && e.url.startsWith(base));
  assert.equal(requests.filter(e => e.method === 'POST').length, 1);
  assert.equal(requests.filter(e => e.method === 'GET').length, result.pollRequests);
  assert.equal(result.httpRequests, 1 + result.pollRequests + (mode === 'websocket' ? 1 : 0));
  if (mode === 'websocket') {
    assert.equal(result.wsMessages, 1);
    assert.equal(network.filter(e => e.type === 'wsUpgrade' && e.status === 101).length, 1);
    assert.equal(network.filter(e => e.type === 'wsFrame' && e.opcode === 1).length, 1);
    const upgrade = network.find(e => e.type === 'wsUpgrade');
    assert.ok(!Object.keys(upgrade.headers).some(key => key.toLowerCase() === 'sec-websocket-extensions'));
  } else assert.equal(result.wsMessages, 0);
  assert.ok(network.filter(e => e.type === 'httpResponse').every(e => !e.contentEncoding));
  if (delayMs === 5000) assert.ok(result.durationMs >= 5000);
  assert.ok(wire.totalBytes > result.jsonBytes);
  console.log(`${warmup ? 'warmup' : 'measure'} ${mode} delay=${delayMs}: ${result.durationMs}ms, HTTP=${result.httpRequests}, JSON=${result.jsonBytes}B, wire=${wire.totalBytes}B`);
}
try {
  await page.goto(base, {waitUntil:'networkidle'});
  await run('polling', 0, true);
  await run('websocket', 0, true);
  for (const delay of [0, 5000]) {
    for (let repeat = 0; repeat < Number(process.env.DELIVERY_REPETITIONS || 3); repeat++) {
      for (const mode of repeat % 2 ? ['websocket','polling'] : ['polling','websocket']) await run(mode, delay);
    }
  }
  await page.screenshot({path:fileURLToPath(new URL('delivery-comparison.png', images)), fullPage:true});
  // Separate UI failure check; does not enter the measurements above.
  await page.route('**/api/jobs', route => route.fulfill({status:503, contentType:'application/problem+json',
    body:JSON.stringify({detail:'Тест: очередь заданий заполнена'})}));
  await page.click('#start');
  await page.waitForFunction(() => window.lastMeasurement?.state === 'ERROR');
  assert.equal(await page.locator('#start').isEnabled(), true);
  assert.match(await page.locator('#status').innerText(), /очередь/);
  await page.unroute('**/api/jobs');
  const checks = {pageErrors:errors, successfulOperations:measurements.length,
    identicalRates:true, httpCountsMatchDevTools:true, oneWsResultPerOperation:true,
    artificialDelayVerified:true, compressionDisabled:true, uiRecoversAfterHttpError:true,
    unit:'wire bytes include HTTP and WebSocket framing, not TCP/IP'};
  await writeFile(new URL('browser-checks.json', out), JSON.stringify(checks, null, 2)+'\n');
} finally { await browser.close(); }
