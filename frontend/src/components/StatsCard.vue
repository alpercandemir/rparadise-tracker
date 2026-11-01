<template>
  <div class="stats-card card">
    <div class="icon-wrapper" :style="{ background: color }">
      <span class="card-icon">{{ icon }}</span>
    </div>
    <div class="card-content">
      <p class="card-title">{{ title }}</p>
      <p class="card-value">{{ value }}</p>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: String,
  value: [String, Number],
  icon: String,
  color: String
})
</script>

<style scoped>
.stats-card { display: flex; align-items: center; gap: 1rem; padding: 1.1rem 1.5rem; }
.icon-wrapper { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 10px; flex-shrink: 0; }
.card-icon { font-size: 1.3rem; }
.card-title { font-size: .8rem; color: var(--text-secondary); }
.card-value { font-size: 1.65rem; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -.03em; }
@media (max-width: 640px) { .stats-card { padding: .8rem .6rem; } .icon-wrapper { display: none; } .card-title { font-size: .72rem; } .card-value { font-size: 1.4rem; } }

</style>
