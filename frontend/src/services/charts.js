const decadeOrder = ['70s & Earlier', '80s', '90s', '2000s', '2010s', '2020s']

export function getDecade(year) {
    const number = Number.parseInt(year, 10)
    if (!Number.isFinite(number)) return 'Unknown'
    if (number < 1980) return '70s & Earlier'
    if (number < 1990) return '80s'
    if (number < 2000) return '90s'
    if (number < 2010) return '2000s'
    if (number < 2020) return '2010s'
    return '2020s'
}

export function groupDecades(songs) {
    return decadeOrder.map(label => ({ label, count: songs.filter(song => getDecade(song.year) === label).length }))
        .filter(group => group.count > 0)
}
