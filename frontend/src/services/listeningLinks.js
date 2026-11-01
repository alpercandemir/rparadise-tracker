export function musicBrainzSource(value) {
    return typeof value === 'string' && /^https:\/\/musicbrainz\.org\/recording\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
        ? value : null
}

export function listeningLinks(song, recording = {}) {
    const query = encodeURIComponent(`${song.artist} ${song.title}`)
    const hasSource = musicBrainzSource(recording?.source)
    const spotify = hasSource && /^https:\/\/open\.spotify\.com\/track\/[A-Za-z0-9]{22}$/.test(recording?.spotifyUrl)
    const youtube = hasSource && /^https:\/\/music\.youtube\.com\/watch\?v=[A-Za-z0-9_-]{11}$/.test(recording?.youtubeMusicUrl)
    return [
        { provider: 'spotify', label: spotify ? 'Open in Spotify' : 'Search Spotify',
            url: spotify ? recording.spotifyUrl : `https://open.spotify.com/search/${query}` },
        { provider: 'youtube', label: youtube ? 'Open in YouTube Music' : 'Search YouTube Music',
            url: youtube ? recording.youtubeMusicUrl : `https://music.youtube.com/search?q=${query}` }
    ]
}
