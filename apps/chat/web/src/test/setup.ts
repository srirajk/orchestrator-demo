import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

// jsdom does not implement layout APIs; stub the ones the components call so effects don't throw.
Element.prototype.scrollIntoView = vi.fn()

// Unmount React trees between tests so each renders into a clean DOM.
afterEach(() => cleanup())
