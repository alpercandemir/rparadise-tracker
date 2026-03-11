<template>
  <div class="current-song-card card" :style="{ borderLeft: `4px solid ${channelColor}` }">
    <div class="channel-header">
      <span class="channel-badge" :style="{ background: channelColor }">
        {{ channelName }}
      </span>
      <span v-if="song" class="live-indicator">
        <span class="pulse-dot"></span>
        LIVE
      </span>
    </div>

    <div v-if="song" class="song-content">
      <div class="album-cover-container">
        <img
          v-if="song.coverUrl"
          :src="song.coverUrl"
          :alt="`${song.album} cover`"
          class="album-cover"
          @error="handleImageError"
        />
        <div v-else class="album-cover-placeholder">
          <span>🎵</span>
        </div>
      </div>

      <div class="song-info">
        <h3 class="song-title">{{ song.title }}</h3>
        <p class="song-artist">{{ song.artist }}</p>
        <p class="song-album">{{ song.album }} <span v-if="song.year">({{ song.year }})</span></p>
        <div class="song-stats">
          <span class="stat">
            <span class="stat-icon">🔁</span>
            {{ song.playCount }} plays
          </span>
          <span class="stat">
            <span class="stat-icon">⏰</span>
            {{ formatTime(song.lastPlayed) }}
          </span>
        </div>
      </div>
    </div>

    <div v-else class="no-song">
      <p>No song data available</p>
    </div>
  </div>
</template>

<script setup>
defineProps({
  channelId: String,
  channelName: String,
  channelColor: String,
  song: Object
})

const handleImageError = (e) => {
  e.target.style.display = 'none'
}

const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  const now = new Date()
  const diff = Math.floor((now - date) / 1000)

  if (diff < 60) return 'Just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return date.toLocaleDateString()
}
</script>

<style scoped>
.current-song-card {
  animation: slideUp 0.5s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.channel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}

.channel-badge {
  padding: 0.5rem 1rem;
  border-radius: 20px;
  color: white;
  font-weight: 600;
  font-size: 0.875rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.live-indicator {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--text-secondary);
  font-size: 0.875rem;
  font-weight: 600;
}

.pulse-dot {
  width: 8px;
  height: 8px;
  background: #ef4444;
  border-radius: 50%;
  animation: pulse-animation 2s ease-in-out infinite;
}

@keyframes pulse-animation {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.5;
    transform: scale(1.2);
  }
}

.song-content {
  display: flex;
  gap: 1.5rem;
}

.album-cover-container {
  flex-shrink: 0;
}

.album-cover {
  width: 120px;
  height: 120px;
  border-radius: 12px;
  object-fit: cover;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.2);
}

.album-cover-placeholder {
  width: 120px;
  height: 120px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--primary), var(--secondary));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 3rem;
}

.song-info {
  flex: 1;
  min-width: 0;
}

.song-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.song-artist {
  font-size: 1rem;
  color: var(--text-secondary);
  margin-bottom: 0.25rem;
  font-weight: 500;
}

.song-album {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 1rem;
}

.song-stats {
  display: flex;
  gap: 1.5rem;
  flex-wrap: wrap;
}

.stat {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.stat-icon {
  font-size: 1rem;
}

.no-song {
  padding: 2rem;
  text-align: center;
  color: var(--text-secondary);
  font-style: italic;
}

@media (max-width: 640px) {
  .song-content {
    flex-direction: column;
    align-items: center;
    text-align: center;
  }

  .song-stats {
    justify-content: center;
  }
}
</style>
