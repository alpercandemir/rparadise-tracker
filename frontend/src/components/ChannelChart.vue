<template>
  <div class="channel-summary">
    <div class="chart-container"><Doughnut :data="chartData" :options="chartOptions" aria-label="Distribution of saved tracks by channel" role="img" /></div>
    <ul class="legend" aria-label="Tracks by channel">
      <li v-for="(channel, id) in CHANNELS" :key="id">
        <span class="swatch" :style="{ background: channel.color }" aria-hidden="true"></span>
        <span>{{ channel.name }}</span><strong>{{ counts[id] }} <span>tracks</span></strong>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Doughnut } from 'vue-chartjs'
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js'
import { CHANNELS } from '../services/api.js'
ChartJS.register(ArcElement, Tooltip, Legend)
const props = defineProps({ songs: Array })
const counts = computed(() => {
  const totals = { '0': 0, '1': 0, '2': 0 }
  for (const song of props.songs) {
    const id = Object.keys(CHANNELS).find(id => song.channel === id || song.channel === CHANNELS[id].name)
    if (id !== undefined) totals[id]++
  }
  return totals
})
const chartData = computed(() => ({
  labels: Object.values(CHANNELS).map(channel => channel.name),
  datasets: [{ data: Object.values(counts.value), backgroundColor: Object.values(CHANNELS).map(channel => channel.color), borderWidth: 4, borderColor: '#fff', hoverOffset: 4 }]
}))
const chartOptions = { responsive: true, maintainAspectRatio: false, cutout: '72%', plugins: { legend: { display: false } } }
</script>

<style scoped>
.channel-summary { display: grid; grid-template-columns: minmax(0, 1fr); justify-items: center; gap: 2rem; padding: 1.5rem 0; }
.chart-container { width: min(100%, 280px); height: 280px; }
.legend { list-style: none; width: min(100%, 340px); }
.legend li { display: flex; align-items: center; gap: .7rem; padding: .75rem 0; border-bottom: 1px solid #eeedf4; font-size: .95rem; }
.swatch { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
strong { margin-left: auto; font-variant-numeric: tabular-nums; }
strong span { font-size: .8rem; font-weight: 400; color: var(--text-secondary); }
</style>
