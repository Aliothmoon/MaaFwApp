<script setup>
defineProps({
  open: { type: Boolean, required: true },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  ariaLabel: { type: String, default: '' },
  height: { type: String, default: '' },
  maxHeight: { type: String, default: '78%' },
})

defineEmits(['close'])
</script>

<template>
  <transition name="fade">
    <div
      v-if="open"
      class="absolute inset-0 bg-[var(--black-20)] z-30"
      @click="$emit('close')"
    />
  </transition>

  <transition name="sheet">
    <section
      v-if="open"
      class="absolute bottom-0 left-0 right-0 z-40 bg-[var(--canvas)] rounded-t-lg border-t border-[var(--hairline)] flex flex-col"
      :style="{ height: height || undefined, maxHeight: maxHeight || undefined }"
      :aria-label="ariaLabel || title"
    >
      <div class="flex justify-center pt-2.5 pb-1 flex-shrink-0">
        <div class="w-9 h-1 rounded-full bg-[var(--chip-gray)]" />
      </div>

      <header class="px-5 py-2.5 flex items-start gap-3 border-b border-[var(--black-06)] flex-shrink-0">
        <div class="flex-1 min-w-0">
          <h3 class="text-[17px] font-semibold tracking-tight text-[var(--ink)]">{{ title }}</h3>
          <p v-if="subtitle" class="mt-0.5 text-[11px] text-[var(--muted)]">{{ subtitle }}</p>
          <slot name="meta" />
        </div>
        <button
          type="button"
          class="w-11 h-11 rounded-full bg-[color:var(--chip-gray)]/60 flex items-center justify-center flex-shrink-0 active:scale-95 transition-transform"
          :aria-label="`关闭${title}`"
          @click="$emit('close')"
        >
          <svg class="w-4 h-4 text-[var(--ink)]" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </header>

      <slot name="intro" />
      <div class="flex-1 min-h-0 overflow-y-auto">
        <slot />
      </div>
      <slot name="footer" />
    </section>
  </transition>
</template>
