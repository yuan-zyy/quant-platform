import { performance } from 'node:perf_hooks'
import { pathToFileURL } from 'node:url'

const UUID = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
const SCENARIO = /^[A-Za-z0-9._-]{1,128}$/
const TOKEN = /^[A-Za-z0-9._~+/\-]+=*$/
const DECIMAL = /^[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/
const ACTIVE = new Set(['QUEUED', 'RUNNING'])

function integer(name, value, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new RangeError(`${name} must be an integer between ${minimum} and ${maximum}`)
  }
}

function loopback(baseUrl) {
  // Compare the raw address first to avoid URL canonicalization accepting ambiguous forms.
  if (typeof baseUrl !== 'string'
      || !/^http:\/\/(?:127\.0\.0\.1|localhost|\[::1\])(?::[0-9]+)?\/?$/.test(baseUrl)) {
    throw new TypeError('baseUrl must be a loopback HTTP origin')
  }
  try {
    return new URL(baseUrl).origin
  } catch {
    throw new TypeError('baseUrl must be a loopback HTTP origin')
  }
}

function count(table, key) {
  table[key] = (table[key] ?? 0) + 1
}

function percentiles(samples) {
  const sorted = samples.sort((a, b) => a - b)
  const at = percentile => Math.round(sorted[Math.ceil(sorted.length * percentile) - 1] * 100) / 100
  return { p50: at(0.50), p95: at(0.95), p99: at(0.99) }
}

class JobFailure extends Error {}

function validMetrics(metrics) {
  return metrics && ['endingEquity', 'totalReturn', 'maxDrawdown']
    .every(key => typeof metrics[key] === 'string' && DECIMAL.test(metrics[key]))
    && Number.isSafeInteger(metrics.fillCount) && metrics.fillCount >= 0
}

export async function runLoad({
  baseUrl = 'http://127.0.0.1:8080', token, scenario = 'demo-buy-hold', jobs = 20, concurrency = 4,
  retries = 3, pollIntervalMs = 100, requestTimeoutMs = 5_000, jobTimeoutMs = 15_000, fetchImpl = fetch,
} = {}) {
  const origin = loopback(baseUrl)
  if (typeof token !== 'string' || token.length > 4096 || !TOKEN.test(token)) {
    throw new TypeError('a valid bearer token is required')
  }
  if (typeof scenario !== 'string' || !SCENARIO.test(scenario)) throw new TypeError('invalid scenario ID')
  integer('jobs', jobs, 1, 2_000)
  integer('concurrency', concurrency, 1, 100)
  integer('retries', retries, 0, 10)
  integer('pollIntervalMs', pollIntervalMs, 1, 10_000)
  integer('requestTimeoutMs', requestTimeoutMs, 1, 60_000)
  integer('jobTimeoutMs', jobTimeoutMs, 1, 300_000)

  const report = { jobs, succeeded: 0, failed: 0, queueSaturation: 0, retries: 0, statusCounts: {}, errors: {} }
  const samples = []
  const seenTasks = new Set()
  let nextJob = 0

  function remaining(deadline) {
    const millis = Math.floor(deadline - performance.now())
    if (millis < 1) throw new JobFailure('job_timeout')
    return millis
  }

  async function pause(deadline) {
    await new Promise(resolve => setTimeout(resolve, Math.min(pollIntervalMs, remaining(deadline))))
  }

  async function request(method, path, deadline) {
    const timeLimit = Math.min(requestTimeoutMs, remaining(deadline))
    const signal = AbortSignal.timeout(timeLimit)
    const headers = { Authorization: `Bearer ${token}`, Accept: 'application/json' }
    if (method === 'POST') headers['Content-Type'] = 'text/plain; charset=UTF-8'
    let response
    try {
      response = await fetchImpl(origin + path, {
        method, headers, redirect: 'error', cache: 'no-store', signal,
        body: method === 'POST' ? scenario : undefined,
      })
    } catch {
      throw new JobFailure(signal.aborted ? 'request_timeout' : 'network_error')
    }
    count(report.statusCounts, response.status)
    const expected = method === 'POST' ? 202 : 200
    if (response.status !== expected) {
      try {
        await response.body?.cancel()
      } catch {
        // The status already determines the outcome.
      }
      return { status: response.status }
    }
    try {
      return { status: response.status, task: await response.json() }
    } catch {
      throw new JobFailure(signal.aborted ? 'request_timeout' : 'invalid_response')
    }
  }

  async function job(deadline) {
    let submission = await request('POST', '/v1/backtests', deadline)
    for (let attempt = 0; submission.status === 503; attempt++) {
      report.queueSaturation++
      if (attempt >= retries) throw new JobFailure('queue_saturated')
      await pause(deadline)
      remaining(deadline)
      report.retries++
      submission = await request('POST', '/v1/backtests', deadline)
    }
    if (submission.status !== 202) throw new JobFailure(`submit_http_${submission.status}`)
    let task = submission.task
    if (typeof task?.id !== 'string' || !UUID.test(task.id)) throw new JobFailure('invalid_task_id')
    const id = task.id
    if (seenTasks.has(id)) throw new JobFailure('duplicate_task_id')
    seenTasks.add(id)

    while (ACTIVE.has(task.status)) {
      await pause(deadline)
      const response = await request('GET', `/v1/backtests/${id}`, deadline)
      if (response.status !== 200) throw new JobFailure(`poll_http_${response.status}`)
      task = response.task
      if (task?.id !== id) throw new JobFailure('invalid_task_id')
    }
    if (task.status === 'FAILED') throw new JobFailure('task_failed')
    if (task.status === 'CANCELED') throw new JobFailure('task_canceled')
    if (task.status !== 'SUCCEEDED' || !validMetrics(task.result)) throw new JobFailure('invalid_result')
  }

  async function worker() {
    while (nextJob < jobs) {
      nextJob++
      const start = performance.now()
      try {
        await job(start + jobTimeoutMs)
        report.succeeded++
      } catch (failure) {
        report.failed++
        count(report.errors, failure instanceof JobFailure ? failure.message : 'unexpected_error')
      } finally {
        samples.push(performance.now() - start)
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(jobs, concurrency) }, worker))
  return { ...report, latencyMs: percentiles(samples) }
}

async function main() {
  const env = process.env
  const number = (key, fallback) => env[key] === undefined ? fallback : Number(env[key])
  try {
    const report = await runLoad({
      baseUrl: env.QUANT_LOAD_URL,
      token: env.QUANT_DEMO_TOKEN,
      scenario: env.QUANT_LOAD_SCENARIO,
      jobs: number('QUANT_LOAD_JOBS', 20),
      concurrency: number('QUANT_LOAD_CONCURRENCY', 4),
      retries: number('QUANT_LOAD_RETRIES', 3),
      pollIntervalMs: number('QUANT_LOAD_POLL_MS', 100),
      requestTimeoutMs: number('QUANT_LOAD_REQUEST_TIMEOUT_MS', 5_000),
      jobTimeoutMs: number('QUANT_LOAD_JOB_TIMEOUT_MS', 15_000),
    })
    console.log(JSON.stringify(report, null, 2))
    if (report.failed) process.exitCode = 1
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main()
