<template>
  <div class="container">
    <header class="header">
      <h1 class="title">
        <span class="icon">🎵</span>
        Radio Paradise Tracker
      </h1>
      <p class="subtitle">Live music tracking across all channels</p>
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

    <div v-else class="dashboard">
      <!-- Current Playing Songs -->
      <section class="current-songs">
        <h2 class="section-title">Now Playing</h2>
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
            title="Recent Plays"
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
.main-nav { display: flex; justify-content: center; gap: .5rem; margin-top: 1.5rem; }
.main-nav a { padding: .7rem 1.4rem; border-radius: 10px; color: white; text-decoration: none; background: rgba(255,255,255,.15); font-weight: 600; }
.main-nav a.selected { background: white; color: var(--primary); }
.header {
  text-align: center;
  margin-bottom: 3rem;
  animation: fadeInDown 0.6s ease;
}

@keyframes fadeInDown {
  from {
    opacity: 0;
    transform: translateY(-30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.title {
  font-size: 3rem;
  font-weight: 700;
  color: white;
  text-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
  margin-bottom: 0.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
}

.icon {
  font-size: 3rem;
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.1); }
}

.subtitle {
  font-size: 1.25rem;
  color: rgba(255, 255, 255, 0.9);
  font-weight: 300;
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1.5rem;
  padding: 4rem;
  color: white;
  font-size: 1.25rem;
}

.spinner {
  width: 50px;
  height: 50px;
  border: 4px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.dashboard {
  display: flex;
  flex-direction: column;
  gap: 2rem;
}

.section-title {
  font-size: 1.75rem;
  font-weight: 600;
  color: white;
  margin-bottom: 1.5rem;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
}

.channel-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(350px, 1fr));
  gap: 1.5rem;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 1.5rem;
}

.charts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 1.5rem;
  margin-bottom: 1.5rem;
}

.timeline-chart {
  width: 100%;
  padding: 2rem;
}

.chart-card {
  padding: 2rem;
}

.chart-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 1.5rem;
}

.tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  flex-wrap: wrap;
}

.tab {
  padding: 0.75rem 1.5rem;
  border: none;
  background: rgba(255, 255, 255, 0.2);
  color: white;
  border-radius: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.tab:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: translateY(-2px);
}

.tab.active {
  background: white;
  color: var(--primary);
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
}

@media (max-width: 768px) {
  .title {
    font-size: 2rem;
  }
  
  .channel-grid,
  .charts-grid {
    grid-template-columns: 1fr;
  }
}
</style>
