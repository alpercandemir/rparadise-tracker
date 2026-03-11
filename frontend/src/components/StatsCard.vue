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
.stats-card {
  display: flex;
  align-items: center;
  gap: 1.5rem;
  padding: 1.5rem;
  animation: fadeIn 0.5s ease;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.icon-wrapper {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.card-icon {
  font-size: 2rem;
}

.card-content {
  flex: 1;
}

.card-title {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 0.5rem;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 500;
}

.card-value {
  font-size: 2rem;
  font-weight: 700;
  color: var(--text-primary);
}
</style>
