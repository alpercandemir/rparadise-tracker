import assert from 'node:assert/strict'
import test from 'node:test'
import { LIVE_STALE_THRESHOLD_MS, formatRelativeTime, getLiveStatus } from './liveStatus.js'

test('marks channel as LIVE when latest record is within the 15-minute threshold', () => {
    const now = Date.parse('2026-11-20T16:30:00Z')
    const recentSong = { lastPlayed: '2026-11-20T16:25:00Z' }
    const boundarySong = { lastPlayed: new Date(now - LIVE_STALE_THRESHOLD_MS).toISOString() }

    assert.deepEqual(getLiveStatus(recentSong, now, 'Europe/Istanbul'), {
        isLive: true,
        label: 'LIVE',
        relativeTime: '5m ago',
        formattedTime: '20.11.2026 19:25:00'
    })
    assert.equal(getLiveStatus(boundarySong, now, 'UTC').isLive, true)
    assert.equal(getLiveStatus(boundarySong, now, 'UTC').label, 'LIVE')
    assert.equal(getLiveStatus(boundarySong, now, 'UTC').relativeTime, '15m ago')
})

test('marks channel as OFFLINE with last received time when record is older than threshold', () => {
    const now = Date.parse('2026-11-20T16:30:00Z')
    const staleSong = { lastPlayed: '2026-11-20T14:15:07Z' }
    const justOverThreshold = { lastPlayed: new Date(now - LIVE_STALE_THRESHOLD_MS - 1000).toISOString() }

    assert.deepEqual(getLiveStatus(staleSong, now, 'Europe/Istanbul'), {
        isLive: false,
        label: 'OFFLINE',
        relativeTime: '2h ago',
        formattedTime: '20.11.2026 17:15:07'
    })
    assert.equal(getLiveStatus(justOverThreshold, now, 'UTC').isLive, false)
    assert.equal(getLiveStatus(justOverThreshold, now, 'UTC').label, 'OFFLINE')
})

test('marks channel as OFFLINE when song or timestamp is missing or invalid', () => {
    const now = Date.parse('2026-11-20T16:30:00Z')

    assert.deepEqual(getLiveStatus(null, now, 'UTC'), {
        isLive: false,
        label: 'OFFLINE',
        relativeTime: null,
        formattedTime: null
    })
    assert.deepEqual(getLiveStatus({ lastPlayed: 'invalid-date' }, now, 'UTC'), {
        isLive: false,
        label: 'OFFLINE',
        relativeTime: null,
        formattedTime: null
    })
})

test('formats relative time across seconds, minutes, hours, and days', () => {
    const now = Date.parse('2026-11-20T16:30:00Z')

    assert.equal(formatRelativeTime('2026-11-20T16:29:40Z', now), 'Just now')
    assert.equal(formatRelativeTime('2026-11-20T16:30:10Z', now), 'Just now')
    assert.equal(formatRelativeTime('2026-11-20T16:12:00Z', now), '18m ago')
    assert.equal(formatRelativeTime('2026-11-20T13:30:00Z', now), '3h ago')
    assert.equal(formatRelativeTime('2026-11-18T16:30:00Z', now), '2d ago')
    assert.equal(formatRelativeTime(null, now), '—')
})
