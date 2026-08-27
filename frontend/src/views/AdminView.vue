<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { endpoints } from '../api'
import { notify } from '../store'
import UiIcon from '../components/UiIcon.vue'

const metrics = ref(null)
const automation = ref(null)
const shadow = ref(null)
const loading = ref(true)
const eventId = ref('')
const replaying = ref(false)
const policyAuthorId = ref('')
const policyMode = ref('PULL')
const policyReason = ref('')
const historyLimit = ref(100)
const policy = ref(null)
const savingPolicy = ref(false)
const backfills = ref([])
const audits = ref([])
const auditAuthorId = ref('')
const auditTriggerType = ref('')
const auditNextBeforeId = ref(null)
const kafkaDeadLetters = ref([])
const busyJobId = ref('')
const busyDeadLetterId = ref(0)
let backfillPoller = null

onMounted(() => {
  load()
  backfillPoller = window.setInterval(loadBackfills, 3000)
})
onUnmounted(() => window.clearInterval(backfillPoller))

async function load() {
  loading.value = true
  try {
    const [outbox, autoPolicy, shadowRead, jobs, deadLetters, auditPage] = await Promise.all([
      endpoints.outboxMetrics(), endpoints.fanoutAutomation(), endpoints.feedShadowMetrics(),
      endpoints.fanoutBackfills(), endpoints.kafkaDeadLetters(), endpoints.fanoutPolicyAudits(),
    ])
    metrics.value = outbox
    automation.value = autoPolicy
    shadow.value = shadowRead
    backfills.value = jobs
    kafkaDeadLetters.value = deadLetters
    audits.value = auditPage.items
    auditNextBeforeId.value = auditPage.nextBeforeId
  }
  catch (error) { notify(error.message, 'error') }
  finally { loading.value = false }
}

async function resolveDeadLetter(record, action) {
  busyDeadLetterId.value = record.id
  try {
    if (action === 'replay') {
      await endpoints.replayKafkaDeadLetter(record.id)
      notify(`Kafka 死信 ${record.id} 已投递回 ${record.originalTopic}`)
    } else {
      await endpoints.discardKafkaDeadLetter(record.id, 'confirmed as non-replayable by operator')
      notify(`Kafka 死信 ${record.id} 已标记为丢弃`)
    }
    kafkaDeadLetters.value = kafkaDeadLetters.value.filter((item) => item.id !== record.id)
  } catch (error) { notify(error.message, 'error') }
  finally { busyDeadLetterId.value = 0 }
}

async function loadBackfills() {
  try { backfills.value = await endpoints.fanoutBackfills() }
  catch { /* Keep background polling quiet; explicit refresh still reports errors. */ }
}

async function loadAudits(append = false) {
  try {
    const page = await endpoints.fanoutPolicyAudits(
      auditAuthorId.value || null,
      auditTriggerType.value || null,
      append ? auditNextBeforeId.value : null,
    )
    audits.value = append ? [...audits.value, ...page.items] : page.items
    auditNextBeforeId.value = page.nextBeforeId
  } catch (error) { notify(error.message, 'error') }
}

async function runAutomation() {
  savingPolicy.value = true
  try {
    automation.value = await endpoints.runFanoutAutomation()
    await Promise.all([loadBackfills(), loadAudits()])
    notify(`已评估 ${automation.value.evaluatedThisRun} 位作者，创建回填 ${automation.value.backfillsCreatedThisRun} 个`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function replay() {
  if (!eventId.value) return
  replaying.value = true
  try {
    await endpoints.replayOutbox(eventId.value)
    notify(`事件 ${eventId.value} 已重新进入投递队列`)
    eventId.value = ''
    await load()
  } catch (error) { notify(error.message, 'error') }
  finally { replaying.value = false }
}

async function loadPolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    policy.value = await endpoints.fanoutPolicy(policyAuthorId.value)
    policyMode.value = policy.value.mode
    policyReason.value = policy.value.reason || ''
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function savePolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    const result = await endpoints.switchFanoutPolicy(policyAuthorId.value, {
      mode: policyMode.value,
      reason: policyReason.value.trim() || null,
      historyLimit: historyLimit.value === '' ? null : Math.max(0, Number(historyLimit.value) || 0),
    })
    policy.value = result.policy
    backfills.value = [result.backfillJob, ...backfills.value.filter((job) => job.id !== result.backfillJob.id)]
    await loadAudits()
    notify(`策略已切换为 ${policyMode.value}，回填任务已创建，共 ${result.backfillJob.totalPosts} 条动态`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function resetPolicy() {
  if (!policyAuthorId.value) return
  savingPolicy.value = true
  try {
    await endpoints.resetFanoutPolicy(policyAuthorId.value)
    policy.value = null
    policyMode.value = 'PUSH'
    policyReason.value = ''
    await loadAudits()
    notify(`用户 ${policyAuthorId.value} 已恢复默认 PUSH 扩散`)
  } catch (error) { notify(error.message, 'error') }
  finally { savingPolicy.value = false }
}

async function controlBackfill(job, action) {
  busyJobId.value = job.id
  try {
    const method = {
      pause: endpoints.pauseFanoutBackfill,
      resume: endpoints.resumeFanoutBackfill,
      retry: endpoints.retryFanoutBackfill,
      cancel: endpoints.cancelFanoutBackfill,
    }[action]
    const updated = await method(job.id)
    backfills.value = backfills.value.map((item) => item.id === updated.id ? updated : item)
    notify(`回填任务已${{ pause: '暂停', resume: '继续', retry: '重新入队', cancel: '取消' }[action]}`)
  } catch (error) { notify(error.message, 'error') }
  finally { busyJobId.value = '' }
}

function progress(job) {
  if (!job.totalPosts) return 100
  return Math.min(100, Math.round(job.processedPosts * 100 / job.totalPosts))
}

function shortId(id) { return id?.slice(0, 8) }
function sourceLabel(source) { return source || 'DEFAULT' }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN') : '-' }
</script>

<template>
  <div class="single-page narrow-page">
    <header class="page-heading heading-actions"><div><p class="eyebrow">OPERATIONS</p><h1>Outbox 运维</h1><p>查看异步扩散链路健康状态并重放死信。</p></div><button class="icon-button soft" @click="load"><UiIcon name="refresh" /></button></header>
    <div v-if="loading" class="list-loading">正在读取指标…</div>
    <section v-else-if="metrics" class="metric-grid">
      <article><span>待完成事件</span><strong>{{ metrics.backlog }}</strong><small>Backlog</small></article>
      <article :class="{ alert: metrics.failed > 0 }"><span>死信事件</span><strong>{{ metrics.failed }}</strong><small>Failed</small></article>
      <article><span>最老积压</span><strong>{{ Number(metrics.oldestBacklogAgeSeconds).toFixed(1) }}s</strong><small>Oldest age</small></article>
      <article><span>平均延迟</span><strong>{{ Number(metrics.averageProcessingLatencySeconds).toFixed(2) }}s</strong><small>5 分钟窗口</small></article>
    </section>
    <section v-if="automation && shadow" class="metric-grid">
      <article><span>自动判定</span><strong>{{ automation.lastEvaluated }}</strong><small>最近评估作者</small></article>
      <article><span>影子读取</span><strong>{{ shadow.reads }}</strong><small>采样 {{ Math.round(shadow.sampleRate * 100) }}%</small></article>
      <article :class="{ alert: shadow.mismatches > 0 }"><span>Feed 差异</span><strong>{{ shadow.mismatches }}</strong><small>Mismatch</small></article>
      <article :class="{ alert: shadow.lastDuplicates > 0 }"><span>最近重复</span><strong>{{ shadow.lastDuplicates }}</strong><small>Duplicate</small></article>
    </section>
    <section class="admin-replay card-surface">
      <div><span class="rail-icon"><UiIcon name="refresh" /></span><div><h2>重放 FAILED 事件</h2><p>仅 FAILED 状态的 Outbox 事件可以重放，尝试次数会被清零。</p></div></div>
      <form @submit.prevent="replay"><input v-model="eventId" type="number" min="1" placeholder="事件 ID" required><button class="primary-button" :disabled="replaying">{{ replaying ? '处理中…' : '确认重放' }}</button></form>
    </section>
    <section class="backfill-panel card-surface">
      <div class="section-title"><div><h2>策略变更审计</h2><p>记录人工与自动策略切换、评估依据及关联回填任务。</p></div></div>
      <form class="audit-filter" @submit.prevent="loadAudits(false)">
        <input v-model="auditAuthorId" type="number" min="1" placeholder="作者用户 ID（可选）">
        <select v-model="auditTriggerType" aria-label="审计触发类型">
          <option value="">全部触发类型</option>
          <option value="MANUAL_SET">人工设置</option>
          <option value="MANUAL_SWITCH">人工切换</option>
          <option value="MANUAL_RESET">人工重置</option>
          <option value="AUTO_SCHEDULED">定时自动</option>
          <option value="AUTO_ADMIN">管理员自动判定</option>
        </select>
        <button class="secondary-button" type="submit">查询审计</button>
      </form>
      <div v-if="!audits.length" class="empty-inline">暂无策略变更审计</div>
      <div v-else class="backfill-list">
        <article v-for="audit in audits" :key="audit.id" class="backfill-job">
          <div class="backfill-heading">
            <div><strong>作者 {{ audit.authorId }} · {{ audit.previousMode }} → {{ audit.targetMode }}</strong><small>#{{ audit.id }} · {{ audit.triggerType }} · {{ formatTime(audit.createdAt) }}</small></div>
            <span class="status-pill">{{ sourceLabel(audit.previousSource) }} → {{ sourceLabel(audit.targetSource) }}</span>
          </div>
          <p v-if="audit.reason" class="backfill-error">{{ audit.reason }}</p>
          <div class="backfill-stats">
            <span>操作者 {{ audit.actorId || 'SYSTEM' }}</span>
            <span>好友数 {{ audit.evaluatedFriendCount ?? '-' }}</span>
            <span>回填 #{{ shortId(audit.backfillJobId) || '-' }}</span>
          </div>
        </article>
      </div>
      <button v-if="auditNextBeforeId" class="secondary-button" type="button" @click="loadAudits(true)">加载更多</button>
    </section>
    <section class="backfill-panel card-surface">
      <div class="section-title"><div><h2>Kafka 异常消息</h2><p>消费重试耗尽后进入 DLT 并持久化，可审计重放或人工丢弃。</p></div></div>
      <div v-if="!kafkaDeadLetters.length" class="empty-inline">暂无待处理 Kafka 死信</div>
      <div v-else class="backfill-list">
        <article v-for="record in kafkaDeadLetters" :key="record.id" class="backfill-job">
          <div class="backfill-heading">
            <div><strong>{{ record.originalTopic }} · P{{ record.originalPartition }} / O{{ record.originalOffset }}</strong><small>#{{ record.id }} · {{ record.exceptionClass || 'Unknown exception' }}</small></div>
            <span class="status-pill job-failed">PENDING</span>
          </div>
          <p v-if="record.exceptionMessage" class="backfill-error">{{ record.exceptionMessage }}</p>
          <div class="backfill-stats"><span>Key {{ record.messageKey || '-' }}</span><span>出现 {{ record.occurrenceCount }} 次</span><span>重放 {{ record.replayCount }} 次</span></div>
          <div class="backfill-actions">
            <button class="small-button primary-small" :disabled="busyDeadLetterId === record.id" @click="resolveDeadLetter(record, 'replay')">重放</button>
            <button class="small-button danger" :disabled="busyDeadLetterId === record.id" @click="resolveDeadLetter(record, 'discard')">确认丢弃</button>
          </div>
        </article>
      </div>
    </section>
    <section class="admin-replay admin-policy card-surface">
      <div><span class="rail-icon"><UiIcon name="people" /></span><div><h2>作者扩散策略</h2><p>切换立即影响后续发布，历史动态由可恢复后台任务分批迁移；留空回填数量表示处理全部历史。</p></div></div>
      <form @submit.prevent="savePolicy">
        <input v-model="policyAuthorId" type="number" min="1" placeholder="作者用户 ID" required>
        <select v-model="policyMode" aria-label="扩散模式"><option value="PUSH">PUSH 写扩散</option><option value="PULL">PULL 读扩散</option></select>
        <input v-model="policyReason" maxlength="128" placeholder="调整原因（可选）">
        <input v-model.number="historyLimit" type="number" min="0" placeholder="回填数量（留空为全部）">
        <button class="primary-button" :disabled="savingPolicy">{{ savingPolicy ? '处理中…' : '创建回填任务' }}</button>
      </form>
      <div class="policy-actions">
        <button class="secondary-button" type="button" :disabled="savingPolicy" @click="runAutomation">立即执行自动判定</button>
        <button class="secondary-button" type="button" :disabled="savingPolicy || !policyAuthorId" @click="loadPolicy">查询当前策略</button>
        <button class="secondary-button danger" type="button" :disabled="savingPolicy || !policyAuthorId" @click="resetPolicy">恢复默认 PUSH</button>
        <span v-if="policy" class="status-pill">当前：{{ policy.mode }} · {{ policy.source || (policy.explicit ? 'MANUAL' : '系统默认') }}</span>
      </div>
    </section>
    <section class="backfill-panel card-surface">
      <div class="section-title"><div><h2>历史回填任务</h2><p>任务按持久化游标分批执行；暂停、故障或服务重启都不会丢失已完成进度。</p></div><button class="icon-button soft" type="button" @click="loadBackfills"><UiIcon name="refresh" /></button></div>
      <div v-if="!backfills.length" class="empty-inline">暂无回填任务</div>
      <div v-else class="backfill-list">
        <article v-for="job in backfills" :key="job.id" class="backfill-job">
          <div class="backfill-heading">
            <div><strong>作者 {{ job.authorId }} · {{ job.sourceMode }} → {{ job.targetMode }}</strong><small>#{{ shortId(job.id) }} · {{ job.status }}</small></div>
            <span class="status-pill" :class="`job-${job.status.toLowerCase()}`">{{ progress(job) }}%</span>
          </div>
          <div class="backfill-progress"><i :style="{ width: `${progress(job)}%` }"></i></div>
          <div class="backfill-stats"><span>动态 {{ job.processedPosts }} / {{ job.totalPosts }}</span><span>Inbox +{{ job.inboxRowsInserted }}</span><span>失败 {{ job.failureCount }} 次</span></div>
          <p v-if="job.lastError" class="backfill-error">{{ job.lastError }}</p>
          <div class="backfill-actions">
            <button v-if="['PENDING', 'RUNNING'].includes(job.status)" class="small-button" :disabled="busyJobId === job.id" @click="controlBackfill(job, 'pause')">暂停</button>
            <button v-if="job.status === 'PAUSED'" class="small-button primary-small" :disabled="busyJobId === job.id" @click="controlBackfill(job, 'resume')">继续</button>
            <button v-if="job.status === 'FAILED'" class="small-button primary-small" :disabled="busyJobId === job.id" @click="controlBackfill(job, 'retry')">重试</button>
            <button v-if="['PENDING', 'RUNNING', 'PAUSED', 'FAILED'].includes(job.status)" class="small-button danger" :disabled="busyJobId === job.id" @click="controlBackfill(job, 'cancel')">取消</button>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>
