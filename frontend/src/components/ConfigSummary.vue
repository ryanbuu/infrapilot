<script setup lang="ts">
import DataValues from "./DataValues.vue";
defineProps<{ fields: { label: string; values: string[] }[] }>();
</script>
<template>
  <dl v-if="fields.length" class="config-summary">
    <div v-for="field in fields" :key="field.label">
      <dt>{{ field.label }}</dt>
      <dd><DataValues :values="field.values" /></dd>
    </div>
  </dl>
  <span v-else class="data-empty">—</span>
</template>
