<template>
  <section class="records card" aria-labelledby="records-title">
    <div class="records-heading">
      <div>
        <p class="eyebrow">YOUR MUSIC ARCHIVE</p>
        <h2 id="records-title">{{ unified ? 'Songs' : 'Records' }}</h2>
        <p class="description">{{ unified ? 'One entry per song across all channels, with combined listening statistics.' : 'Explore channel-specific tracks and every observed play.' }}</p>
      </div>
      <button class="control secondary" @click="loadPage" :disabled="loading">Refresh</button>
    </div>

    <div v-if="!unified" class="record-tabs" role="tablist" aria-label="Record views">
      <button v-for="tab in recordTabs" :key="tab.id" role="tab"
        :id="`tab-${tab.id}`" :aria-selected="state.tab === tab.id" aria-controls="records-panel"
        :class="['record-tab', { active: state.tab === tab.id }]" @click="change({ tab: tab.id })">
        {{ tab.label }}
      </button>
    </div>

    <p v-if="unified" class="history-note library-note">Songs with the same artist, title, and album are combined. Different albums remain separate. Plays and first/last observed times cover all channels.</p>

    <p v-if="state.tab === 'plays'" class="history-note">
      <template v-if="page.historyStartedAt">History available since {{ localTime(page.historyStartedAt) }} ({{ clientTimeZone }}).</template>
      <template v-else>Playback history begins with the first observed play after collection starts.</template>
      Times show when this tracker first observed a song, within its 30-second polling interval. Earlier broadcasts are not reconstructed.
    </p>

    <form class="filters" @submit.prevent="applyFilters">
      <label class="search-field">Search
        <input v-model="draft.q" maxlength="200" type="search" placeholder="Track, artist, or album" />
      </label>
      <label v-if="!unified">Channel
        <select v-model="draft.channel">
          <option value="">All channels</option>
          <option v-for="(channel, id) in CHANNELS" :key="id" :value="id">{{ channel.name }}</option>
        </select>
      </label>
      <template v-if="state.tab === 'plays'">
        <label>From date<input v-model="draft.from" type="date" min="1900-01-01" max="9998-12-31" /></label>
        <label>To date<input v-model="draft.to" type="date" min="1900-01-01" max="9998-12-31" /></label>
      </template>
      <button class="control" type="submit">Apply filters</button>
      <button class="control secondary" type="button" @click="clearFilters">Clear</button>
    </form>

    <div class="table-toolbar">
      <p aria-live="polite">{{ loading ? `Loading ${entryLabel}…` : pageRange(page) + ' ' + entryLabel }}</p>
      <label>Rows per page
        <select :value="state.size" @change="change({ size: Number($event.target.value) })">
          <option :value="25">25</option><option :value="50">50</option><option :value="100">100</option>
        </select>
      </label>
    </div>

    <p class="table-hint">Select a {{ unified ? 'song' : 'track' }} for details. Scroll the table sideways to see all columns.</p>

    <div id="records-panel" :role="unified ? 'region' : 'tabpanel'" :aria-labelledby="unified ? 'records-title' : `tab-${state.tab}`" :aria-busy="loading">
      <div v-if="error" class="message error" role="alert">
        <p>{{ error }}</p><button class="control" @click="loadPage">Try again</button>
      </div>
      <div v-else-if="loading" class="message" role="status">Loading {{ entryLabel }}…</div>
      <div v-else-if="!page.items.length" class="message">
        <h3>No matching {{ entryLabel }}</h3>
        <p v-if="state.page > 0">This page is empty. Return to the first page to see available records.</p>
        <p v-else>Try another search or clear the filters.</p>
        <button v-if="state.page > 0" class="control" @click="change({ page: 0 })">First page</button>
      </div>
      <div v-else class="table-scroll" tabindex="0" aria-label="Records table, scroll horizontally for more columns">
        <table>
          <caption class="sr-only">{{ unified ? 'All songs across channels' : state.tab === 'plays' ? 'Playback history' : 'All saved tracks' }}. Click a column header to sort. All times use your timezone: {{ clientTimeZone }}.</caption>
          <thead><tr>
            <th scope="col" class="cover-cell">Cover</th>
            <th v-for="column in columns" :key="column.key" scope="col" :aria-sort="ariaSort(column.key)">
              <button class="sort-button" @click="sortBy(column.key)">
                {{ column.label }} <span aria-hidden="true">{{ state.sort === column.key ? (state.direction === 'asc' ? '↑' : '↓') : '↕' }}</span>
              </button>
            </th>
          </tr></thead>
          <tbody>
            <tr v-for="row in page.items" :key="row.id">
              <td class="cover-cell">
                <button class="song-link" :aria-label="`View details for ${row.title}`" @click="$emit('select-song', row)">
                  <AlbumArtwork :src="row.coverUrl" :album="row.album" loading="lazy" class="record-cover" />
                </button>
              </td>
              <td v-for="column in columns" :key="column.key" :class="{ 'date-cell': column.kind === 'date', 'title-cell': column.key === 'title' }">
                <button v-if="column.key === 'title'" class="song-link" @click="$emit('select-song', row)">{{ row.title }}</button>
                <span v-else-if="column.key === 'channel'" class="channel-label" :style="{ color: CHANNELS[row.channel]?.color }">{{ CHANNELS[row.channel]?.name || row.channel }}</span>
                <time v-else-if="column.kind === 'date'" :datetime="row[column.key]" :title="row[column.key]">{{ localTime(row[column.key]) }}</time>
                <template v-else>{{ row[column.key] ?? '—' }}</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <footer class="pagination">
      <p>Your timezone: <strong>{{ clientTimeZone }}</strong></p>
      <nav class="page-controls" :aria-label="unified ? 'Songs pagination' : 'Records pagination'">
        <button class="control secondary" :disabled="loading || state.page === 0" @click="change({ page: state.page - 1 })">Previous</button>
        <span>Page {{ state.page + 1 }} of {{ Math.max(page.totalPages, 1) }}</span>
        <button class="control secondary" :disabled="loading || state.page + 1 >= page.totalPages" @click="change({ page: state.page + 1 })">Next</button>
      </nav>
    </footer>
  </section>
</template>

<script setup>
defineEmits(['select-song'])
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AlbumArtwork from './AlbumArtwork.vue'
import { CHANNELS, radioParadiseAPI } from '../services/api.js'
import { parseRecordsHash, recordsHash, updateRecordsState, formatRecordTime, getClientTimeZone, pageRange } from '../services/records.js'

const props = defineProps({ unified: { type: Boolean, default: false } })
const entryLabel = computed(() => props.unified ? 'songs' : 'records')
const recordTabs = [{ id: 'tracks', label: 'All Tracks' }, { id: 'plays', label: 'Playback History' }]
const clientTimeZone = getClientTimeZone()
const localTime = value => formatRecordTime(value, clientTimeZone)
const state = ref(parseRecordsHash(window.location.hash))
const draft = reactive({ q: state.value.q, channel: state.value.channel, from: state.value.from, to: state.value.to })
const page = ref({ items: [], page: state.value.page, size: state.value.size, totalElements: 0, totalPages: 0, historyStartedAt: null })
const loading = ref(false)
const error = ref('')
let request = 0
let controller

const metadataColumns = [
  { key: 'title', label: 'Track' }, { key: 'artist', label: 'Artist' },
  { key: 'album', label: 'Album' }, { key: 'year', label: 'Year' }, { key: 'channel', label: 'Channel' }
]
const columns = computed(() => {
  const metadata = props.unified
    ? metadataColumns.filter(column => column.key !== 'channel').map(column => column.key === 'title' ? { ...column, label: 'Song' } : column)
    : metadataColumns
  return state.value.tab === 'plays'
    ? [{ key: 'playedAt', label: 'Observed at', kind: 'date' }, ...metadata]
    : [...metadata, { key: 'firstPlayed', label: 'First played', kind: 'date' },
      { key: 'lastPlayed', label: 'Last played', kind: 'date' }, { key: 'playCount', label: 'Plays' }]
})

function syncDraft() {
  for (const key of ['q', 'channel', 'from', 'to']) draft[key] = state.value[key]
}

function change(patch) {
  state.value = updateRecordsState(state.value, patch)
  syncDraft()
  const nextHash = recordsHash(state.value)
  if (window.location.hash === nextHash) loadPage()
  else window.location.hash = nextHash
}

function applyFilters() {
  if (draft.from && draft.to && draft.from > draft.to) {
    error.value = 'From date must not be after To date.'
    return
  }
  change({ ...draft })
}

function clearFilters() { change({ q: '', channel: '', from: '', to: '' }) }
function sortBy(sort) { change({ sort, direction: state.value.sort === sort && state.value.direction === 'asc' ? 'desc' : 'asc' }) }
function ariaSort(key) { return state.value.sort === key ? (state.value.direction === 'asc' ? 'ascending' : 'descending') : 'none' }

async function loadPage() {
  const current = ++request
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  const { tab, ...params } = state.value
  if (tab !== 'plays') { delete params.from; delete params.to }
  else params.timeZone = clientTimeZone
  if (tab === 'songs') delete params.channel
  try {
    const data = await radioParadiseAPI.getRecordsPage(tab, params, controller.signal)
    if (current === request) page.value = data
  } catch (failure) {
    if (current === request && failure.code !== 'ERR_CANCELED') {
      error.value = failure.response?.data?.error || `Could not load ${entryLabel.value}. Please try again.`
    }
  } finally {
    if (current === request) loading.value = false
  }
}

function onHashChange() {
  const route = window.location.hash.split('?')[0]
  if (route !== (props.unified ? '#/songs' : '#/records')) return
  state.value = parseRecordsHash(window.location.hash)
  syncDraft()
  loadPage()
}

onMounted(() => { window.addEventListener('hashchange', onHashChange); loadPage() })
onBeforeUnmount(() => { ++request; controller?.abort(); window.removeEventListener('hashchange', onHashChange) })
</script>

<style scoped>
.song-link { background: none; border: 0; color: var(--primary); cursor: pointer; font: inherit; font-weight: 600; text-align: left; }
.song-link:hover { text-decoration: underline; }
.records { padding: 2rem; }
.records:hover { transform: none; }
.records-heading, .table-toolbar, .pagination, .page-controls { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.eyebrow { color: var(--primary); font-size: .7rem; letter-spacing: .15em; font-weight: 700; margin-bottom: .4rem; }
h2 { color: var(--text-primary); font-size: 2rem; }
.description { color: var(--text-secondary); margin-top: .35rem; }
.record-tabs { display: flex; gap: .5rem; margin: 1.5rem 0; border-bottom: 1px solid #e2e8f0; }
.record-tab { background: none; border: 0; padding: 1rem; color: var(--text-secondary); cursor: pointer; font: inherit; font-weight: 600; border-bottom: 3px solid transparent; }
.record-tab.active { color: var(--primary); border-bottom-color: var(--primary); }
.history-note { background: #edf2ff; color: #4a5568; border-radius: 8px; padding: 1rem; font-size: .85rem; line-height: 1.6; margin-bottom: 1.25rem; }
.library-note { margin-top: 1.5rem; }
.filters { display: flex; flex-wrap: wrap; align-items: flex-end; gap: .75rem; padding: 1rem; background: #f5f6fb; border-radius: 12px; }
label { display: flex; flex-direction: column; gap: .4rem; color: #4a5568; font-size: .8rem; font-weight: 600; }
.search-field { flex: 1; min-width: 200px; }
input, select { min-height: 42px; padding: .6rem .75rem; border: 1px solid #cbd5e0; border-radius: 7px; background: white; color: #2d3748; font: inherit; font-size: .9rem; }
.control { border: 1px solid var(--primary); border-radius: 8px; padding: .65rem 1rem; min-height: 42px; cursor: pointer; background: var(--primary); color: white; font: inherit; font-size: .85rem; font-weight: 600; white-space: nowrap; }
.control.secondary { background: white; color: #4c5bc0; border-color: #d9def2; }
button:disabled { opacity: .45; cursor: default; }
button:focus-visible, input:focus-visible, select:focus-visible, .table-scroll:focus-visible { outline: 3px solid #4facfe; outline-offset: 2px; }
.table-toolbar { padding: 1.25rem 0; color: var(--text-secondary); font-size: .85rem; }
.table-toolbar label { flex-direction: row; align-items: center; }
.table-scroll { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 10px; }
table { width: 100%; border-collapse: collapse; font-size: .9rem; }
th { text-align: left; background: #f5f6fb; white-space: nowrap; padding: .8rem .75rem; }
.sort-button { border: 0; background: none; color: #4a5568; cursor: pointer; font: inherit; font-weight: 600; display: flex; gap: .5rem; align-items: center; }
.sort-button span { color: var(--primary); }
td { padding: .8rem .75rem; border-top: 1px solid #edf2f7; color: #4a5568; min-width: 100px; max-width: 240px; overflow-wrap: anywhere; }
tbody tr:hover { background: #fafaff; }
.title-cell { font-weight: 600; color: #2d3748; min-width: 150px; }
.cover-cell { min-width: 64px; width: 64px; }
.date-cell { white-space: nowrap; font-variant-numeric: tabular-nums; }
.channel-label { font-weight: 700; white-space: nowrap; }
.message { text-align: center; padding: 3rem 1rem; color: var(--text-secondary); border: 1px dashed #d9def2; border-radius: 10px; }
.message p, .message h3 { margin-bottom: 1rem; }
.error { color: #9b2c2c; background: #fff5f5; }
.pagination { margin-top: 1.5rem; flex-wrap: wrap; color: var(--text-secondary); font-size: .8rem; }
.page-controls { justify-content: flex-end; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
@media (max-width: 768px) {
  .records { padding: 1rem; }
  .filters label { flex: 1; min-width: 140px; }
  .search-field { flex-basis: 100% !important; }
  .pagination, .page-controls { justify-content: center; }
  .page-controls { width: 100%; gap: .6rem; }
  .table-toolbar { gap: .5rem; flex-wrap: wrap; }
}
.record-cover { width: 44px; height: 44px; border-radius: 7px; }
.table-hint { color: var(--text-secondary); font-size: .8rem; margin-bottom: .75rem; }
.table-scroll { max-width: 100%; }
.record-tab, .control { min-height: 44px; }
@media (max-width: 640px) {
  .records-heading { align-items: flex-start; }
  .filters { padding: .75rem; }
  .filters label { min-width: 0; flex-basis: 100%; }
  input, select { width: 100%; min-width: 0; font-size: 1rem; }
  .search-field { min-width: 0; }
  .record-tab { padding: .75rem .5rem; font-size: .9rem; }
  .page-controls { flex-wrap: wrap; }
}
</style>
