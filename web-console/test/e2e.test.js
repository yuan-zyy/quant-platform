import assert from 'node:assert/strict'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { createBacktestClient } from '../src/backtests.js'

const baseUrl = process.env.QUANT_CONSOLE_TEST_URL
const token = process.env.QUANT_DEMO_TOKEN

test('console client submits, inspects and cancels tasks against the Java demo', {
  skip: !baseUrl,
  timeout: 20_000,
}, async () => {
  assert.ok(token, 'QUANT_DEMO_TOKEN is required for the demo integration test')
  const client = createBacktestClient(token, { baseUrl })
  const submitted = await client.submit('demo-buy-hold')
  assert.ok(submitted.id)
  let completed = await client.get(submitted.id)
  while (completed.status === 'QUEUED' || completed.status === 'RUNNING') {
    await delay(20)
    completed = await client.get(submitted.id)
  }
  assert.equal(completed.status, 'SUCCEEDED')
  assert.equal(completed.result.fillCount, 1)

  const active = await client.submit('demo-cancel')
  assert.equal((await client.cancel(active.id)).status, 'CANCELED')
  assert.equal((await client.get(active.id)).status, 'CANCELED')
})
