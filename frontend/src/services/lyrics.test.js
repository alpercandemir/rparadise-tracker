import test from 'node:test'
import assert from 'node:assert/strict'
import { lyricsSource } from './lyrics.js'

test('lyrics attribution identifies the provider and retains its recording link', () => {
    for (const [url, label] of [
        ['https://radioparadise.com/music/song/42', 'Radio Paradise'],
        ['https://lrclib.net/api/get/123', 'LRCLIB']
    ]) {
        assert.deepEqual(lyricsSource(url), { url, label })
    }
})

test('lyrics attribution rejects unsafe, unknown and incomplete source links', () => {
    for (const value of [null, '', 'javascript:alert(1)', 'https://lrclib.net.evil.test/api/get/123',
        'https://lrclib.net/api/get/123?redirect=other', 'https://radioparadise.com/music',
        'https://radioparadise.com/music/song/0', 'https://lrclib.net/api/get/123\n']) {
        assert.equal(lyricsSource(value), null)
    }
})
