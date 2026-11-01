import assert from 'node:assert/strict'
import test from 'node:test'
import { CHANNELS } from './api.js'

// Radio Paradise's channel IDs are also the IDs stored in existing song records.
test('labels songs from Radio Paradise channel 1 as Mellow Mix', () => {
    assert.equal(CHANNELS['1'].name, 'Mellow Mix')
})

test('labels songs from Radio Paradise channel 2 as Rock Mix', () => {
    assert.equal(CHANNELS['2'].name, 'Rock Mix')
})
