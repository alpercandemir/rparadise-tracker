const sorts = {
    tracks: ['id', 'title', 'artist', 'album', 'year', 'channel', 'firstPlayed', 'lastPlayed', 'playCount'],
    plays: ['id', 'title', 'artist', 'album', 'year', 'channel', 'playedAt']
}

export function parseRecordsHash(hash) {
    const params = new URLSearchParams(hash.split('?')[1] || '')
    const tab = params.get('tab') === 'plays' ? 'plays' : 'tracks'
    const page = Number(params.get('page') || 0)
    const size = Number(params.get('size') || 25)
    const sort = params.get('sort')
    return {
        tab,
        page: Number.isInteger(page) && page >= 0 && page <= 1000000 ? page : 0,
        size: [25, 50, 100].includes(size) ? size : 25,
        sort: sorts[tab].includes(sort) ? sort : tab === 'plays' ? 'playedAt' : 'lastPlayed',
        direction: params.get('direction') === 'asc' ? 'asc' : 'desc',
        q: (params.get('q') || '').slice(0, 200),
        channel: ['0', '1', '2'].includes(params.get('channel')) ? params.get('channel') : '',
        from: tab === 'plays' ? params.get('from') || '' : '',
        to: tab === 'plays' ? params.get('to') || '' : ''
    }
}

export function recordsHash(state) {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(state)) {
        if (value !== '' && value !== null && value !== undefined) params.set(key, String(value))
    }
    return '#/records?' + params.toString()
}

export function updateRecordsState(state, patch) {
    const next = { ...state, ...patch, page: Object.hasOwn(patch, 'page') ? patch.page : 0 }
    if (patch.tab && patch.tab !== state.tab) {
        next.sort = patch.tab === 'plays' ? 'playedAt' : 'lastPlayed'
        next.direction = 'desc'
        next.from = ''
        next.to = ''
    }
    return next
}

export function getClientTimeZone() {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

const dateFormatters = new Map()

export function formatRecordTime(value, timeZone = getClientTimeZone()) {
    if (!value || Number.isNaN(new Date(value).getTime())) return '—'
    if (!dateFormatters.has(timeZone)) {
        dateFormatters.set(timeZone, new Intl.DateTimeFormat('en-GB', {
            timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
        }))
    }
    const dateFormatter = dateFormatters.get(timeZone)
    const parts = Object.fromEntries(dateFormatter.formatToParts(new Date(value)).map(part => [part.type, part.value]))
    return `${parts.day}.${parts.month}.${parts.year} ${parts.hour}:${parts.minute}:${parts.second}`
}

export function pageRange(page) {
    if (!page.items.length) return `0 of ${page.totalElements}`
    const first = page.page * page.size + 1
    return `${first}–${first + page.items.length - 1} of ${page.totalElements}`
}
