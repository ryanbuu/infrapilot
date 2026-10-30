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
      colorItemTextSelected: isDark.value ? "#80b3ff" : "#0958d9",
      colorItemBgSelected: isDark.value ? "#203955" : "#eaf2ff",
    },
  },
  token: isDark.value
    ? {
        colorBgLayout: "#101827",
        colorBgContainer: "#182233",
        colorBgElevated: "#202d40",
        colorBorder: "#3a4b62",
        colorBorderSecondary: "#2b394d",
        colorText: "#ecf1f8",
        colorLink: "#80b3ff",
        colorLinkHover: "#b5d2ff",
        colorLinkActive: "#80b3ff",
        colorTextSecondary: "#becbdc",
        colorTextTertiary: "#a0b1c7",
      }
    : {
        colorBgLayout: "#f4f7fb",
        colorBgContainer: "#ffffff",
        colorBgElevated: "#ffffff",
        colorBorder: "#cbd5e1",
        colorBorderSecondary: "#e2e8f0",
        colorText: "#182338",
        colorTextSecondary: "#475569",
        colorTextTertiary: "#64748b",
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
      ?.setAttribute("content", dark ? "#101827" : "#f4f7fb");
  },
  { immediate: true, flush: "sync" },
);
media.addEventListener("change", (event) => (systemDark.value = event.matches));
window.addEventListener("storage", (event) => {
  if (event.key === storageKey || event.key === null)
    themePreference.value = preference(event.newValue);
});
