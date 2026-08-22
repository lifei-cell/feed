import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import { clearSession, store } from '../store'
import ProfileView from './ProfileView.vue'

describe('ProfileView', () => {
  beforeEach(() => {
    clearSession()
    store.user = {
      id: 5,
      username: 'alice',
      nickname: 'Alice',
      bio: 'old bio',
      avatarUrl: '',
    }
  })

  it('trims, saves and caches profile updates', async () => {
    const updated = { ...store.user, nickname: 'Alice New', bio: 'new bio' }
    vi.spyOn(endpoints, 'updateMe').mockResolvedValue(updated)
    const wrapper = mount(ProfileView)
    const inputs = wrapper.findAll('input')

    await inputs[1].setValue('  Alice New  ')
    await wrapper.get('textarea').setValue('  new bio  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(endpoints.updateMe).toHaveBeenCalledWith({
      nickname: 'Alice New', bio: 'new bio', avatarUrl: '',
    })
    expect(store.user).toEqual(updated)
    expect(store.profileCache.get(5)).toEqual(updated)
    expect(wrapper.text()).toContain('Alice New')
  })
})
