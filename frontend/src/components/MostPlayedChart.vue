<template>
  <div class="chart-container">
    <Bar :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Bar } from 'vue-chartjs'
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, Title, Tooltip, Legend } from 'chart.js'

ChartJS.register(CategoryScale, LinearScale, BarElement, Title, Tooltip, Legend)

const props = defineProps({
  songs: Array
})

const chartData = computed(() => {
  const topSongs = props.songs.slice(0, 10)
  
  return {
    labels: topSongs.map(song => `${song.artist} - ${song.title}`.substring(0, 30) + '...'),
    datasets: [{
      label: 'Play Count',
      data: topSongs.map(song => song.playCount),
      backgroundColor: '#667eea',
      borderRadius: 8,
      borderSkipped: false
    }]
  }
})

const chartOptions = {
  responsive: true,
  maintainAspectRatio: true,
  indexAxis: 'y',
  plugins: {
    legend: {
      display: false
    },
    tooltip: {
      backgroundColor: 'rgba(0, 0, 0, 0.8)',
      padding: 12,
      cornerRadius: 8
    }
  },
  scales: {
    x: {
      beginAtZero: true,
      ticks: {
        precision: 0
      },
      grid: {
        display: false
      }
    },
    y: {
      grid: {
        display: false
      }
    }
  }
}
</script>

<style scoped>
.chart-container {
  position: relative;
  height: 350px;
}
</style>
