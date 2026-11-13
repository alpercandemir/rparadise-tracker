<template>
  <section class="discoveries card" aria-labelledby="discoveries-title" :aria-busy="loading">
    <div class="heading">
      <div>
        <p class="eyebrow">FRESH FINDS · LAST 30 DAYS</p>
        <h2 id="discoveries-title">New Discoveries <span v-if="!loading && !error" class="count">{{ page.totalElements.toLocaleString() }}</span></h2>
        <p class="description">First heard by this tracker. A little more music to explore.</p>
      </div>
      <a :href="discoveriesLink">View all discoveries <span aria-hidden="true">→</span></a>
    </div>

    <p v-if="loading" class="message" role="status">Finding your latest discoveries…</p>
    <div v-else-if="error" class="message" role="alert">
      <p>Could not load new discoveries.</p>
      <button class="retry" @click="loadDiscoveries">Try again</button>
    </div>
    <div v-else-if="!page.items.length" class="message">
      <h3>No new discoveries yet</h3>
      <p>Songs will appear here when the tracker hears them for the first time.</p>
    </div>
    <ul v-else class="discovery-grid">
      <li v-for="song in page.items.slice(0, 6)" :key="song.id">
        <button class="discovery" :aria-label="`View details for ${song.title} by ${song.artist}`" @click="$emit('select-song', song)">
          <AlbumArtwork :src="song.coverUrl" :album="song.album" loading="lazy" />
          <span class="song-title">{{ song.title }}</span>
          <span class="artist">{{ song.artist }}</span>
          <span class="discovered">Discovered <time :datetime="song.firstPlayed" :title="formatRecordTime(song.firstPlayed)">{{ discoveryDate(song.firstPlayed) }}</time></span>
        </button>
      </li>
    </ul>
    <p class="footnote">New to the archive · All channels combined · Newest discoveries first</p>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AlbumArtwork from './AlbumArtwork.vue'
import { radioParadiseAPI } from '../services/api.js'
import { formatRecordTime } from '../services/records.js'

defineEmits(['select-song'])
const discoveriesLink = '#/songs?discovery=30d&sort=firstPlayed&direction=desc'
const page = ref({ items: [], totalElements: 0 })
const loading = ref(true)
const error = ref(false)
const dateFormatter = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })
const discoveryDate = value => dateFormatter.format(new Date(value))
let controller

async function loadDiscoveries() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = false
  try {
    const result = await radioParadiseAPI.getRecordsPage('songs', {
      discovery: '30d', sort: 'firstPlayed', direction: 'desc', page: 0, size: 25
    }, request.signal)
    if (!request.signal.aborted) page.value = result
  } catch (failure) {
    if (!request.signal.aborted) error.value = true
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}

onMounted(loadDiscoveries)
onBeforeUnmount(() => controller?.abort())
</script>

<style scoped>
.discoveries { background: linear-gradient(135deg, #f3f0fc, #fff 60%); }
.heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 1rem; margin-bottom: 1.4rem; }
.eyebrow { color: var(--primary); font-size: .7rem; font-weight: 700; letter-spacing: .13em; margin-bottom: .35rem; }
h2 { display: flex; align-items: center; gap: .6rem; font-size: 1.3rem; letter-spacing: -.02em; }
.count { background: #e6e0f6; color: var(--primary); border-radius: 20px; padding: .1rem .6rem; font-size: .8rem; letter-spacing: 0; }
.description { color: var(--text-secondary); font-size: .9rem; margin-top: .35rem; }
.heading a { font-size: .85rem; font-weight: 600; padding: .5rem 0; }
.discovery-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1.1rem; list-style: none; }
.discovery { display: flex; flex-direction: column; width: 100%; height: 100%; padding: 0; border: 0; border-radius: 10px; background: transparent; text-align: left; cursor: pointer; color: var(--text-primary); }
.discovery .artwork { width: 100%; margin-bottom: .75rem; }
.song-title { font-size: .95rem; font-weight: 650; overflow-wrap: anywhere; }
.discovery:hover .song-title { color: var(--primary); text-decoration: underline; }
.artist { font-size: .85rem; color: var(--text-secondary); overflow-wrap: anywhere; margin: .15rem 0 .5rem; }
.discovered { margin-top: auto; font-size: .75rem; color: var(--primary); }
.footnote { margin-top: 1.3rem; padding-top: 1rem; border-top: 1px solid #e3dff0; color: var(--text-secondary); font-size: .75rem; }
.message { padding: 2rem 0; text-align: center; color: var(--text-secondary); }
.message h3 { font-size: 1rem; margin-bottom: .4rem; }
.retry { padding: .5rem 1rem; margin-top: .75rem; border: 1px solid var(--primary); border-radius: 8px; background: white; color: var(--primary); cursor: pointer; }
@media (max-width: 1000px) { .discovery-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 540px) { .discovery-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; } .discoveries { padding: 1.1rem; } }
</style>
