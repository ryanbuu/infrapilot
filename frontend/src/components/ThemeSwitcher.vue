<script setup lang="ts">
import {
  BgColorsOutlined,
  DesktopOutlined,
  BulbOutlined,
} from "@ant-design/icons-vue";
import { themePreference, setThemePreference } from "../theme";
import type { ThemePreference } from "../theme";
function select({ key }: { key: string | number }) {
  setThemePreference(key as ThemePreference);
}
</script>
<template>
  <a-dropdown
    :trigger="['click']"
    placement="bottomRight"
    overlay-class-name="theme-menu"
  >
    <a-button type="text" aria-label="切换主题" class="theme-switcher"
      ><BgColorsOutlined
    /></a-button>
    <template #overlay>
      <a-menu :selected-keys="[themePreference]" @click="select">
        <a-menu-item key="system">
          <template #icon><DesktopOutlined aria-hidden="true" /></template>
          跟随系统
        </a-menu-item>
        <a-menu-item key="light">
          <template #icon><BulbOutlined aria-hidden="true" /></template>
          浅色模式
        </a-menu-item>
        <a-menu-item key="dark">
          <template #icon><BgColorsOutlined aria-hidden="true" /></template>
          深色模式
        </a-menu-item>
      </a-menu>
    </template>
  </a-dropdown>
</template>

<style>
@media (pointer: coarse) {
  .theme-menu .ant-dropdown-menu-item {
    min-height: 44px;
  }
}
</style>
