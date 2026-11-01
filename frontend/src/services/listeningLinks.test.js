import test from 'node:test'
import assert from 'node:assert/strict'
import { listeningLinks, musicBrainzSource } from './listeningLinks.js'

const song = { artist: 'Artist & Friend', title: 'Title? #1/2' }
const source = 'https://musicbrainz.org/recording/5a6cd4c6-3d1f-4d71-8803-1b327674fb86'
const spotifyUrl = 'https://open.spotify.com/track/0123456789ABCDEFGHIJKL'
const youtubeMusicUrl = 'https://music.youtube.com/watch?v=Abc123_ef-g'

test('unresolved songs open encoded artist and title searches', () => {
    const links = listeningLinks(song, null)
    assert.equal(links[0].label, 'Search Spotify')
    assert.equal(decodeURIComponent(new URL(links[0].url).pathname.slice('/search/'.length)), `${song.artist} ${song.title}`)
    assert.equal(new URL(links[1].url).searchParams.get('q'), `${song.artist} ${song.title}`)
})

test('catalog links open the accepted recording for each provider independently', () => {
    const links = listeningLinks(song, { source, spotifyUrl, youtubeMusicUrl })
    assert.equal(links[0].label, 'Open in Spotify')
    assert.equal(links[0].url, spotifyUrl)
    assert.equal(links[1].label, 'Open in YouTube Music')
    assert.equal(links[1].url, youtubeMusicUrl)
    assert.equal(listeningLinks(song, { source, spotifyUrl })[1].label, 'Search YouTube Music')
})

test('unsafe, album, generic YouTube and unprovenanced links fall back to searches', () => {
    for (const url of ['javascript:alert(1)', 'https://open.spotify.com.evil.test/track/0123456789ABCDEFGHIJKL',
        'https://open.spotify.com/album/0123456789ABCDEFGHIJKL', `${spotifyUrl}?redirect=other`]) {
        assert.equal(listeningLinks(song, { source, spotifyUrl: url })[0].label, 'Search Spotify')
    }
    assert.equal(listeningLinks(song, { spotifyUrl })[0].label, 'Search Spotify')
    assert.equal(listeningLinks(song, { source, youtubeMusicUrl: 'https://www.youtube.com/watch?v=Abc123_ef-g' })[1].label, 'Search YouTube Music')
    assert.equal(musicBrainzSource('https://musicbrainz.org.evil.test/recording/id'), null)
})
