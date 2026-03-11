<template>
  <div class="chart-container">
    <Doughnut :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Doughnut } from 'vue-chartjs'
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js'

ChartJS.register(ArcElement, Tooltip, Legend)

const props = defineProps({
  songs: Array
})

const chartData = computed(() => {
  const channelCounts = {
    '0': 0,
    '1': 0,
    '2': 0
  }

  props.songs.forEach(song => {
    if (channelCounts.hasOwnProperty(song.channel)) {
      channelCounts[song.channel]++
    }
  })

  return {
    labels: ['Main Mix', 'Rock Mix', 'Mellow Mix'],
    datasets: [{
      data: [channelCounts['0'], channelCounts['1'], channelCounts['2']],
      backgroundColor: ['#667eea', '#f093fb', '#4facfe'],
      borderWidth: 0,
      hoverOffset: 10
    }]
  }
})

const chartOptions = {
  responsive: true,
  maintainAspectRatio: true,
  plugins: {
    legend: {
      position: 'bottom',
      labels: {
        padding: 20,
        font: {
          size: 13,
          family: 'Inter'
        }
      }
    },
    tooltip: {
      backgroundColor: 'rgba(0, 0, 0, 0.8)',
      padding: 12,
      cornerRadius: 8,
      titleFont: {
        size: 14
      },
      bodyFont: {
        size: 13
      }
    }
  }
}
</script>

<style scoped>
.chart-container {
  position: relative;
  height: 300px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
