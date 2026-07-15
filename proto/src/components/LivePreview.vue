<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useInterface } from '../composables/useInterface.js'

const { state, selectedController } = useInterface()

const resolution = computed(() => {
  const c = selectedController.value
  const short = c?.display_short_side || 720
  const long = c?.display_long_side || Math.round(short * 16 / 9)
  return `${short} × ${long}`
})

const elapsed = ref(0)
let timer = null
onMounted(() => {
  timer = setInterval(() => {
    if (state.isRunning) elapsed.value++
  }, 1000)
})
onUnmounted(() => clearInterval(timer))

const elapsedStr = computed(() => {
  const m = String(Math.floor(elapsed.value / 60)).padStart(2, '0')
  const s = String(elapsed.value % 60).padStart(2, '0')
  return `${m}:${s}`
})
</script>

<template>
  <div class="responsive-content preview-wrap px-4 pt-3 pb-2 flex-shrink-0">
    <div class="preview-screen">
      <!-- Animated game background -->
      <div class="game-bg" />

      <!-- Scanning line when running -->
      <div v-if="state.isRunning" class="scan-line" />

      <div v-if="state.isConnected" class="preview-monogram">MAA</div>

      <!-- Top status overlay -->
      <div class="overlay-top">
        <div class="badge-live">
          <span class="dot" :class="state.isRunning ? 'pulse-red' : 'steady-green'" />
          {{ state.isRunning ? 'RUNNING' : 'LIVE' }}
        </div>
        <div class="badge-info">
          <span v-if="state.isRunning">{{ elapsedStr }}</span>
          <span class="sep">·</span>
          <span>{{ state.previewFPS }} FPS</span>
        </div>
      </div>

      <!-- Bottom info -->
      <div class="overlay-bottom">
        <span class="info-chip">{{ resolution }}</span>
        <span class="info-chip">{{ selectedController?.type || 'Adb' }}</span>
      </div>

      <!-- Placeholder text when idle -->
      <div v-if="!state.isConnected" class="no-signal">No Signal</div>
    </div>
  </div>
</template>

<style scoped>
.preview-screen {
  position: relative;
  width: 100%;
  height: 148px;
  border-radius: 0;
  overflow: hidden;
  background: var(--black);
  border: 0;
}

/* The preview is the photographic/product surface: flat near-black, no UI decoration. */
.game-bg {
  position: absolute;
  inset: 0;
  background: var(--tile-dark);
}

/* Scan line */
.scan-line {
  position: absolute;
  left: 0; right: 0;
  height: 2px;
  background: var(--primary-on-dark);
  opacity: 0.45;
  animation: scan 2.5s linear infinite;
}
@keyframes scan {
  0%   { top: -2px; opacity: 0; }
  10%  { opacity: 1; }
  90%  { opacity: 1; }
  100% { top: 100%; opacity: 0; }
}

.preview-monogram {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--white-12);
  font-family: "SF Pro Display", system-ui, sans-serif;
  font-size: 40px;
  font-weight: 600;
  letter-spacing: -0.04em;
}

/* Top overlay */
.overlay-top {
  position: absolute;
  top: 0; left: 0; right: 0;
  padding: 8px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--black-28);
}

.badge-live {
  display: flex;
  align-items: center;
  gap: 5px;
  font-family: "SF Pro Text", system-ui, sans-serif;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--white-85);
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.steady-green {
  background: var(--primary-on-dark);
}
.pulse-red {
  background: var(--primary-on-dark);
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%      { opacity: 0.5; transform: scale(0.8); }
}

.badge-info {
  font-family: 'JetBrains Mono', monospace;
  font-size: 9px;
  color: var(--white-50);
  display: flex;
  align-items: center;
  gap: 4px;
}
.badge-info .sep { opacity: 0.4; }

/* Bottom overlay */
.overlay-bottom {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  padding: 6px 12px;
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  background: var(--black-28);
}

.info-chip {
  font-family: 'JetBrains Mono', monospace;
  font-size: 9px;
  color: var(--white-40);
  padding: 1px 5px;
  border-radius: 5px;
  background: var(--white-04);
}

.no-signal {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: "SF Pro Text", system-ui, sans-serif;
  font-size: 13px;
  font-weight: 600;
  color: var(--white-30);
  letter-spacing: 0.1em;
}
</style>
