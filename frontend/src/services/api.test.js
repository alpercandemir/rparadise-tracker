import assert from 'node:assert/strict'
import test from 'node:test'
import axios from 'axios'
import { CHANNELS, radioParadiseAPI } from './api.js'

test('dashboard API returns bounded tracks and all-time summaries from its dedicated endpoint', async () => {
    const original = axios.defaults.adapter
    const dashboard = { latestTracks: [{ id: 1 }], totalTracks: 800, tracksByChannel: { '1': 800 }, tracksByDecade: { '80s': 800 } }
    const calls = []
    axios.defaults.adapter = async config => {
        calls.push(config.url)
        return { data: dashboard, status: 200, statusText: 'OK', headers: {}, config }
    }
    try {
        assert.deepEqual(await radioParadiseAPI.getDashboard(), dashboard)
        assert.deepEqual(calls, ['/api/songs/dashboard'])
    } finally {
        axios.defaults.adapter = original
    }
})

// Radio Paradise's channel IDs are also the IDs stored in existing song records.
test('labels songs from Radio Paradise channel 1 as Mellow Mix', () => {
    assert.equal(CHANNELS['1'].name, 'Mellow Mix')
})

test('labels songs from Radio Paradise channel 2 as Rock Mix', () => {
    assert.equal(CHANNELS['2'].name, 'Rock Mix')
})
