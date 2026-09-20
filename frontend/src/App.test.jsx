import { describe, expect, it } from 'vitest'
import { eventFromStatus, formatDuration, shortLeaseId } from './App.jsx'

describe('lease display helpers', () => {
  it('formats short and long durations', () => {
    expect(formatDuration(62)).toBe('01:02')
    expect(formatDuration(3_661)).toBe('01:01:01')
    expect(formatDuration(null)).toBe('—')
  })

  it('never renders a full lease id', () => {
    expect(shortLeaseId('database/creds/todo-app/1234567890')).toBe('…/34567890')
  })

  it('detects renew and rotation transitions', () => {
    const previous = { leaseId: 'lease-a', renewalCount: 1, currentDbUsername: 'user-a' }
    expect(eventFromStatus(previous, { ...previous, renewalCount: 2 }).type).toBe('renewed')
    expect(eventFromStatus(previous, {
      ...previous,
      leaseId: 'lease-b',
      currentDbUsername: 'user-b',
    }).type).toBe('rotated')
  })
})
