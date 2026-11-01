<template>
  <span class="artwork">
    <img v-if="src && !failed" :key="src" :src="src" :alt="`${album || 'Album'} cover`"
      :loading="loading" @error="failed = true" />
    <span v-else class="artwork-fallback" role="img" :aria-label="`Artwork unavailable for ${album || 'this album'}`"
      title="Artwork unavailable"><span aria-hidden="true">♪</span></span>
  </span>
</template>

<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ src: String, album: String, loading: { type: String, default: 'eager' } })
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
</script>

<style scoped>
.artwork { display: block; flex-shrink: 0; aspect-ratio: 1; overflow: hidden; border-radius: 10px; background: #edeaf5; }
img, .artwork-fallback { display: block; width: 100%; height: 100%; object-fit: cover; }
.artwork-fallback { display: grid; place-items: center; color: #62528c; font-size: 2rem; background: linear-gradient(135deg, #edeaf5, #dce6f0); }
</style>
