import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import { clearSession, store } from '../store'
import PeopleView from './PeopleView.vue'

describe('PeopleView', () => {
  beforeEach(() => {
    clearSession()
    store.user = { id: 1, username: 'alice', nickname: 'Alice' }
    vi.spyOn(endpoints, 'friends').mockResolvedValue([
      { id: 2, username: 'bob', nickname: 'Bob', bio: 'Friend' },
    ])
    vi.spyOn(endpoints, 'blocks').mockResolvedValue([])
    vi.spyOn(endpoints, 'friendRequests').mockResolvedValue({
      items: [{
        id: 41, status: 'PENDING', createdAt: '2026-08-20T08:00:00Z',
        requester: { id: 3, username: 'carol', nickname: 'Carol' },
        recipient: store.user,
      }],
    })
  })

  it('searches users and sends a friend request', async () => {
    vi.spyOn(endpoints, 'searchUsers').mockResolvedValue({
      items: [store.user, { id: 4, username: 'dave', nickname: 'Dave', bio: '' }],
      nextAfterId: null, hasMore: false,
    })
    const send = vi.spyOn(endpoints, 'sendFriendRequest').mockResolvedValue({ id: 50 })
    const wrapper = mount(PeopleView)
    await flushPromises()

    await wrapper.get('[aria-label="搜索用户"]').setValue('  dave  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('Dave')
    expect(wrapper.text()).not.toContain('@alice')

    await wrapper.get('.primary-small').trigger('click')
    await flushPromises()
    expect(send).toHaveBeenCalledWith(4)
    expect(store.toast?.message).toBe('已向 Dave 发送好友申请')
    wrapper.unmount()
  })

  it('accepts a pending request and removes it from the list', async () => {
    const accept = vi.spyOn(endpoints, 'acceptFriendRequest').mockResolvedValue(null)
    const wrapper = mount(PeopleView)
    await flushPromises()

    await wrapper.findAll('[role="tablist"] button')[2].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Carol')
    await wrapper.get('.primary-small').trigger('click')
    await flushPromises()

    expect(accept).toHaveBeenCalledWith(41)
    expect(wrapper.text()).toContain('没有相关申请')
    expect(store.toast?.message).toBe('已成为好友')
    wrapper.unmount()
  })

  it('removes a friend after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const remove = vi.spyOn(endpoints, 'removeFriend').mockResolvedValue(null)
    const wrapper = mount(PeopleView)
    await flushPromises()

    await wrapper.findAll('[role="tablist"] button')[1].trigger('click')
    await flushPromises()
    await wrapper.findAll('.small-button')[0].trigger('click')
    await flushPromises()

    expect(remove).toHaveBeenCalledWith(2)
    expect(wrapper.text()).toContain('还没有好友')
    wrapper.unmount()
  })
})
