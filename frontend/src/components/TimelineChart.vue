<template>
  <div class="decade-chart-wrapper">
    <div class="chart-container">
      <Doughnut :data="chartData" :options="chartOptions" @click="handleChartClick" />
    </div>
    
    <!-- Song Panel for Selected Decade -->
    <transition name="slide-fade">
      <div v-if="selectedDecade" class="song-panel">
        <div class="panel-header">
          <h4>{{ selectedDecade }} Songs ({{ filteredSongs.length }})</h4>
          <button @click="selectedDecade = null" class="close-btn">✕</button>
        </div>
        <div class="panel-content">
          <div v-for="song in filteredSongs" :key="song.id" class="song-item">
            <img 
              v-if="song.coverUrl" 
              :src="song.coverUrl" 
              :alt="song.album"
              class="song-cover"
            />
            <div class="song-info">
              <div class="song-title">{{ song.title }}</div>
              <div class="song-artist">{{ song.artist }}</div>
              <div class="song-meta">
                <span class="year">{{ song.year }}</span>
                <span class="plays">▶ {{ song.playCount }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Doughnut } from 'vue-chartjs'
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js'

ChartJS.register(ArcElement, Tooltip, Legend)

const props = defineProps({
  songs: Array
})

const selectedDecade = ref(null)

const getDecade = (year) => {
  if (!year || year === 'Unknown') return 'Unknown'
  const yearNum = parseInt(year)
  if (isNaN(yearNum)) return 'Unknown'
  
  if (yearNum < 1980) return "70s & Earlier"
  if (yearNum < 1990) return "80s"
  if (yearNum < 2000) return "90s"
  if (yearNum < 2010) return "2000s"
  if (yearNum < 2020) return "2010s"
  return "2020s"
}

const chartData = computed(() => {
  // Group songs by decade
  const decadeGroups = {}
  
  props.songs.forEach(song => {
    const decade = getDecade(song.year)
    
    if (!decadeGroups[decade]) {
      decadeGroups[decade] = 0
    }
    decadeGroups[decade]++
  })

  // Remove 'Unknown' if it exists
  delete decadeGroups['Unknown']

  // Define order for decades
  const decadeOrder = ["70s & Earlier", "80s", "90s", "2000s", "2010s", "2020s"]
  const labels = []
  const data = []
  
  decadeOrder.forEach(decade => {
    if (decadeGroups[decade]) {
      labels.push(decade)
      data.push(decadeGroups[decade])
    }
  })

  return {
    labels,
    datasets: [{
      data,
      backgroundColor: [
        '#667eea', // 70s & Earlier - Purple
        '#f093fb', // 80s - Pink
        '#4facfe', // 90s - Blue
        '#00f2fe', // 2000s - Cyan
        '#43e97b', // 2010s - Green
        '#fa709a', // 2020s - Rose
      ],
      borderWidth: 4,
      borderColor: '#fff',
      hoverOffset: 15
    }]
  }
})

const chartOptions = {
  responsive: true,
  maintainAspectRatio: true,
  onClick: (event, elements) => {
    if (elements.length > 0) {
      const index = elements[0].index
      const decade = chartData.value.labels[index]
      selectedDecade.value = decade
    }
  },
  plugins: {
    legend: {
      position: 'right',
      labels: {
        padding: 20,
        font: {
          size: 16,
          family: 'Inter',
          weight: '600'
        },
        color: '#1a202c',
        usePointStyle: true,
        pointStyle: 'circle',
        boxWidth: 12,
        boxHeight: 12,
        generateLabels: function(chart) {
          const data = chart.data;
          if (data.labels.length && data.datasets.length) {
            return data.labels.map((label, i) => {
              const value = data.datasets[0].data[i];
              const total = data.datasets[0].data.reduce((a, b) => a + b, 0);
              const percentage = ((value / total) * 100).toFixed(1);
              return {
                text: `${label}: ${percentage}%`,
                fillStyle: data.datasets[0].backgroundColor[i],
                hidden: false,
                index: i
              };
            });
          }
          return [];
        }
      }
    },
    tooltip: {
      backgroundColor: 'rgba(0, 0, 0, 0.9)',
      padding: 16,
      cornerRadius: 10,
      titleFont: {
        size: 16,
        family: 'Inter',
        weight: 'bold'
      },
      bodyFont: {
        size: 15,
        family: 'Inter'
      },
      callbacks: {
        label: function(context) {
          const label = context.label || '';
          const value = context.parsed;
          const total = context.dataset.data.reduce((a, b) => a + b, 0);
          const percentage = ((value / total) * 100).toFixed(1);
          return `${value} songs (${percentage}%) - Click to view`;
        }
      }
    }
  }
}

const filteredSongs = computed(() => {
  if (!selectedDecade.value) return []
  
  return props.songs.filter(song => {
    return getDecade(song.year) === selectedDecade.value
  }).sort((a, b) => b.playCount - a.playCount)
})

const handleChartClick = () => {
  // Event is handled by chartOptions.onClick
}
</script>

<style scoped>
.decade-chart-wrapper {
  position: relative;
  display: flex;
  gap: 2rem;
}

.chart-container {
  flex: 1;
  position: relative;
  height: 350px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.song-panel {
  position: absolute;
  right: 0;
  top: 0;
  width: 350px;
  max-height: 500px;
  background: rgba(255, 255, 255, 0.98);
  backdrop-filter: blur(10px);
  border-radius: 16px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  z-index: 10;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1.25rem 1.5rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.panel-header h4 {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
}

.close-btn {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: white;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 1.2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.close-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.panel-content {
  max-height: 440px;
  overflow-y: auto;
  padding: 1rem;
}

.song-item {
  display: flex;
  gap: 1rem;
  padding: 0.75rem;
  border-radius: 10px;
  margin-bottom: 0.75rem;
  background: rgba(255, 255, 255, 0.5);
  transition: all 0.2s;
}

.song-item:hover {
  background: rgba(102, 126, 234, 0.1);
  transform: translateX(-4px);
}

.song-cover {
  width: 50px;
  height: 50px;
  border-radius: 8px;
  object-fit: cover;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.song-info {
  flex: 1;
  min-width: 0;
}

.song-title {
  font-weight: 600;
  font-size: 0.95rem;
  color: #2d3748;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 0.25rem;
}

.song-artist {
  font-size: 0.85rem;
  color: #718096;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 0.25rem;
}

.song-meta {
  display: flex;
  gap: 1rem;
  font-size: 0.8rem;
  color: #a0aec0;
}

.year {
  font-weight: 600;
}

.plays {
  color: #667eea;
}

/* Smooth scrollbar for panel */
.panel-content::-webkit-scrollbar {
  width: 6px;
}

.panel-content::-webkit-scrollbar-track {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 3px;
}

.panel-content::-webkit-scrollbar-thumb {
  background: rgba(102, 126, 234, 0.3);
  border-radius: 3px;
}

.panel-content::-webkit-scrollbar-thumb:hover {
  background: rgba(102, 126, 234, 0.5);
}

/* Slide fade transition */
.slide-fade-enter-active {
  transition: all 0.3s ease-out;
}

.slide-fade-leave-active {
  transition: all 0.2s ease-in;
}

.slide-fade-enter-from {
  transform: translateX(20px);
  opacity: 0;
}

.slide-fade-leave-to {
  transform: translateX(20px);
  opacity: 0;
}
</style>
