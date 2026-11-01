export function musicBrainzSource(value) {
    return typeof value === 'string' && /^https:\/\/musicbrainz\.org\/recording\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
        ? value : null
}

export function listeningLinks(song, recording = {}, browser = {}) {
    const query = encodeURIComponent(`${song.artist} ${song.title}`)
    const hasSource = musicBrainzSource(recording?.source)
    const spotify = hasSource && /^https:\/\/open\.spotify\.com\/track\/[A-Za-z0-9]{22}$/.test(recording?.spotifyUrl)
    const youtube = hasSource && /^https:\/\/music\.youtube\.com\/watch\?v=[A-Za-z0-9_-]{11}$/.test(recording?.youtubeMusicUrl)
    return [
        { provider: 'spotify', label: spotify ? 'Open in Spotify' : 'Search Spotify',
            url: spotify ? recording.spotifyUrl : `https://open.spotify.com/search/${query}` },
        { provider: 'youtube', label: youtube ? 'Open in YouTube Music' : 'Search YouTube Music',
            url: youtube ? recording.youtubeMusicUrl : `https://music.youtube.com/search?q=${query}` }
    ].map(link => appAwareNavigation(link, browser))
}

function appAwareNavigation(link, { userAgent = '', maxTouchPoints = 0 }) {
    const isAndroid = /Android/i.test(userAgent)
    const isAppleMobile = /iPhone|iPad|iPod/i.test(userAgent)
        || (/Macintosh/i.test(userAgent) && maxTouchPoints > 1)
    const target = isAndroid || isAppleMobile ? '_self' : '_blank'
    const isWebView = /;\s*wv\b|Version\/4\.0/i.test(userAgent)
    const isChrome = /Chrome\/\d+/i.test(userAgent) && !/EdgA\/|OPR\/|SamsungBrowser\//i.test(userAgent)
    const supportsAndroidIntent = isAndroid && isChrome && !isWebView
    if (!supportsAndroidIntent) return { ...link, target }

    const appPackage = link.provider === 'spotify'
        ? 'com.spotify.music' : 'com.google.android.apps.youtube.music'
    const fallback = encodeURIComponent(link.url)
    // A real anchor click preserves Chrome's required user gesture. The browser
    // handles missing apps through the HTTPS fallback, without installation probes.
    const url = `intent://${link.url.slice('https://'.length)}#Intent;scheme=https;package=${appPackage};S.browser_fallback_url=${fallback};end`
    return { ...link, url, target }
}
