import assert from 'node:assert/strict'
import test from 'node:test'
import { BacktestApiError, createBacktestClient } from '../src/backtests.js'

test('submits scenario text and retrieves or cancels tasks with authorization', async () => {
  const calls = []
  const snapshot = { id: 'task-id', status: 'QUEUED', result: null }
  const api = createBacktestClient('operator-token', {
    baseUrl: 'http://127.0.0.1:8080',
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return Response.json(snapshot)
    },
  })
  const signal = new AbortController().signal
  assert.deepEqual(await api.submit('demo-buy-hold', signal), snapshot)
  await api.get('task-id')
  await api.cancel('task-id')
  assert.deepEqual(calls.map(call => call.options.method), ['POST', 'GET', 'DELETE'])
  assert.deepEqual(calls.map(call => call.url), [
    'http://127.0.0.1:8080/v1/backtests',
    'http://127.0.0.1:8080/v1/backtests/task-id',
    'http://127.0.0.1:8080/v1/backtests/task-id',
  ])
  assert.equal(calls[0].options.body, 'demo-buy-hold')
  assert.equal(calls[0].options.signal, signal)
  assert.equal(calls[0].options.headers['Content-Type'], 'text/plain; charset=UTF-8')
  assert.equal(calls[1].options.body, undefined)
  for (const { options } of calls) {
    assert.equal(options.headers.Authorization, 'Bearer operator-token')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.redirect, 'error')
  }
})

test('surfaces API failures and malformed success responses', async () => {
  const denied = createBacktestClient('token', {
    fetchImpl: async () => Response.json({ error: 'unauthorized' }, { status: 401 }),
  })
  await assert.rejects(denied.get('task-id'), error =>
    error instanceof BacktestApiError && error.status === 401 && error.code === 'unauthorized')
  const malformed = createBacktestClient('token', {
    fetchImpl: async () => new Response('unexpected'),
  })
  await assert.rejects(malformed.get('task-id'), { code: 'invalid_response' })
})
