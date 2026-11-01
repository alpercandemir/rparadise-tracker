<template>
  <div class="container">
    <header class="header">
      <h1 class="title">
        <span class="icon" aria-hidden="true">♫</span>
        Radio Paradise Tracker
      </h1>
      <p class="subtitle">Your listening archive · Main, Mellow & Rock</p>
      <nav class="main-nav" aria-label="Main navigation">
        <a href="#/" :aria-current="!isRecords ? 'page' : undefined" :class="{ selected: !isRecords }">Dashboard</a>
        <a href="#/records" :aria-current="isRecords ? 'page' : undefined" :class="{ selected: isRecords }">Records</a>
      </nav>
    </header>

    <RecordsPage v-if="isRecords" @select-song="openSong" />
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
            :value="allSongs.length"
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
      <section class="charts-section">
        <div class="charts-grid">
          <div class="card chart-card">
            <h3 class="chart-title">Tracks by Channel</h3>
            <ChannelChart :songs="allSongs" />
          </div>
          <div class="card chart-card">
            <h3 class="chart-title">Most Played</h3>
            <MostPlayedChart :songs="mostPlayedSongs" @select-song="openSong" />
          </div>
        </div>
        
        <!-- Timeline Chart (Full Width) -->
        <div class="card chart-card timeline-chart">
          <h3 class="chart-title">Songs by Decade</h3>
          <TimelineChart :songs="allSongs" @select-song="openSong" />
        </div>
      </section>

      <!-- Song Lists -->
      <section class="lists-section">
        <div class="tabs">
          <button
            v-for="tab in tabs"
            :key="tab.id"
            :class="['tab', { active: activeTab === tab.id }]"
            :aria-pressed="activeTab === tab.id"
            @click="activeTab = tab.id"
          >
            {{ tab.label }}
          </button>
        </div>

        <Transition name="fade" mode="out-in">
          <SongList
            :key="activeTab"
            :songs="getTabSongs()"
            :title="getTabTitle()"
            @select-song="openSong"
          />
        </Transition>
      </section>
    </div>
    <SongDetailsModal v-if="selectedSong" :song="selectedSong" @close="selectedSong = null" />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import RecordsPage from './components/RecordsPage.vue'
import { radioParadiseAPI, CHANNELS } from './services/api.js'
import CurrentSong from './components/CurrentSong.vue'
import StatsCard from './components/StatsCard.vue'
import ChannelChart from './components/ChannelChart.vue'
import MostPlayedChart from './components/MostPlayedChart.vue'
import TimelineChart from './components/TimelineChart.vue'
import SongList from './components/SongList.vue'
import SongDetailsModal from './components/SongDetailsModal.vue'

const loading = ref(true)
const loadError = ref('')
const allSongs = ref([])
const recentSongs = ref([])
const mostPlayedSongs = ref([])
const liveSongs = ref([])
const selectedSong = ref(null)
const openSong = song => { selectedSong.value = { ...song, id: song.songId ?? song.id } }
const channels = CHANNELS
const activeTab = ref('all')
const isRecords = ref(window.location.hash.startsWith('#/records'))
let refreshTimer

const syncView = () => {
  const previous = isRecords.value
  isRecords.value = window.location.hash.startsWith('#/records')
  if (previous && !isRecords.value) loadData()
}

const tabs = [
  { id: 'all', label: 'All Songs' },
  { id: 'recent', label: 'Recent' },
  { id: 'main', label: 'Main Mix' },
  { id: 'rock', label: 'Rock Mix' },
  { id: 'mellow', label: 'Mellow Mix' }
]

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

const getTabSongs = () => {
  switch (activeTab.value) {
    case 'all': return allSongs.value
    case 'recent': return recentSongs.value
    case 'main': return allSongs.value.filter(s => isChannelMatch(s.channel, '0'))
    case 'rock': return allSongs.value.filter(s => isChannelMatch(s.channel, '2'))
    case 'mellow': return allSongs.value.filter(s => isChannelMatch(s.channel, '1'))
    default: return allSongs.value
  }
}

const getTabTitle = () => {
  const tab = tabs.find(t => t.id === activeTab.value)
  return tab ? tab.label : 'Songs'
}

const loadData = async () => {
  loadError.value = ''
  try {
    // Only show loading state on initial load (when allSongs is empty)
    if (allSongs.value.length === 0) {
      loading.value = true
    }
    const [songs, recent, mostPlayed, current] = await Promise.all([
      radioParadiseAPI.getAllSongs(),
      radioParadiseAPI.getRecentSongs(),
      radioParadiseAPI.getMostPlayedSongs(),
      radioParadiseAPI.getCurrentSongs()
    ])
    allSongs.value = songs
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
  if (!isRecords.value) loadData()
  // Refresh only live cards; browsing lists/charts and the selected modal stay stable.
  refreshTimer = setInterval(async () => {
    if (isRecords.value || document.hidden) return
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
.tabs { display: flex; gap: .4rem; flex-wrap: wrap; margin-bottom: 1rem; }
.tab { padding: .65rem 1rem; min-height: 44px; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--text-secondary); font-weight: 600; cursor: pointer; }
.tab:hover { background: #eeecf7; }
.tab.active { color: var(--primary); background: #eeecf7; border-color: #d9d4ef; }
.loading { padding: 4rem; text-align: center; color: var(--text-secondary); }
@media (max-width: 980px) { .channel-grid { grid-template-columns: 1fr; } }
@media (max-width: 850px) { .main-nav { position: static; width: fit-content; margin-top: 1.25rem; } .charts-grid { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .header { margin-bottom: 1.5rem; } .stats-grid { gap: .5rem; } .chart-card { padding: 1.1rem; } .tab { padding: .6rem .7rem; } }

.load-error { padding: 2rem; text-align: center; }
.load-error p { color: var(--text-secondary); margin: .75rem 0 1.25rem; }
.load-error button { padding: .65rem 1.2rem; min-height: 44px; background: var(--primary); border: 0; border-radius: 8px; color: white; cursor: pointer; }
</style>
