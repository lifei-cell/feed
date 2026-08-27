import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import { clearSession, store } from '../store'
import NotificationsView from './NotificationsView.vue'

const notification = {
  id: 71,
  type: 'POST_LIKED',
  message: 'Bob 点赞了你的动态',
  readAt: null,
  createdAt: '2026-08-20T08:00:00Z',
  actor: { id: 2, username: 'bob', nickname: 'Bob' },
}

describe('NotificationsView', () => {
  beforeEach(() => {
    clearSession()
    store.user = { id: 1, username: 'alice', nickname: 'Alice' }
    store.unreadCount = 1
  })

  it('marks a notification read and refreshes the unread count', async () => {
    vi.spyOn(endpoints, 'notifications').mockImplementation(async (unreadOnly, beforeId, size) => {
      if (size === 1) return { items: [], unreadCount: 0, hasMore: false }
      return { items: [notification], unreadCount: 1, hasMore: false, nextBeforeId: null }
    })
    const markRead = vi.spyOn(endpoints, 'markNotificationRead').mockResolvedValue(null)
    const wrapper = mount(NotificationsView)
    await flushPromises()

    expect(wrapper.get('.notification-row').classes()).toContain('unread')
    await wrapper.get('.notification-row').trigger('click')
    await flushPromises()

    expect(markRead).toHaveBeenCalledWith(71)
    expect(wrapper.get('.notification-row').classes()).not.toContain('unread')
    expect(store.unreadCount).toBe(0)
    wrapper.unmount()
  })

  it('marks all loaded notifications read', async () => {
    vi.spyOn(endpoints, 'notifications').mockResolvedValue({
      items: [notification], unreadCount: 1, hasMore: false, nextBeforeId: null,
    })
    const markAll = vi.spyOn(endpoints, 'markAllNotificationsRead').mockResolvedValue({ updatedCount: 1 })
    const wrapper = mount(NotificationsView)
    await flushPromises()

    await wrapper.get('.secondary-button').trigger('click')
    await flushPromises()

    expect(markAll).toHaveBeenCalledOnce()
    expect(wrapper.get('.notification-row').classes()).not.toContain('unread')
    expect(store.unreadCount).toBe(0)
    expect(store.toast?.message).toBe('已将 1 条通知标为已读')
    wrapper.unmount()
  })
})
