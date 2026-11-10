import assert from 'node:assert/strict'
import test from 'node:test'
import axios from 'axios'
import { execFileSync } from 'node:child_process'
import { parseRecordsHash, recordsHash, updateRecordsState, formatRecordTime, pageRange } from './records.js'
import { radioParadiseAPI } from './api.js'

test('songs URL preserves browsing state and excludes channel and history filters', () => {
    const state = parseRecordsHash('#/songs?q=Muse&page=2&size=50&sort=playCount&direction=asc&channel=2&from=2026-11-01&tab=plays')
    assert.deepEqual(state, { tab: 'songs', page: 2, size: 50, sort: 'playCount', direction: 'asc', q: 'Muse', channel: '', from: '', to: '' })
    assert.ok(recordsHash(state).startsWith('#/songs?'))
    assert.deepEqual(parseRecordsHash(recordsHash(state)), state)
    assert.equal(parseRecordsHash('#/songs?sort=channel').sort, 'lastPlayed')
    assert.equal(updateRecordsState(state, { q: 'Pink Floyd' }).page, 0)
})

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

test('timestamps convert absolute instants to the requested zone including day rollover', () => {
    assert.equal(formatRecordTime('2026-10-31T21:05:07Z', 'Europe/Istanbul'), '01.11.2026 00:05:07')
    assert.equal(formatRecordTime('2026-11-01T12:00:00+03:00', 'UTC'), '01.11.2026 09:00:00')
    assert.equal(formatRecordTime('2026-10-31T21:05:07Z', 'Asia/Tokyo'), '01.11.2026 06:05:07')
    assert.equal(formatRecordTime('2026-10-31T21:05:07Z', 'America/New_York'), '31.10.2026 17:05:07')
    assert.equal(formatRecordTime(null), '—')
    assert.equal(formatRecordTime('not-a-date'), '—')
})

test('formatting follows the browser runtime timezone by default', () => {
    const moduleUrl = new URL('./records.js', import.meta.url).href
    for (const [zone, expected] of [
        ['UTC', '31.10.2026 21:05:07'],
        ['America/New_York', '31.10.2026 17:05:07'],
        ['Asia/Tokyo', '01.11.2026 06:05:07']
    ]) {
        const result = execFileSync(process.execPath, ['--input-type=module', '-e',
            `import {formatRecordTime} from ${JSON.stringify(moduleUrl)}; process.stdout.write(formatRecordTime('2026-10-31T21:05:07Z'))`
        ], { env: { ...process.env, TZ: zone }, encoding: 'utf8' })
        assert.equal(result, expected, zone)
    }
})

test('local formatting applies daylight saving for the date being shown', () => {
    assert.equal(formatRecordTime('2026-03-08T06:59:59Z', 'America/New_York'), '08.03.2026 01:59:59')
    assert.equal(formatRecordTime('2026-03-08T07:00:00Z', 'America/New_York'), '08.03.2026 03:00:00')
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
        await radioParadiseAPI.getRecordsPage('songs', { page: 2, q: 'Muse' }, signal)
        assert.equal(calls[0].url, '/api/records/plays')
        assert.deepEqual(calls[0].params, { page: 3, size: 50, channel: '1' })
        assert.equal(calls[0].signal, signal)
        assert.equal(calls[1].url, '/api/records/tracks')
        assert.equal(calls[2].url, '/api/songs/library')
        assert.deepEqual(calls[2].params, { page: 2, q: 'Muse' })
        assert.equal(calls[2].signal, signal)
    } finally {
        axios.defaults.adapter = original
    }
})
