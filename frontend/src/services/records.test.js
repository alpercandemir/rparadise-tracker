import assert from 'node:assert/strict'
import test from 'node:test'
import axios from 'axios'
import { parseRecordsHash, recordsHash, updateRecordsState, formatRecordTime, pageRange } from './records.js'
import { radioParadiseAPI } from './api.js'

test('records URL preserves filters, ordering, and page across refresh', () => {
    const state = parseRecordsHash('#/records?tab=plays&page=2&size=50&sort=artist&direction=asc&q=King%20Buffalo&channel=2&from=2026-11-01&to=2026-11-02')
    assert.deepEqual(state, { tab: 'plays', page: 2, size: 50, sort: 'artist', direction: 'asc', q: 'King Buffalo', channel: '2', from: '2026-11-01', to: '2026-11-02' })
    assert.deepEqual(parseRecordsHash(recordsHash(state)), state)
})

test('unsupported URL values get safe defaults', () => {
    const state = parseRecordsHash('#/records?tab=unknown&page=-1&size=999&sort=bad&direction=sideways&channel=9')
    assert.equal(state.tab, 'tracks')
    assert.equal(state.page, 0)
    assert.equal(state.size, 25)
    assert.equal(state.sort, 'lastPlayed')
    assert.equal(state.direction, 'desc')
    assert.equal(state.channel, '')
})

test('filter and sort changes reset pagination; tabs reset incompatible sorting and dates', () => {
    const state = parseRecordsHash('#/records?tab=plays&page=8&sort=playedAt&from=2026-11-01')
    assert.equal(updateRecordsState(state, { q: 'Muse' }).page, 0)
    assert.equal(updateRecordsState(state, { sort: 'artist', direction: 'asc' }).page, 0)
    assert.equal(updateRecordsState(state, { page: 3 }).page, 3)
    const tracks = updateRecordsState(state, { tab: 'tracks' })
    assert.equal(tracks.sort, 'lastPlayed')
    assert.equal(tracks.from, '')
    assert.equal(tracks.page, 0)
})

test('timestamps include Istanbul calendar date and seconds; empty values are explicit', () => {
    assert.equal(formatRecordTime('2026-10-31T21:05:07Z'), '01.11.2026 00:05:07')
    assert.equal(formatRecordTime('2026-11-01T12:00:00+03:00'), '01.11.2026 12:00:00')
    assert.equal(formatRecordTime(null), '—')
    assert.equal(formatRecordTime('not-a-date'), '—')
})

test('empty and final pages have honest ranges', () => {
    assert.equal(pageRange({ page: 0, size: 25, totalElements: 0, items: [] }), '0 of 0')
    assert.equal(pageRange({ page: 2, size: 25, totalElements: 53, items: [{}, {}, {}] }), '51–53 of 53')
    assert.equal(pageRange({ page: 9, size: 25, totalElements: 53, items: [] }), '0 of 53')
})

test('API requests the right paginated endpoint and passes cancellation and filters', async () => {
    const original = axios.defaults.adapter
    const signal = new AbortController().signal
    const calls = []
    axios.defaults.adapter = async config => {
        calls.push(config)
        return { data: { items: [], totalElements: 0 }, status: 200, statusText: 'OK', headers: {}, config }
    }
    try {
        await radioParadiseAPI.getRecordsPage('plays', { page: 3, size: 50, channel: '1' }, signal)
        await radioParadiseAPI.getRecordsPage('tracks', { sort: 'playCount', direction: 'desc' }, signal)
        assert.equal(calls[0].url, '/api/records/plays')
        assert.deepEqual(calls[0].params, { page: 3, size: 50, channel: '1' })
        assert.equal(calls[0].signal, signal)
        assert.equal(calls[1].url, '/api/records/tracks')
    } finally {
        axios.defaults.adapter = original
    }
})
