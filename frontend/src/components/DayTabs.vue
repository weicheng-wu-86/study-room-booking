<script setup>
// 日期选择条：今天起连续若干天，横向排列，手机上可左右滑动
import { computed } from 'vue'
import { useClockStore } from '../stores'
import { dayLabel } from '../utils'

defineProps({ modelValue: { type: String, default: '' } })
defineEmits(['update:modelValue'])

const clock = useClockStore()
const days = computed(() =>
  clock.days.map((date, i) => ({ date, ...dayLabel(date), tag: i === 0 ? '今天' : i === 1 ? '明天' : '' }))
)
</script>

<template>
  <div class="days">
    <button v-for="d in days" :key="d.date" type="button" class="day" :class="{ on: d.date === modelValue }"
            @click="$emit('update:modelValue', d.date)">
      <span class="week">{{ d.tag || d.week }}</span>
      <span class="md num">{{ d.md }}</span>
    </button>
  </div>
</template>

<style scoped>
.days {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.day {
  flex: 0 0 auto;
  min-width: 78px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink-2);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-family: inherit;
}
.day:hover {
  border-color: var(--el-color-primary-light-5);
}
.day.on {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.week {
  font-size: 12px;
  opacity: 0.85;
}
.md {
  font-size: 14px;
  font-weight: 600;
}
</style>
