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
          role="button" tabindex="0" :aria-label="`View details for ${song.title} by ${song.artist}`"
          @click="$emit('select-song', song)"
          @keydown.enter.prevent="$emit('select-song', song)"
          @keydown.space.prevent="$emit('select-song', song)"
        >
          <AlbumArtwork :src="song.coverUrl" :album="song.album" class="song-cover" loading="lazy" />

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
defineEmits(['select-song'])
import AlbumArtwork from './AlbumArtwork.vue'
import { CHANNELS } from '../services/api.js'

defineProps({
  songs: Array,
  title: String
})

const getChannelName = (channelId) => {
  if (Object.values(CHANNELS).some(channel => channel.name === channelId)) {
    return channelId
  }
  return CHANNELS[channelId]?.name || 'Unknown'
}

const getChannelStyle = (channelId) => {
  const namedChannel = Object.values(CHANNELS).find(channel => channel.name === channelId)
  if (namedChannel) {
    return {
      background: namedChannel.color,
      color: 'white'
    }
  }

  const color = CHANNELS[channelId]?.color || '#999'
  return {
    background: color,
    color: 'white'
  }
}
</script>

<style scoped>
.song-item { cursor: pointer; display: flex; align-items: center; gap: 1rem; padding: 1rem 0; border-bottom: 1px solid #eeedf4; }
.song-item:last-child { border-bottom: 0; }
.song-item:hover { background: #f7f6fb; }
.song-list { padding: 1.5rem; }
.list-title { font-size: 1.15rem; margin-bottom: .75rem; }
.empty-state { text-align: center; padding: 3rem; color: var(--text-secondary); }
.empty-icon { display: block; font-size: 2rem; }
.list-container { max-height: 600px; overflow-y: auto; padding: .25rem; }
.song-cover { width: 56px; height: 56px; }
.song-details { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.song-name { font-weight: 600; font-size: .95rem; }
.song-meta { font-size: .85rem; color: var(--text-secondary); }
.song-meta-secondary { font-size: .78rem; color: var(--text-secondary); }
.song-actions { display: flex; flex-direction: column; align-items: flex-end; gap: .5rem; flex-shrink: 0; }
.channel-tag { padding: .2rem .6rem; border-radius: 6px; font-size: .7rem; font-weight: 650; }
.play-count { font-size: .8rem; color: var(--text-secondary); }
@media (max-width: 640px) { .song-list { padding: 1rem; } .song-item { flex-wrap: wrap; gap: .75rem; } .song-actions { flex-direction: row; width: 100%; justify-content: space-between; padding-left: 68px; } }

</style>
