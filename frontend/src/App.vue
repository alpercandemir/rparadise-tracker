<template>
  <div class="container">
    <header class="header">
      <h1 class="title">
        <span class="icon" aria-hidden="true">♫</span>
        Radio Paradise Tracker
      </h1>
      <p class="subtitle">Your listening archive · Main, Mellow & Rock</p>
      <nav class="main-nav" aria-label="Main navigation">
        <a href="#/" :aria-current="view === 'dashboard' ? 'page' : undefined" :class="{ selected: view === 'dashboard' }">Dashboard</a>
        <a href="#/songs" :aria-current="view === 'songs' ? 'page' : undefined" :class="{ selected: view === 'songs' }">Songs</a>
        <a href="#/records" :aria-current="view === 'records' ? 'page' : undefined" :class="{ selected: view === 'records' }">Records</a>
      </nav>
    </header>

    <RecordsPage v-if="view !== 'dashboard'" :key="view" :unified="view === 'songs'" @select-song="openSong" />
    <div v-else-if="loading" class="loading">
      <div class="spinner"></div>
      <p>Loading tracks...</p>
    </div>

    <div v-else-if="loadError" class="load-error card" role="alert">
      <h2>Could not load the dashboard</h2>
      <p>{{ loadError }}</p>
      <button @click="loadData">Try again</button>
    </div>

    <div v-else class="dashboard">
      <!-- Current Playing Songs -->
      <section class="current-songs">
        <div class="section-heading"><h2 class="section-title">Now Playing</h2><span>Latest observed · refreshes every 30 seconds</span></div>
        <div class="channel-grid">
          <CurrentSong
            v-for="(channel, id) in channels"
            :key="id"
            :channelId="id"
            :channelName="channel.name"
            :channelColor="channel.color"
            :song="currentSongs[id]"
            @select-song="openSong"
          />
        </div>
      </section>

      <!-- Statistics -->
      <section class="stats-section">
        <div class="stats-grid">
          <StatsCard
            title="Total Tracks"
            :value="summary.totalTracks"
            icon="🎼"
            color="var(--primary)"
          />
          <StatsCard
            title="Channels"
            :value="3"
            icon="📻"
            color="var(--accent)"
          />
          <StatsCard
            title="Recent Tracks"
            :value="recentSongs.length"
            icon="⏱️"
            color="var(--secondary)"
          />
        </div>
      </section>

      <!-- Charts -->
      <NewDiscoveries @select-song="openSong" />

      <section class="charts-section">
        <div class="charts-grid">
          <div class="card chart-card">
            <h3 class="chart-title">Tracks by Channel · All time</h3>
            <ChannelChart :counts="summary.tracksByChannel" />
          </div>
          <div class="card chart-card">
            <h3 class="chart-title">Most Played · All time</h3>
            <MostPlayedChart :songs="mostPlayedSongs" @select-song="openSong" />
          </div>
        </div>
        
        <!-- Timeline Chart (Full Width) -->
        <div class="card chart-card timeline-chart">
          <h3 class="chart-title">Songs by Decade · All time</h3>
          <TimelineChart :songs="latestSongs" :counts="summary.tracksByDecade" @select-song="openSong" />
        </div>
      </section>

      <section class="card archive-links">
        <div><h2 class="section-title">Your song library</h2><p>Browse every song with listening statistics combined across channels.</p></div>
        <a href="#/songs">Browse all songs →</a>
        <a href="#/records">Channel tracks &amp; playback history →</a>
      </section>
    </div>
    <SongDetailsModal v-if="selectedSong" :song="selectedSong" @close="selectedSong = null" />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import RecordsPage from './components/RecordsPage.vue'
import NewDiscoveries from './components/NewDiscoveries.vue'
import { radioParadiseAPI, CHANNELS } from './services/api.js'
import CurrentSong from './components/CurrentSong.vue'
import StatsCard from './components/StatsCard.vue'
import ChannelChart from './components/ChannelChart.vue'
import MostPlayedChart from './components/MostPlayedChart.vue'
import TimelineChart from './components/TimelineChart.vue'
import SongDetailsModal from './components/SongDetailsModal.vue'

const loading = ref(true)
const loadError = ref('')
const latestSongs = ref([])
const summary = ref({ totalTracks: 0, tracksByChannel: {}, tracksByDecade: {} })
const recentSongs = ref([])
const mostPlayedSongs = ref([])
const liveSongs = ref([])
const selectedSong = ref(null)
const openSong = song => { selectedSong.value = { ...song, id: song.songId ?? song.id } }
const channels = CHANNELS
const currentView = () => {
  const path = window.location.hash.split('?')[0]
  return path === '#/songs' ? 'songs' : path === '#/records' ? 'records' : 'dashboard'
}
const view = ref(currentView())
let refreshTimer

const syncView = () => {
  const previous = view.value
  view.value = currentView()
  if (previous !== 'dashboard' && view.value === 'dashboard') loadData()
}

const isChannelMatch = (songChannel, channelId) => {
  if (!songChannel) {
    return false
  }

  const expectedName = channels[channelId]?.name
  return songChannel === channelId || songChannel === expectedName
}

const currentSongs = computed(() => {
  const songs = {}
  Object.keys(channels).forEach(channelId => {
    const channelSongs = liveSongs.value.filter(s => isChannelMatch(s.channel, channelId))
    songs[channelId] = channelSongs.length > 0
      ? channelSongs.reduce((latest, song) =>
          new Date(song.lastPlayed) > new Date(latest.lastPlayed) ? song : latest
        )
      : null
  })
  return songs
})

const loadData = async () => {
  loadError.value = ''
  try {
    // Only show loading state on initial load.
    if (latestSongs.value.length === 0) {
      loading.value = true
    }
    const [dashboard, recent, mostPlayed, current] = await Promise.all([
      radioParadiseAPI.getDashboard(),
      radioParadiseAPI.getRecentSongs(),
      radioParadiseAPI.getMostPlayedSongs(),
      radioParadiseAPI.getCurrentSongs()
    ])
    latestSongs.value = dashboard.latestTracks
    summary.value = dashboard
    recentSongs.value = recent
    mostPlayedSongs.value = mostPlayed
    liveSongs.value = current
  } catch (error) {
    loadError.value = 'Please check your connection and try again.'
    console.error('Error loading data:', error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  window.addEventListener('hashchange', syncView)
  if (view.value === 'dashboard') loadData()
  // Refresh only live cards; browsing lists/charts and the selected modal stay stable.
  refreshTimer = setInterval(async () => {
    if (view.value !== 'dashboard' || document.hidden) return
    try { liveSongs.value = await radioParadiseAPI.getCurrentSongs() }
    catch (error) { console.error('Could not refresh live songs:', error) }
  }, 30000)
})

onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  window.removeEventListener('hashchange', syncView)
})
</script>

<style scoped>
.header { position: relative; padding: .5rem 0 1.75rem; margin-bottom: 2rem; border-bottom: 1px solid #dddde9; }
.title { display: flex; align-items: center; gap: .75rem; font-size: clamp(1.5rem, 3vw, 2rem); line-height: 1.2; letter-spacing: -.035em; font-weight: 700; }
.icon { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 12px; background: var(--primary); color: white; font-size: 1.65rem; flex-shrink: 0; }
.subtitle { color: var(--text-secondary); margin: .65rem 0 0; font-size: .95rem; }
.main-nav { display: flex; gap: .35rem; position: absolute; right: 0; top: .75rem; padding: .3rem; border: 1px solid #e0dfed; border-radius: 12px; background: #ecebf3; }
.main-nav a { padding: .6rem 1.2rem; border-radius: 8px; color: var(--text-secondary); text-decoration: none; font-weight: 600; }
.main-nav a.selected { background: white; color: var(--primary); box-shadow: 0 2px 6px #2526380a; }
.dashboard { display: flex; flex-direction: column; gap: 1.5rem; }
.section-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: .5rem; margin-bottom: 1rem; }
.section-heading span { font-size: .85rem; color: var(--text-secondary); }
.section-title { font-size: 1.3rem; letter-spacing: -.02em; }
.channel-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1rem; }
.stats-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1rem; }
.charts-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 1.5rem; margin-bottom: 1.5rem; }
.chart-card { padding: 1.5rem; }
.chart-title { font-size: 1.15rem; margin-bottom: 1.25rem; letter-spacing: -.015em; }
.archive-links { padding: 1.5rem; display: flex; flex-wrap: wrap; align-items: center; gap: 1rem; }
.archive-links div { flex: 1 1 300px; }
.archive-links p { margin-top: .5rem; color: var(--text-secondary); }
.archive-links a { color: var(--primary); font-weight: 600; }
.loading { padding: 4rem; text-align: center; color: var(--text-secondary); }
@media (max-width: 980px) { .channel-grid { grid-template-columns: 1fr; } }
@media (max-width: 850px) { .main-nav { position: static; width: fit-content; margin-top: 1.25rem; } .charts-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .header { margin-bottom: 1.5rem; } .stats-grid { gap: .5rem; } .chart-card { padding: 1.1rem; } }

.load-error { padding: 2rem; text-align: center; }
.load-error p { color: var(--text-secondary); margin: .75rem 0 1.25rem; }
.load-error button { padding: .65rem 1.2rem; min-height: 44px; background: var(--primary); border: 0; border-radius: 8px; color: white; cursor: pointer; }
</style>
