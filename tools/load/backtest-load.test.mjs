import assert from 'node:assert/strict';
import test from 'node:test';
import { runLoad } from './backtest-load.mjs';

const endpoint = 'http://127.0.0.1:8080';
const token = 'fixture-token-only';
const metrics = { endingEquity: '10075.00', totalReturn: '0.0075', maxDrawdown: '0', fillCount: 1 };
const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function settings(fetchImpl, overrides = {}) {
  return { baseUrl: endpoint, token, jobs: 6, concurrency: 2, retries: 2, pollIntervalMs: 1,
    requestTimeoutMs: 100, jobTimeoutMs: 3000, fetchImpl, ...overrides };
}

function accepted(id) {
  return Response.json({ id, status: 'QUEUED' }, {
    status: 202, headers: { Location: `/v1/backtests/${id}` },
  });
}

test('bounds simultaneous workload and reports monotonic latency percentiles', async () => {
  let active = 0;
  let maximum = 0;
  let submitted = 0;
  const fetchImpl = async (url, options) => {
    const path = new URL(url).pathname;
    assert.equal(new Headers(options.headers).get('authorization'), `Bearer ${token}`);
    active++;
    maximum = Math.max(maximum, active);
    try {
      await delay(5);
      if (options.method === 'POST') {
        assert.equal(path, '/v1/backtests');
        assert.equal(options.body, 'demo-buy-hold');
        return accepted(`00000000-0000-0000-0000-${String(++submitted).padStart(12, '0')}`);
      }
      assert.match(path, /^\/v1\/backtests\/[0-9a-f-]+$/);
      return Response.json({ id: path.split('/').at(-1), status: 'SUCCEEDED', result: metrics });
    } finally {
      active--;
    }
  };

  const report = await runLoad(settings(fetchImpl));
  assert.equal(maximum, 2);
  assert.equal(submitted, 6);
  assert.equal(report.jobs, 6);
  assert.equal(report.succeeded, 6);
  assert.equal(report.failed, 0);
  assert.equal(report.queueSaturation, 0);
  assert.equal(report.statusCounts[202], 6);
  assert.equal(report.statusCounts[200], 6);
  const { p50, p95, p99 } = report.latencyMs;
  assert.ok(Number.isFinite(p50) && p50 >= 0);
  assert.ok(Number.isFinite(p95) && p95 >= p50);
  assert.ok(Number.isFinite(p99) && p99 >= p95);
});

test('keeps partial successes and counts only queue-full retries', async () => {
  let submitted = 0;
  const ids = ['00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002'];
  const fetchImpl = async (url, options) => {
    if (options.method === 'POST') {
      switch (++submitted) {
        case 1: return new Response(null, { status: 503 });
        case 2: return accepted(ids[0]);
        case 3: return new Response('private diagnostics', { status: 400 });
        case 4: return accepted(ids[1]);
        default: assert.fail('unexpected extra POST');
      }
    }
    const id = new URL(url).pathname.split('/').at(-1);
    if (id === ids[0]) return Response.json({ id, status: 'SUCCEEDED', result: metrics });
    if (id === ids[1]) return Response.json({ id, status: 'FAILED' });
    assert.fail('unexpected task GET');
  };

  const report = await runLoad(settings(fetchImpl, { jobs: 3, concurrency: 1 }));
  assert.equal(report.succeeded, 1);
  assert.equal(report.failed, 2);
  assert.equal(report.queueSaturation, 1);
  assert.equal(report.retries, 1);
  assert.equal(report.statusCounts[503], 1);
  assert.equal(report.statusCounts[400], 1);
  assert.equal(Object.values(report.errors).reduce((total, value) => total + value, 0), 2);
  assert.doesNotMatch(JSON.stringify(report), /private diagnostics|fixture-token-only/);
});

test('rejects non-loopback or ambiguous targets and invalid bounds before fetching', async () => {
  const fetchImpl = () => assert.fail('invalid configuration reached fetch');
  for (const baseUrl of ['https://127.0.0.1:8080', 'http://example.com',
    'http://127.0.0.1.example.com:8080', 'http://u:p@127.0.0.1:8080',
    'http://127.0.0.1:8080/api', 'http://127.0.0.1:8080/?token=secret']) {
    await assert.rejects(runLoad(settings(fetchImpl, { baseUrl })), Error);
  }
  await assert.rejects(runLoad(settings(fetchImpl, { jobs: 0 })), Error);
  await assert.rejects(runLoad(settings(fetchImpl, { concurrency: 0 })), Error);
});
