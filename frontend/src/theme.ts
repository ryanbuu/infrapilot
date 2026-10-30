import { computed, ref, watch } from "vue";
import { theme } from "ant-design-vue";

export type ThemePreference = "system" | "light" | "dark";
const storageKey = "infrapilot.theme";
function preference(value: string | null): ThemePreference {
  return value === "light" || value === "dark" ? value : "system";
}
function savedPreference(): ThemePreference {
  try {
    return preference(localStorage.getItem(storageKey));
  } catch {
    return "system";
  }
}
const media = window.matchMedia("(prefers-color-scheme: dark)");
const systemDark = ref(media.matches);
export const themePreference = ref<ThemePreference>(savedPreference());
export const isDark = computed(() =>
  themePreference.value === "system"
    ? systemDark.value
    : themePreference.value === "dark",
);
export const themeConfig = computed(() => ({
  algorithm: isDark.value ? theme.darkAlgorithm : theme.defaultAlgorithm,
  components: {
    Menu: {
      colorItemTextSelected: isDark.value ? "#8edbb0" : "#1c6b46",
      colorItemBgSelected: isDark.value ? "#254936" : "#e4f3e9",
    },
  },
  token: isDark.value
    ? {
        colorPrimary: "#238257",
        colorInfo: "#238257",
        colorBgLayout: "#101d18",
        colorBgContainer: "#182a22",
        colorBgElevated: "#21392e",
        colorBorder: "#3d6050",
        colorBorderSecondary: "#2b4638",
        colorText: "#e8f5ec",
        colorLink: "#8edbb0",
        colorLinkHover: "#bcecd0",
        colorLinkActive: "#8edbb0",
        colorTextSecondary: "#bfd6c7",
        colorTextTertiary: "#a3bfad",
      }
    : {
        colorPrimary: "#238257",
        colorInfo: "#238257",
        colorLink: "#1c6b46",
        colorLinkHover: "#135437",
        colorLinkActive: "#135437",
        colorBgLayout: "#f3faf6",
        colorBgContainer: "#ffffff",
        colorBgElevated: "#ffffff",
        colorBorder: "#cfdfd5",
        colorBorderSecondary: "#e0ebe4",
        colorText: "#183b2c",
        colorTextSecondary: "#3d5e4d",
        colorTextTertiary: "#526f60",
      },
}));
export function setThemePreference(value: ThemePreference) {
  themePreference.value = value;
  try {
    localStorage.setItem(storageKey, value);
  } catch {
    // Appearance still works when storage is unavailable.
  }
}
watch(
  isDark,
  (dark) => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
    document
      .querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", dark ? "#101d18" : "#f3faf6");
  },
  { immediate: true, flush: "sync" },
);
media.addEventListener("change", (event) => (systemDark.value = event.matches));
window.addEventListener("storage", (event) => {
  if (event.key === storageKey || event.key === null)
    themePreference.value = preference(event.newValue);
});
