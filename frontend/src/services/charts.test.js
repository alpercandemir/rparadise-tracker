import assert from 'node:assert/strict'
import test from 'node:test'
import { getDecade, groupDecades } from './charts.js'

test('decade browsing retains chronological groups and excludes unknown dates', () => {
    const songs = [{ year: '2024' }, { year: 'Unknown' }, { year: null }, { year: '1979' }, { year: '1980' }, { year: '1989' }, { year: '2000' }]
    assert.deepEqual(groupDecades(songs), [
        { label: '70s & Earlier', count: 1 }, { label: '80s', count: 2 },
        { label: '2000s', count: 1 }, { label: '2020s', count: 1 }
    ])
    assert.equal(getDecade('1990'), '90s')
    assert.equal(getDecade('2010'), '2010s')
    assert.deepEqual(groupDecades([]), [])
})
