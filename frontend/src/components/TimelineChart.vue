<template>
  <div class="decade-chart-wrapper">
    <div class="decade-summary">
      <div class="chart-container"><Doughnut :data="chartData" :options="chartOptions" aria-label="Distribution of tracks by decade; select a decade below to explore" role="img" /></div>
      <div class="decade-options" aria-label="Browse tracks by decade">
        <p class="hint">Choose a decade to explore its tracks.</p>
        <button v-for="(label, index) in chartData.labels" :key="label" class="decade-option"
          :aria-pressed="selectedDecade === label" @click="selectedDecade = label">
          <span class="swatch" :style="{ background: colors[index] }" aria-hidden="true"></span>
          <span>{{ label }}</span><strong>{{ chartData.datasets[0].data[index] }} tracks</strong>
        </button>
      </div>
    </div>
    <section v-if="selectedDecade" class="song-panel" aria-label="Selected decade tracks">
      <div class="panel-header"><h4>{{ selectedDecade }} · {{ filteredSongs.length }} tracks</h4>
        <button @click="selectedDecade = null" class="close-btn" aria-label="Close decade tracks">✕</button>
      </div>
      <div class="panel-content">
        <button v-for="song in filteredSongs" :key="song.id" class="song-item" @click="$emit('select-song', song)"
          :aria-label="`View details for ${song.title} by ${song.artist}`">
          <AlbumArtwork :src="song.coverUrl" :album="song.album" class="song-cover" loading="lazy" />
          <span class="song-info"><span class="song-title">{{ song.title }}</span><span class="song-artist">{{ song.artist }} · {{ song.year }}</span></span>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Doughnut } from 'vue-chartjs'
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js'
import AlbumArtwork from './AlbumArtwork.vue'
import { getDecade, groupDecades } from '../services/charts.js'
ChartJS.register(ArcElement, Tooltip, Legend)
defineEmits(['select-song'])
const props = defineProps({ songs: Array })
const selectedDecade = ref(null)
const colors = ['#5145a6', '#9a3971', '#176d82', '#94703a', '#44806b', '#807097']
const chartData = computed(() => {
  const groups = groupDecades(props.songs)
  return { labels: groups.map(group => group.label), datasets: [{ data: groups.map(group => group.count), backgroundColor: colors, borderWidth: 4, borderColor: '#fff', hoverOffset: 4 }] }
})
const chartOptions = {
  responsive: true, maintainAspectRatio: false, cutout: '65%',
  onClick: (_event, elements) => { if (elements.length) selectedDecade.value = chartData.value.labels[elements[0].index] },
  plugins: { legend: { display: false } }
}
const filteredSongs = computed(() => props.songs.filter(song => getDecade(song.year) === selectedDecade.value).sort((a, b) => b.playCount - a.playCount))
</script>

<style scoped>
.decade-summary { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: center; gap: 2rem; max-width: 850px; margin: auto; }
.chart-container { width: min(100%, 260px); height: 260px; justify-self: center; }
.hint { font-size: .85rem; color: var(--text-secondary); margin-bottom: .75rem; }
.decade-option { width: 100%; display: flex; align-items: center; gap: .65rem; padding: .75rem; min-height: 44px; border: 1px solid transparent; border-radius: 8px; background: none; color: var(--text-primary); text-align: left; cursor: pointer; font-size: .9rem; }
.decade-option:hover, .decade-option[aria-pressed="true"] { background: #f1eef9; border-color: #d9d4ef; }
.swatch { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.decade-option strong { margin-left: auto; color: var(--text-secondary); font-size: .8rem; font-weight: 500; white-space: nowrap; }
.song-panel { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid #e3e3ee; }
.panel-header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; margin-bottom: .75rem; }
.close-btn { width: 44px; height: 44px; border: 0; border-radius: 8px; background: #eeecf7; color: var(--primary); cursor: pointer; }
.panel-content { max-height: 360px; overflow-y: auto; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .5rem; padding: .25rem; }
.song-item { display: flex; align-items: center; gap: .75rem; padding: .75rem; border: 0; border-radius: 8px; background: #f8f7fb; text-align: left; color: var(--text-primary); cursor: pointer; }
.song-cover { width: 44px; height: 44px; }
.song-info { min-width: 0; overflow-wrap: anywhere; }
.song-title { display: block; font-size: .9rem; font-weight: 600; }
.song-artist { display: block; font-size: .8rem; color: var(--text-secondary); }
@media (max-width: 640px) { .decade-summary, .panel-content { grid-template-columns: 1fr; } .decade-summary { gap: 1rem; } }
</style>
