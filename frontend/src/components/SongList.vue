<template>
  <div class="song-list card">
    <h3 class="list-title">{{ title }} ({{ songs.length }})</h3>
    
    <div v-if="songs.length === 0" class="empty-state">
      <span class="empty-icon">🎵</span>
      <p>No songs yet</p>
    </div>

    <div v-else class="list-container">
      <TransitionGroup name="list" tag="div" class="songs-grid">
        <div
          v-for="song in songs"
          :key="song.id"
          class="song-item"
        >
          <img
            v-if="song.coverUrl"
            :src="song.coverUrl"
            :alt="`${song.album} cover`"
            class="song-cover"
            @error="handleImageError"
          />
          <div v-else class="song-cover-placeholder">
            <span>🎵</span>
          </div>

          <div class="song-details">
            <p class="song-name">{{ song.title }}</p>
            <p class="song-meta">{{ song.artist }}</p>
            <p class="song-meta-secondary">
              {{ song.album }}
              <span v-if="song.year">• {{ song.year }}</span>
            </p>
          </div>

          <div class="song-actions">
            <span class="channel-tag" :style="getChannelStyle(song.channel)">
              {{ getChannelName(song.channel) }}
            </span>
            <span class="play-count" :title="`Played ${song.playCount} times`">
              <span class="count-icon">🔁</span>
              {{ song.playCount }}
            </span>
          </div>
        </div>
      </TransitionGroup>
    </div>
  </div>
</template>

<script setup>
import { CHANNELS } from '../services/api.js'

defineProps({
  songs: Array,
  title: String
})

const handleImageError = (e) => {
  e.target.style.display = 'none'
}

const getChannelName = (channelId) => {
  return CHANNELS[channelId]?.name || 'Unknown'
}

const getChannelStyle = (channelId) => {
  const color = CHANNELS[channelId]?.color || '#999'
  return {
    background: color,
    color: 'white'
  }
}
</script>

<style scoped>
.song-list {
  padding: 2rem;
}

.list-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 1.5rem;
}

.empty-state {
  text-align: center;
  padding: 4rem 2rem;
  color: var(--text-secondary);
}

.empty-icon {
  font-size: 4rem;
  display: block;
  margin-bottom: 1rem;
  opacity: 0.5;
}

.list-container {
  max-height: 600px;
  overflow-y: auto;
  padding-right: 0.5rem;
}

.songs-grid {
  display: grid;
  gap: 1rem;
}

.song-item {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background: rgba(102, 126, 234, 0.05);
  border-radius: 12px;
  transition: all 0.3s ease;
}

.song-item:hover {
  background: rgba(102, 126, 234, 0.1);
  transform: translateX(4px);
}

.song-cover {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  object-fit: cover;
  flex-shrink: 0;
}

.song-cover-placeholder {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--primary), var(--secondary));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  flex-shrink: 0;
}

.song-details {
  flex: 1;
  min-width: 0;
}

.song-name {
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.song-meta {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 0.125rem;
}

.song-meta-secondary {
  font-size: 0.75rem;
  color: var(--text-secondary);
}

.song-actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  align-items: flex-end;
  flex-shrink: 0;
}

.channel-tag {
  padding: 0.25rem 0.75rem;
  border-radius: 12px;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.play-count {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.875rem;
  color: var(--text-secondary);
  font-weight: 500;
}

.count-icon {
  font-size: 1rem;
}

.list-enter-active,
.list-leave-active {
  transition: all 0.5s ease;
}

.list-enter-from {
  opacity: 0;
  transform: translateX(-30px);
}

.list-leave-to {
  opacity: 0;
  transform: translateX(30px);
}

@media (max-width: 640px) {
  .song-item {
    flex-wrap: wrap;
  }

  .song-actions {
    flex-direction: row;
    width: 100%;
    justify-content: space-between;
  }
}
</style>
