import { formatRecordTime, getClientTimeZone } from './records.js'

export const LIVE_STALE_THRESHOLD_MS = 15 * 60 * 1000

export function formatRelativeTime(timestamp, now = Date.now()) {
    if (!timestamp) return '—'
    const playedAt = new Date(timestamp).getTime()
    const current = new Date(now).getTime()
    if (Number.isNaN(playedAt) || Number.isNaN(current)) return '—'

    const diffSeconds = Math.max(0, Math.floor((current - playedAt) / 1000))
    if (diffSeconds < 60) return 'Just now'
    if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)}m ago`
    if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)}h ago`
    return `${Math.floor(diffSeconds / 86400)}d ago`
}

export function getLiveStatus(song, now = Date.now(), timeZone = getClientTimeZone()) {
    const lastPlayed = song?.lastPlayed
    if (!lastPlayed) {
        return { isLive: false, label: 'OFFLINE', relativeTime: null, formattedTime: null }
    }

    const playedAt = new Date(lastPlayed).getTime()
    const current = new Date(now).getTime()
    if (Number.isNaN(playedAt) || Number.isNaN(current)) {
        return { isLive: false, label: 'OFFLINE', relativeTime: null, formattedTime: null }
    }

    const ageMs = current - playedAt
    const isLive = ageMs <= LIVE_STALE_THRESHOLD_MS
    return {
        isLive,
        label: isLive ? 'LIVE' : 'OFFLINE',
        relativeTime: formatRelativeTime(lastPlayed, current),
        formattedTime: formatRecordTime(lastPlayed, timeZone)
    }
}
