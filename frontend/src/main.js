import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { Chart } from 'chart.js'

Chart.defaults.font.family = '-apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
Chart.defaults.font.size = 13
Chart.defaults.color = '#5e6375'
if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) Chart.defaults.animation = false

createApp(App).mount('#app')
