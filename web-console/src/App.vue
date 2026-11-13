<script setup>
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { createBacktestClient } from './backtests.js'

const labels = { QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' }
const token = ref('')
const client = shallowRef(null)
const scenario = ref('demo-buy-hold')
const lookup = ref('')
const task = ref(null)
const error = ref('')
const busy = ref(false)
const active = computed(() => ['QUEUED', 'RUNNING'].includes(task.value?.status))
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i
let timer
let current

function connect() {
  const credential = token.value.trim()
  if (!credential) return
  client.value = createBacktestClient(credential)
  token.value = ''
  error.value = ''
}

function disconnect() {
  clearTimeout(timer)
  current?.abort()
  current = undefined
  client.value = null
  task.value = null
  lookup.value = ''
  error.value = ''
  busy.value = false
}

function errorText(problem) {
  switch (problem.status) {
    case 401: return '访问令牌无效或已过期，请清除令牌后重新连接。'
    case 404: return '找不到指定的场景或任务，请核对编号。'
    case 503: return '回测队列暂不可用，请稍后重试。'
    case 400:
    case 413: return '场景编号格式无效。'
    default: return '请求失败，请检查服务是否运行后重试。'
  }
}

async function request(method, input, background = false) {
  if (!client.value || (current && background)) return
  clearTimeout(timer)
  current?.abort()
  const controller = new AbortController()
  current = controller
  busy.value = !background
  error.value = ''
  try {
    const snapshot = await client.value[method](input, controller.signal)
    if (current !== controller) return
    task.value = snapshot
    if (active.value) {
      timer = setTimeout(() => request('get', snapshot.id, true), 1200)
    }
  } catch (problem) {
    if (current === controller && problem.name !== 'AbortError') {
      error.value = errorText(problem)
    }
  } finally {
    if (current === controller) {
      current = undefined
      busy.value = false
    }
  }
}

function lookupTask() {
  const id = lookup.value.trim()
  if (!uuid.test(id)) {
    error.value = '请输入完整的任务 ID。'
    return
  }
  request('get', id)
}

const dateTime = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' })
function displayTime(value) {
  return value ? dateTime.format(new Date(value)) : '—'
}

function percent(value) {
  const number = Number(value)
  return Number.isFinite(number)
    ? new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 4 }).format(number)
    : value
}

onBeforeUnmount(disconnect)
</script>

<template>
  <header class="top">
    <div class="shell top-inner">
      <div class="brand"><span class="mark" aria-hidden="true">Q</span> Quant Platform</div>
      <button v-if="client" class="quiet" type="button" @click="disconnect">清除令牌</button>
    </div>
  </header>
  <main class="shell">
    <h1>回测工作台</h1>
    <form v-if="!client" class="card access" @submit.prevent="connect">
      <label for="token">访问令牌</label>
      <div class="form-row">
        <input id="token" v-model="token" type="password" autocomplete="off" required>
        <button type="submit" :disabled="!token.trim()">连接</button>
      </div>
      <p class="hint">令牌仅保存在当前页面内存中。</p>
    </form>
    <div v-else class="workspace">
      <aside class="controls">
        <section class="card">
          <h2>新建任务</h2>
          <form @submit.prevent="request('submit', scenario.trim())">
            <label for="scenario">场景编号</label>
            <input id="scenario" v-model="scenario" required maxlength="128" pattern="[A-Za-z0-9._\-]{1,128}">
            <button type="submit" :disabled="busy">提交回测</button>
          </form>
        </section>
        <section class="card">
          <h2>查找任务</h2>
          <form @submit.prevent="lookupTask">
            <label for="lookup">任务 ID</label>
            <input id="lookup" v-model="lookup" required maxlength="36" autocomplete="off" spellcheck="false">
            <button class="secondary" type="submit" :disabled="busy">查询</button>
          </form>
        </section>
      </aside>

      <section class="card details" aria-labelledby="details-title" :aria-busy="busy">
        <div class="detail-heading">
          <h2 id="details-title">任务详情</h2>
          <span v-if="task" class="status" :data-status="task.status" aria-live="polite">
            {{ labels[task.status] ?? task.status }}
          </span>
        </div>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="busy" class="hint" role="status">正在请求服务…</p>
        <p v-if="!task && !busy" class="empty">提交新回测，或通过任务 ID 查找已有任务。</p>
        <template v-if="task">
          <dl class="metadata">
            <div><dt>任务 ID</dt><dd class="task-id">{{ task.id }}</dd></div>
            <div><dt>提交时间</dt><dd>{{ displayTime(task.submittedAt) }}</dd></div>
            <div><dt>开始时间</dt><dd>{{ displayTime(task.startedAt) }}</dd></div>
            <div><dt>结束时间</dt><dd>{{ displayTime(task.finishedAt) }}</dd></div>
          </dl>
          <dl v-if="task.result" class="metrics">
            <div><dt>期末权益</dt><dd>{{ task.result.endingEquity }}</dd></div>
            <div><dt>总收益率</dt><dd>{{ percent(task.result.totalReturn) }}</dd></div>
            <div><dt>最大回撤</dt><dd>{{ percent(task.result.maxDrawdown) }}</dd></div>
            <div><dt>成交次数</dt><dd>{{ task.result.fillCount }}</dd></div>
          </dl>
          <p v-else-if="task.status === 'FAILED'" class="hint">任务执行失败，未生成回测结果。</p>
          <div class="actions">
            <button class="secondary" type="button" :disabled="busy" @click="request('get', task.id)">刷新状态</button>
            <button v-if="active" class="cancel" type="button" :disabled="busy" @click="request('cancel', task.id)">
              取消任务
            </button>
          </div>
        </template>
      </section>
    </div>
  </main>
</template>
