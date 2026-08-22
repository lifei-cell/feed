import { afterEach, vi } from 'vitest'
import { config } from '@vue/test-utils'

config.global.stubs = {
  Transition: false,
}

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})
