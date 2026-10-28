<script setup lang="ts">
import { computed } from "vue";
import { LinkOutlined, SettingOutlined } from "@ant-design/icons-vue";

const props = withDefaults(
  defineProps<{
    values?: string | string[];
    separated?: boolean;
    monospace?: boolean;
    label?: string;
    source?: "inherited" | "custom";
  }>(),
  { monospace: true, label: "配置值" },
);
const items = computed(() => {
  const values = Array.isArray(props.values)
    ? props.values
    : props.separated
      ? (props.values || "").split(/[,\n]+/)
      : [props.values || ""];
  // DNS RDATA arrives as an array: preserve each original value, including whitespace.
  return Array.isArray(props.values)
    ? values.filter((value) => value.length > 0)
    : values.map((value) => value.trim()).filter(Boolean);
});
const sourceText = computed(() =>
  props.source === "inherited" ? "继承所属网络的设置" : "此设备独立配置",
);
</script>

<template>
  <div class="data-values" :class="{ 'data-values-mono': monospace }">
    <div v-if="items.length" class="data-values-list">
      <div v-for="(value, index) in items" :key="index" class="data-value">
        {{ value }}
      </div>
    </div>
    <span v-else class="data-empty">—</span>
    <a-tooltip
      v-if="source && items.length"
      :title="sourceText"
      :trigger="['hover', 'focus', 'click']"
    >
      <button
        type="button"
        class="data-source"
        :aria-label="`${label}：${sourceText}`"
      >
        <LinkOutlined v-if="source === 'inherited'" aria-hidden="true" />
        <SettingOutlined v-else aria-hidden="true" />
      </button>
    </a-tooltip>
  </div>
</template>
