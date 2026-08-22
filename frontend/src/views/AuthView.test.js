import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { endpoints } from '../api'
import AuthView from './AuthView.vue'

describe('AuthView', () => {
  it('submits login credentials and emits the created session', async () => {
    const access = { accessToken: 'access', refreshToken: 'refresh' }
    vi.spyOn(endpoints, 'login').mockResolvedValue(access)
    const wrapper = mount(AuthView)

    await wrapper.get('input[autocomplete="username"]').setValue('demo_alice')
    await wrapper.get('input[type="password"]').setValue('demo12345')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(endpoints.login).toHaveBeenCalledWith({ username: 'demo_alice', password: 'demo12345' })
    expect(wrapper.emitted('authenticated')).toEqual([[access]])
  })

  it('requests a registration challenge before enabling registration', async () => {
    vi.spyOn(endpoints, 'requestRegistrationCode').mockResolvedValue({
      challengeId: 'challenge-1', expiresIn: 600,
    })
    const wrapper = mount(AuthView)
    const tabs = wrapper.findAll('.auth-tabs button')
    await tabs[1].trigger('click')
    await wrapper.get('input[type="email"]').setValue('alice@example.com')
    await wrapper.get('.code-button').trigger('click')
    await flushPromises()

    expect(endpoints.requestRegistrationCode).toHaveBeenCalledWith({
      channel: 'EMAIL', target: 'alice@example.com',
    })
    expect(wrapper.text()).toContain('验证码已发送')
    expect(wrapper.get('.auth-submit').attributes('disabled')).toBeUndefined()
  })
})
