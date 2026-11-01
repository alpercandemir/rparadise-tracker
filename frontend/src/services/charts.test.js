import assert from 'node:assert/strict'
import test from 'node:test'
import { getDecade, groupDecades } from './charts.js'

test('decade charts use all-time summary counts in chronological order and exclude unknown dates', () => {
    assert.deepEqual(groupDecades({ '2020s': 700, 'Unknown': 3, '80s': 20, '2000s': 0, '70s & Earlier': 5 }), [
        { label: '70s & Earlier', count: 5 }, { label: '80s', count: 20 }, { label: '2020s', count: 700 }
    ])
    assert.deepEqual(groupDecades({}), [])
})

test('decade browsing groups the latest tracks using the same release year rules', () => {
    assert.equal(getDecade('1979'), '70s & Earlier')
    assert.equal(getDecade('1980'), '80s')
    assert.equal(getDecade('1990'), '90s')
    assert.equal(getDecade('2000'), '2000s')
    assert.equal(getDecade('2010'), '2010s')
    assert.equal(getDecade('2020'), '2020s')
    assert.equal(getDecade('2030'), '2020s')
    assert.equal(getDecade(' 1999 remastered'), '90s')
    assert.equal(getDecade(null), 'Unknown')
    assert.equal(getDecade('Unknown'), 'Unknown')
})
