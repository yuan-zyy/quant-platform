const path = '/v1/backtests'

export class BacktestApiError extends Error {
  constructor(status, code) {
    super(`Backtest API request failed (${status})`)
    this.name = 'BacktestApiError'
    this.status = status
    this.code = code
  }
}

export function createBacktestClient(token, { fetchImpl = fetch, baseUrl = '' } = {}) {
  const authorization = `Bearer ${token}`
  async function request(method, route, body, signal) {
    const headers = { Authorization: authorization, Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'text/plain; charset=UTF-8'
    const response = await fetchImpl(`${baseUrl}${route}`, {
      method, headers, body, signal, cache: 'no-store', redirect: 'error',
    })
    let data
    try {
      data = await response.json()
    } catch (error) {
      if (error.name === 'AbortError') throw error
      throw new BacktestApiError(response.status, 'invalid_response')
    }
    if (!response.ok) throw new BacktestApiError(response.status, data?.error)
    if (!data?.id || !data?.status) throw new BacktestApiError(response.status, 'invalid_response')
    return data
  }

  return {
    submit: (scenario, signal) => request('POST', path, scenario, signal),
    get: (id, signal) => request('GET', `${path}/${encodeURIComponent(id)}`, undefined, signal),
    cancel: (id, signal) => request('DELETE', `${path}/${encodeURIComponent(id)}`, undefined, signal),
  }
}
