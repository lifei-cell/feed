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
