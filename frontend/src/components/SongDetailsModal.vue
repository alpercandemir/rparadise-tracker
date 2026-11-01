<template>
  <Teleport to="body">
    <dialog ref="dialog" class="song-dialog" aria-labelledby="song-detail-title"
      @cancel.prevent="$emit('close')" @click="onBackdropClick">
      <div class="detail-header">
        <span class="eyebrow">SONG DETAILS</span>
        <button class="close-button" autofocus aria-label="Close song details" @click="$emit('close')">✕</button>
      </div>
      <div class="detail-scroll">
        <div class="detail-overview">
          <AlbumArtwork :src="track.coverUrl" :album="track.album" class="large-cover" />
          <div class="detail-facts">
            <p class="channel">{{ CHANNELS[track.channel]?.name || track.channel }}</p>
            <h2 id="song-detail-title">{{ track.title }}</h2>
            <p class="artist">{{ track.artist }}</p>
            <p class="album">{{ track.album }}<span v-if="track.year"> · {{ track.year }}</span></p>
            <dl>
              <div v-if="details?.durationSeconds"><dt>Duration</dt><dd>{{ duration(details.durationSeconds) }}</dd></div>
              <div><dt>Tracked plays</dt><dd>{{ track.playCount ?? '—' }}</dd></div>
              <div><dt>First observed</dt><dd>{{ localTime(track.firstPlayed) }}</dd></div>
              <div><dt>Last observed</dt><dd>{{ localTime(track.lastPlayed) }}</dd></div>
            </dl>
            <p class="tracking-note">Listening statistics are for this channel. Times use your timezone.</p>
            <nav class="listening-links" aria-label="Find this recording on music services">
              <a v-for="link in links" :key="link.provider" :href="link.url" target="_blank" rel="noopener noreferrer">{{ link.label }} ↗</a>
            </nav>
            <p v-if="recordingSource" class="recording-source">
              Recording matched via <a :href="recordingSource" target="_blank" rel="noopener noreferrer">MusicBrainz ↗</a>
              <span v-if="details?.recordingLinks?.isrc"> · ISRC: {{ details.recordingLinks.isrc }}</span>
            </p>
          </div>
        </div>
        <section class="lyrics-section" aria-labelledby="lyrics-title" :aria-busy="loading">
          <div class="lyrics-heading"><h3 id="lyrics-title">Lyrics</h3>
            <a v-if="lyricSource && (details?.lyrics || details?.lyricsStatus === 'instrumental')" :href="lyricSource.url" target="_blank" rel="noopener noreferrer">Source: {{ lyricSource.label }} ↗</a>
          </div>
          <p v-if="loading" class="status" role="status">Finding song details…</p>
          <div v-else-if="error" class="status" role="alert">
            <p>{{ error }}</p><button class="retry-button" @click="loadDetails">Try again</button>
          </div>
          <p v-else-if="details?.lyricsStatus === 'instrumental'" class="status">Instrumental — no lyrics.</p>
          <p v-else-if="details?.lyrics" class="lyrics">{{ details.lyrics }}</p>
          <p v-else-if="details?.lyricsStatus === 'unavailable'" class="status">Lyrics are temporarily unavailable. We’ll try again later.</p>
          <p v-else class="status">No lyrics found for this recording yet.</p>
        </section>
      </div>
    </dialog>
  </Teleport>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AlbumArtwork from './AlbumArtwork.vue'
import { CHANNELS, radioParadiseAPI } from '../services/api.js'
import { formatRecordTime } from '../services/records.js'
import { listeningLinks, musicBrainzSource } from '../services/listeningLinks.js'
import { lyricsSource } from '../services/lyrics.js'

const props = defineProps({ song: { type: Object, required: true } })
const emit = defineEmits(['close'])
const dialog = ref(null)
const details = ref(null)
const track = computed(() => details.value?.song || props.song)
const links = computed(() => listeningLinks(track.value, details.value?.recordingLinks))
const recordingSource = computed(() => musicBrainzSource(details.value?.recordingLinks?.source))
const lyricSource = computed(() => lyricsSource(details.value?.lyricsSource))
const loading = ref(true)
const error = ref('')
let controller
let previousOverflow
const localTime = value => value ? formatRecordTime(value) : '—'
const duration = seconds => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`

async function loadDetails() {
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try { details.value = await radioParadiseAPI.getSongDetails(props.song.id, controller.signal) }
  catch (failure) {
    if (failure.code !== 'ERR_CANCELED') error.value = failure.response?.status === 404
      ? 'This song could not be found.' : 'Could not load song details. Please try again.'
  } finally { loading.value = false }
}

function onBackdropClick(event) {
  if (event.target !== dialog.value) return
  const rect = dialog.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('close')
}

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  dialog.value.showModal()
  loadDetails()
})
onBeforeUnmount(() => {
  controller?.abort()
  dialog.value?.close()
  document.body.style.overflow = previousOverflow
})
</script>

<style scoped>
.song-dialog { position: fixed; margin: auto; width: min(860px, calc(100% - 2rem)); max-height: 90dvh; padding: 0; border: 0; border-radius: 20px; color: var(--text-primary); background: #fff; box-shadow: 0 24px 90px #0005; }
.song-dialog::backdrop { background: #12152bb3; backdrop-filter: blur(5px); }
.detail-header { display: flex; justify-content: space-between; align-items: center; padding: 1rem 1.5rem; border-bottom: 1px solid #edf2f7; }
.eyebrow { color: var(--primary); font-size: .75rem; font-weight: 700; letter-spacing: .12em; }
.close-button { width: 40px; height: 40px; border: 0; border-radius: 50%; background: #f0f2fa; color: #4a5568; cursor: pointer; font-size: 1.2rem; }
.detail-scroll { overflow-y: auto; max-height: calc(90dvh - 73px); padding: 1.5rem; overscroll-behavior: contain; }
.detail-overview { display: grid; grid-template-columns: minmax(200px, 320px) 1fr; gap: 1.75rem; align-items: start; }
.large-cover { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 12px; box-shadow: 0 8px 28px #1a1a2e26; }
.channel { font-size: .8rem; color: var(--primary); font-weight: 700; margin-bottom: .6rem; }
h2 { font-size: clamp(1.5rem, 4vw, 2rem); line-height: 1.2; overflow-wrap: anywhere; }
.artist { font-size: 1.15rem; margin-top: .6rem; }
.album { color: var(--text-secondary); margin-top: .5rem; line-height: 1.5; }
dl { margin-top: 1.5rem; display: grid; gap: .8rem; }
dl div { display: grid; grid-template-columns: 110px 1fr; gap: .75rem; font-size: .85rem; }
dt { color: var(--text-secondary); } dd { margin: 0; overflow-wrap: anywhere; }
.tracking-note { font-size: .75rem; line-height: 1.5; color: var(--text-secondary); margin-top: 1rem; }
.listening-links { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
.listening-links a { display: inline-flex; align-items: center; min-height: 40px; padding: .55rem .8rem; border: 1px solid #dce1ef; border-radius: 8px; text-decoration: none; font-weight: 600; }
.listening-links a:hover { background: #f0f2fa; }
.recording-source { margin-top: .65rem; font-size: .75rem; color: var(--text-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.lyrics-section { margin-top: 2rem; padding-top: 1.5rem; border-top: 1px solid #e2e8f0; }
.lyrics-heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
h3 { font-size: 1.25rem; }
a { color: var(--primary); font-size: .8rem; }
.lyrics { white-space: pre-wrap; line-height: 1.85; margin-top: 1.25rem; overflow-wrap: anywhere; }
.status { color: var(--text-secondary); line-height: 1.6; padding: 1.5rem 0; }
.retry-button { margin-top: .75rem; padding: .6rem 1rem; background: var(--primary); border: 0; border-radius: 8px; color: #fff; cursor: pointer; }
button:focus-visible, a:focus-visible { outline: 3px solid #4facfe; outline-offset: 3px; }
@media (max-width: 600px) {
  .song-dialog { width: calc(100% - 1rem); max-height: 94dvh; }
  .detail-scroll { padding: 1rem; max-height: calc(94dvh - 73px); }
  .detail-overview { grid-template-columns: 1fr; gap: 1.25rem; }
  .large-cover { max-width: 320px; justify-self: center; }
}
</style>
