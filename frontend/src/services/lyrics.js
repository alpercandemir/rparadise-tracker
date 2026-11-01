export function lyricsSource(value) {
    if (typeof value !== 'string' || value !== value.trim()) return null
    if (/^https:\/\/radioparadise\.com\/music\/song\/[1-9][0-9]*$/.test(value)) {
        return { url: value, label: 'Radio Paradise' }
    }
    if (/^https:\/\/lrclib\.net\/api\/get\/[1-9][0-9]*$/.test(value)) {
        return { url: value, label: 'LRCLIB' }
    }
    return null
}
