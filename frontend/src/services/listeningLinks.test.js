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

const androidChrome = { userAgent: 'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 Chrome/142.0.0.0 Mobile Safari/537.36' }

test('Android Chrome opens accepted tracks through the correct app with the original HTTPS fallback', () => {
    const links = listeningLinks(song, { source, spotifyUrl, youtubeMusicUrl }, androidChrome)
    assert.equal(links[0].label, 'Open in Spotify')
    assert.equal(links[1].label, 'Open in YouTube Music')
    for (const [index, packageName, webUrl] of [
        [0, 'com.spotify.music', spotifyUrl],
        [1, 'com.google.android.apps.youtube.music', youtubeMusicUrl]
    ]) {
        assert.equal(links[index].target, '_self')
        assert.equal(links[index].url.split('#Intent;')[0], webUrl.replace('https:', 'intent:'))
        assert.ok(links[index].url.includes(`scheme=https;package=${packageName};`))
        assert.equal(intentFallback(links[index].url), webUrl)
    }
})

test('Android search intents preserve punctuation and Unicode without injecting intent extras', () => {
    const track = { artist: 'Björk & Friend', title: 'Title? #Intent;package=other;end / live' }
    const desktop = listeningLinks(track, null)
    const android = listeningLinks(track, null, androidChrome)
    for (let index = 0; index < android.length; index++) {
        assert.equal(intentFallback(android[index].url), desktop[index].url)
        assert.equal(android[index].url.split('#Intent;').length, 2)
        assert.equal(android[index].url.includes(';package=other;'), false)
        assert.equal(android[index].label, desktop[index].label)
    }
    assert.equal(new URL(intentFallback(android[1].url)).searchParams.get('q'), `${track.artist} ${track.title}`)
})

test('iPhone, iPad and other Android browsers use same-tab HTTPS links', () => {
    for (const browser of [
        { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1' },
        { userAgent: 'Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile Safari/604.1' },
        { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15', maxTouchPoints: 5 },
        { userAgent: 'Mozilla/5.0 (Android 15; Mobile; rv:142.0) Gecko/142.0 Firefox/142.0' },
        { userAgent: `${androidChrome.userAgent} EdgA/142.0.0.0` },
        { userAgent: `${androidChrome.userAgent} SamsungBrowser/29.0` },
        { userAgent: `${androidChrome.userAgent} OPR/90.0.0.0` },
        { userAgent: 'Mozilla/5.0 (Linux; Android 15; Pixel 9; wv) AppleWebKit/537.36 Version/4.0 Chrome/142.0.0.0 Mobile Safari/537.36' }
    ]) {
        const links = listeningLinks(song, { source, spotifyUrl, youtubeMusicUrl }, browser)
        assert.equal(links[0].url, spotifyUrl)
        assert.equal(links[1].url, youtubeMusicUrl)
        assert.ok(links.every(link => link.target === '_self'))
    }
})

test('desktop and unknown browsers retain new-tab HTTPS links', () => {
    for (const browser of [{}, { userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15) Chrome/142.0.0.0 Safari/537.36', maxTouchPoints: 0 }]) {
        const links = listeningLinks(song, { source, spotifyUrl, youtubeMusicUrl }, browser)
        assert.equal(links[0].url, spotifyUrl)
        assert.equal(links[1].url, youtubeMusicUrl)
        assert.ok(links.every(link => link.target === '_blank'))
    }
})

test('Android still rejects unsafe provider URLs before generating app intents', () => {
    const links = listeningLinks(song, { source, spotifyUrl: 'javascript:alert(1)', youtubeMusicUrl: 'https://evil.example/watch?v=Abc123_ef-g' }, androidChrome)
    assert.equal(links[0].label, 'Search Spotify')
    assert.equal(links[1].label, 'Search YouTube Music')
    assert.equal(new URL(intentFallback(links[0].url)).host, 'open.spotify.com')
    assert.equal(new URL(intentFallback(links[1].url)).host, 'music.youtube.com')
})

function intentFallback(intent) {
    return decodeURIComponent(intent.split('S.browser_fallback_url=')[1].split(';')[0])
}
