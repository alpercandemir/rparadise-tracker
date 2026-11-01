<template>
  <div class="current-song-card card" :style="{ borderLeft: `4px solid ${channelColor}` }"
    :role="song ? 'button' : undefined" :tabindex="song ? 0 : undefined"
    :aria-label="song ? `View details for ${song.title} by ${song.artist}` : undefined"
    @click="song && $emit('select-song', song)"
    @keydown.enter.prevent="song && $emit('select-song', song)"
    @keydown.space.prevent="song && $emit('select-song', song)">
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
      <AlbumArtwork :src="song.coverUrl" :album="song.album" class="album-cover" />

      <div class="song-info">
        <h3 class="song-title">{{ song.title }}</h3>
        <p class="song-artist">{{ song.artist }}</p>
        <p class="song-album">{{ song.album }} <span v-if="song.year">({{ song.year }})</span></p>
        <div class="song-stats">
          <span class="stat">
            <span class="stat-icon">🔁</span>
            {{ song.playCount }} {{ song.playCount === 1 ? 'play' : 'plays' }}
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
import AlbumArtwork from './AlbumArtwork.vue'
defineEmits(['select-song'])
defineProps({
  channelId: String,
  channelName: String,
  channelColor: String,
  song: Object
})

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
.current-song-card[role="button"] { cursor: pointer; }
.current-song-card:hover { border-color: #bfb9d9; box-shadow: 0 6px 24px #28224910; }
.channel-header { display: flex; align-items: center; justify-content: space-between; gap: .5rem; margin-bottom: 1.25rem; }
.channel-badge { padding: .3rem .7rem; border-radius: 6px; color: white; font-size: .75rem; font-weight: 700; }
.live-indicator { display: flex; align-items: center; gap: .4rem; color: #426152; font-size: .7rem; letter-spacing: .06em; font-weight: 700; }
.pulse-dot { width: 6px; height: 6px; background: #33815b; border-radius: 50%; }
.song-content { display: flex; gap: 1rem; align-items: flex-start; }
.album-cover { width: 88px; height: 88px; }
.song-info { flex: 1; min-width: 0; }
.song-title { font-size: 1.05rem; line-height: 1.4; font-weight: 650; overflow-wrap: anywhere; margin-bottom: .3rem; }
.song-artist { font-size: .95rem; color: var(--text-secondary); }
.song-album { font-size: .8rem; color: var(--text-secondary); margin-top: .2rem; overflow-wrap: anywhere; }
.song-stats { display: flex; flex-wrap: wrap; gap: .35rem 1rem; margin-top: .85rem; font-size: .75rem; color: var(--text-secondary); }
.stat { display: flex; align-items: center; gap: .35rem; }
.stat-icon { font-size: .8rem; }
.no-song { padding: 1.5rem 0; color: var(--text-secondary); }
@media (max-width: 380px) { .album-cover { width: 64px; height: 64px; } }

</style>
