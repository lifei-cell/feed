import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import AdminView from './AdminView.vue'

describe('AdminView Kafka dead-letter operations', () => {
  beforeEach(() => {
    vi.spyOn(endpoints, 'outboxMetrics').mockResolvedValue({
      backlog: 0, failed: 0, oldestBacklogAgeSeconds: 0, averageProcessingLatencySeconds: 0,
    })
    vi.spyOn(endpoints, 'fanoutAutomation').mockResolvedValue({ lastEvaluated: 0 })
    vi.spyOn(endpoints, 'feedShadowMetrics').mockResolvedValue({
      reads: 0, mismatches: 0, lastDuplicates: 0, sampleRate: 0,
    })
    vi.spyOn(endpoints, 'fanoutBackfills').mockResolvedValue([])
    vi.spyOn(endpoints, 'fanoutPolicyAudits').mockResolvedValue({
      items: [], nextBeforeId: null,
    })
  })

  it('loads, replays and discards pending Kafka dead letters', async () => {
    vi.spyOn(endpoints, 'kafkaDeadLetters').mockResolvedValue([
      deadLetter(11, 'feed-key-11'), deadLetter(12, 'feed-key-12'),
    ])
    const replay = vi.spyOn(endpoints, 'replayKafkaDeadLetter').mockResolvedValue({ id: 11 })
    const discard = vi.spyOn(endpoints, 'discardKafkaDeadLetter').mockResolvedValue({ id: 12 })

    const wrapper = mount(AdminView)
    await flushPromises()

    expect(wrapper.text()).toContain('feed-key-11')
    const operationButtons = wrapper.findAll('.backfill-actions button')
    await operationButtons[0].trigger('click')
    await flushPromises()
    expect(replay).toHaveBeenCalledWith(11)

    await wrapper.findAll('.backfill-actions button')[1].trigger('click')
    await flushPromises()
    expect(discard).toHaveBeenCalledWith(12, 'confirmed as non-replayable by operator')
    expect(wrapper.text()).toContain('暂无待处理 Kafka 死信')

    wrapper.unmount()
  })

  it('loads, filters and refreshes fanout policy audits after automation', async () => {
    endpoints.fanoutPolicyAudits.mockResolvedValueOnce({
      items: [{
        id: 31, authorId: 7, previousMode: 'PUSH', targetMode: 'PULL',
        previousSource: null, targetSource: 'AUTO', triggerType: 'AUTO_SCHEDULED',
        reason: 'automatic connection threshold: 12000', evaluatedFriendCount: 12000,
        actorId: null, backfillJobId: '12345678-0000-0000-0000-000000000000',
        createdAt: '2026-08-27T12:00:00Z',
      }], nextBeforeId: null,
    })
    vi.spyOn(endpoints, 'kafkaDeadLetters').mockResolvedValue([])
    vi.spyOn(endpoints, 'runFanoutAutomation').mockResolvedValue({
      evaluatedThisRun: 1, backfillsCreatedThisRun: 1,
    })
    const wrapper = mount(AdminView)
    await flushPromises()

    expect(wrapper.text()).toContain('PUSH → PULL')
    expect(wrapper.text()).toContain('AUTO_SCHEDULED')

    await wrapper.find('.audit-filter input').setValue('7')
    await wrapper.find('.audit-filter select').setValue('AUTO_ADMIN')
    await wrapper.find('.audit-filter').trigger('submit')
    await flushPromises()
    expect(endpoints.fanoutPolicyAudits).toHaveBeenLastCalledWith(7, 'AUTO_ADMIN', null)

    const automationButton = wrapper.findAll('button')
      .find((button) => button.text().includes('立即执行自动判定'))
    await automationButton.trigger('click')
    await flushPromises()
    expect(endpoints.runFanoutAutomation).toHaveBeenCalledOnce()
    expect(endpoints.fanoutPolicyAudits).toHaveBeenLastCalledWith(7, 'AUTO_ADMIN', null)

    wrapper.unmount()
  })
})

function deadLetter(id, key) {
  return {
    id,
    originalTopic: 'feed.post-published.v1',
    originalPartition: 0,
    originalOffset: id,
    exceptionClass: 'JsonParseException',
    exceptionMessage: 'invalid payload',
    messageKey: key,
    occurrenceCount: 1,
    replayCount: 0,
  }
}
