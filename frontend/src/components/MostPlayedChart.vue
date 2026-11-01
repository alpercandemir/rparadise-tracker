<template>
  <ol v-if="topSongs.length" class="ranking" aria-label="Most played tracks">
    <li v-for="(song, index) in topSongs" :key="song.id">
      <button class="ranked-track" @click="$emit('select-song', song)" :aria-label="`View details for ${song.title} by ${song.artist}, ${song.playCount} ${song.playCount === 1 ? 'play' : 'plays'}`">
        <span class="rank">{{ index + 1 }}</span>
        <AlbumArtwork :src="song.coverUrl" :album="song.album" class="rank-cover" />
        <span class="track-info"><span class="track-title">{{ song.title }}</span><span class="artist">{{ song.artist }}</span></span>
        <span class="count">{{ song.playCount }} <span>{{ song.playCount === 1 ? 'play' : 'plays' }}</span></span>
      </button>
    </li>
  </ol>
  <p v-else class="empty">Play counts will appear as tracks are collected.</p>
</template>

<script setup>
import { computed } from 'vue'
import AlbumArtwork from './AlbumArtwork.vue'
defineEmits(['select-song'])
const props = defineProps({ songs: Array })
const topSongs = computed(() => props.songs.slice(0, 10))
</script>

<style scoped>
.ranking { list-style: none; }
.ranked-track { width: 100%; display: flex; align-items: center; gap: .75rem; padding: .55rem .25rem; border: 0; border-radius: 8px; background: none; color: var(--text-primary); text-align: left; cursor: pointer; }
.ranked-track:hover { background: #f5f3fb; }
.rank { width: 1.25rem; color: var(--text-secondary); font-size: .75rem; text-align: center; font-variant-numeric: tabular-nums; }
.rank-cover { width: 36px; height: 36px; border-radius: 6px; }
.track-info { min-width: 0; flex: 1; }
.track-title, .artist { display: block; overflow-wrap: anywhere; }
.track-title { font-size: .9rem; font-weight: 600; line-height: 1.35; }
.artist { font-size: .78rem; color: var(--text-secondary); }
.count { font-variant-numeric: tabular-nums; font-size: .95rem; font-weight: 650; color: var(--primary); white-space: nowrap; }
.count span { font-size: .7rem; font-weight: 400; color: var(--text-secondary); }
.empty { color: var(--text-secondary); padding: 2rem 0; }
@media (max-width: 380px) { .ranked-track { gap: .5rem; } .rank-cover { width: 30px; height: 30px; } }
</style>
