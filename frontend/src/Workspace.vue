<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  onMounted,
  onUnmounted,
  ref,
} from "vue";
import { App as AntdApp } from "ant-design-vue";
import { MenuOutlined } from "@ant-design/icons-vue";
import { routes } from "./navigation";
import WorkspaceNavigation from "./components/WorkspaceNavigation.vue";
import { api, csrf, login, logout } from "./api";
import type { Meta, User, Zone } from "./types";
import OverviewView from "./views/OverviewView.vue";
import RecordsView from "./views/RecordsView.vue";
import AuditView from "./views/AuditView.vue";
import DhcpView from "./views/DhcpView.vue";
import ThemeSwitcher from "./components/ThemeSwitcher.vue";
import HelpHint from "./components/HelpHint.vue";
import BrandIdentity from "./components/BrandIdentity.vue";
const GatewayView = defineAsyncComponent(
  () => import("./views/GatewayView.vue"),
);
const ForwardingView = defineAsyncComponent(
  () => import("./views/ForwardingView.vue"),
);
const BindView = defineAsyncComponent(() => import("./views/BindView.vue"));
const { message } = AntdApp.useApp();
const meta = ref<Meta>(),
  user = ref<User>(),
  initializing = ref(true),
  username = ref(""),
  password = ref(""),
  loggingIn = ref(false),
  loginError = ref(""),
  zones = ref<Zone[]>([]),
  loadingZones = ref(false),
  zoneError = ref("");
const page = ref("overview"),
  selectedZone = ref(""),
  mobileMenu = ref(false);
const navigationBreakpoint = window.matchMedia("(max-width: 760px)");
const compactNavigation = ref(navigationBreakpoint.matches);
const menuTrigger = ref<{ $el: HTMLElement }>();
const navigationDialog = ref<HTMLElement>();
function resizeNavigation(event: MediaQueryListEvent) {
  compactNavigation.value = event.matches;
  mobileMenu.value = false;
}
function finishMobileNavigation(open: boolean) {
  if (open) {
    navigationDialog.value
      ?.querySelector<HTMLButtonElement>('[aria-label="关闭菜单"]')
      ?.focus();
  } else if (compactNavigation.value && user.value) {
    menuTrigger.value?.$el.focus();
  }
}
function containNavigationFocus(event: KeyboardEvent) {
  if (event.key !== "Tab") return;
  const controls = [
    ...(navigationDialog.value?.querySelectorAll<HTMLElement>(
      "a[href], button, input, [tabindex]",
    ) || []),
  ].filter(
    (element) =>
      element.tabIndex >= 0 &&
      !element.matches(":disabled") &&
      element.getClientRects().length > 0,
  );
  const first = controls[0];
  const last = controls[controls.length - 1];
  if (!first || !last) return;
  const active = document.activeElement;
  if (
    event.shiftKey &&
    (active === first || !controls.includes(active as HTMLElement))
  ) {
    event.preventDefault();
    last.focus();
  } else if (
    !event.shiftKey &&
    (active === last || !controls.includes(active as HTMLElement))
  ) {
    event.preventDefault();
    first.focus();
  }
}
const activeTitle = computed(
  () => routes.find((r) => r.id === page.value)?.title || "概览",
);
const activeGroup = computed(
  () => routes.find((r) => r.id === page.value)?.group || "工作空间",
);
function navigate(id: string, zone?: string) {
  page.value = id;
  mobileMenu.value = false;
  if (zone) selectedZone.value = zone;
  const h =
    "#/" +
    id +
    (id === "records" && selectedZone.value
      ? "?zone=" + encodeURIComponent(selectedZone.value)
      : "");
  if (location.hash !== h) location.hash = h;
}
function readRoute() {
  const [route, query] = (
    location.hash.replace(/^#\//, "") || "overview"
  ).split("?");
  if (routes.some((r) => r.id === route)) {
    page.value = route!;
    const z = new URLSearchParams(query).get("zone");
    if (z) selectedZone.value = z;
  } else page.value = "overview";
  mobileMenu.value = false;
}
function section() {
  return page.value.slice(5) as "subnets" | "leases" | "reservations" | "ha";
}
async function loadZones() {
  loadingZones.value = true;
  zoneError.value = "";
  try {
    zones.value = await api("/zones");
  } catch (e) {
    zoneError.value = (e as Error).message;
  } finally {
    loadingZones.value = false;
  }
}
async function start() {
  try {
    [meta.value] = await Promise.all([api<Meta>("/meta"), csrf()]);
    try {
      user.value = await api("/session");
      await loadZones();
    } catch {
      user.value = undefined;
    }
  } catch {
    loginError.value = "无法连接后端，请确认服务已启动";
  } finally {
    initializing.value = false;
  }
}
async function signIn() {
  if (!username.value || !password.value) {
    loginError.value = "请输入用户名和密码";
    return;
  }
  loggingIn.value = true;
  loginError.value = "";
  try {
    await login(username.value, password.value);
    user.value = await api("/session");
    password.value = "";
    await loadZones();
  } catch (e) {
    loginError.value = (e as Error).message;
  } finally {
    loggingIn.value = false;
  }
}
async function signOut() {
  try {
    await logout();
    user.value = undefined;
    zones.value = [];
    navigate("overview");
  } catch (e) {
    message.error((e as Error).message);
  }
}
function selectZone(id: string) {
  selectedZone.value = id;
  navigate("records", id);
}
function expired() {
  if (user.value) {
    user.value = undefined;
    message.warning("会话已过期，请重新登录");
  }
}
onMounted(() => {
  readRoute();
  navigationBreakpoint.addEventListener("change", resizeNavigation);
  window.addEventListener("hashchange", readRoute);
  window.addEventListener("session-expired", expired);
  void start();
});
onUnmounted(() => {
  navigationBreakpoint.removeEventListener("change", resizeNavigation);
  window.removeEventListener("session-expired", expired);
  window.removeEventListener("hashchange", readRoute);
});
</script>
<template>
  <div v-if="initializing" class="boot-screen">
    <a-spin size="large" />
    <p>正在连接 InfraPilot…</p>
  </div>
  <div v-else-if="!user" class="login-layout">
    <ThemeSwitcher class="login-theme" />
    <a class="brand login-brand" href="/"><BrandIdentity /></a>
    <section class="login-panel">
      <div class="login-form-wrap">
        <div class="heading-with-help">
          <h1>欢迎回来</h1>
          <HelpHint label="登录说明" text="登录以管理 DNS、DHCP 和网络配置。" />
        </div>
        <a-alert
          v-if="loginError"
          :message="loginError"
          type="error"
          show-icon
        />
        <a-form
          layout="vertical"
          :model="{ username, password }"
          @finish="signIn"
        >
          <a-form-item label="用户名"
            ><a-input
              v-model:value="username"
              size="large"
              autocomplete="username"
              placeholder="输入用户名"
              aria-label="用户名"
          /></a-form-item>
          <a-form-item label="密码"
            ><a-input-password
              v-model:value="password"
              size="large"
              autocomplete="current-password"
              placeholder="输入密码"
              aria-label="密码"
          /></a-form-item>
          <a-button
            type="primary"
            size="large"
            html-type="submit"
            block
            :loading="loggingIn"
            >登录工作空间</a-button
          >
        </a-form>
        <div v-if="meta?.demo" class="login-demo">
          <strong>演示模式</strong
          ><HelpHint
            label="演示模式说明"
            text="使用独立示例数据，不会连接现网 DNS。"
          />
          <a-button
            type="link"
            @click="
              username = 'admin';
              password = 'infrapilot-demo';
            "
            >填入演示账户</a-button
          >
        </div>
      </div>
    </section>
    <p class="login-bottom">InfraPilot · v{{ meta?.version || "0.1.0" }}</p>
  </div>
  <div v-else class="app-shell">
    <a
      class="skip-link"
      href="#workspace-content"
      @click.prevent="
        ($event.currentTarget as HTMLElement)
          .closest('.app-shell')
          ?.querySelector<HTMLElement>('#workspace-content')
          ?.focus()
      "
      >跳到主内容</a
    >
    <a-drawer
      v-if="compactNavigation"
      v-model:open="mobileMenu"
      root-class-name="mobile-navigation"
      placement="left"
      width="min(320px, calc(100vw - 48px))"
      :closable="false"
      :push="false"
      :body-style="{ padding: 0, overflow: 'hidden' }"
      @after-open-change="finishMobileNavigation"
    >
      <section
        ref="navigationDialog"
        class="mobile-navigation-dialog"
        role="dialog"
        aria-label="导航菜单"
        aria-modal="true"
        tabindex="-1"
        @keydown="containNavigationFocus"
      >
        <WorkspaceNavigation
          :user="user"
          :page="page"
          mobile
          @navigate="navigate"
          @close="mobileMenu = false"
          @sign-out="signOut"
        />
      </section>
    </a-drawer>
    <aside v-else class="sidebar" aria-label="主导航">
      <WorkspaceNavigation
        :user="user"
        :page="page"
        @navigate="navigate"
        @sign-out="signOut"
      />
    </aside>
    <main class="main-content" :inert="mobileMenu || undefined">
      <header class="topbar">
        <div class="topbar-left">
          <a-button
            ref="menuTrigger"
            class="mobile-toggle"
            aria-haspopup="dialog"
            :aria-expanded="mobileMenu"
            type="text"
            aria-label="打开菜单"
            @click="mobileMenu = true"
            ><MenuOutlined /></a-button
          ><span class="breadcrumb"
            >{{ activeGroup }} <span>/</span> <b>{{ activeTitle }}</b></span
          >
        </div>
        <div class="topbar-right">
          <ThemeSwitcher />
          <span class="connection-status"
            ><span class="status-dot" :class="{ demo: meta?.demo }"></span
            >{{ meta?.demo ? "演示环境" : "生产连接" }}</span
          ><HelpHint
            v-if="meta?.demo"
            label="演示环境说明"
            text="这里的变更仅影响示例数据，不会写入现网 DNS。"
          /><span class="version-label">v{{ meta?.version }}</span>
        </div>
      </header>
      <div id="workspace-content" class="page-content" tabindex="-1">
        <a-alert v-if="zoneError" :message="zoneError" type="error" show-icon />
        <OverviewView
          v-if="page === 'overview'"
          :zones="zones"
          :loading="loadingZones"
          :demo="!!meta?.demo"
          @select="selectZone"
        />
        <RecordsView
          v-else-if="page === 'records'"
          :zones="zones"
          :initial-zone="selectedZone"
          :admin="user.admin"
          @changed="loadZones"
        />
        <ForwardingView v-else-if="page === 'forwarding'" :admin="user.admin" />
        <BindView
          v-else-if="page.startsWith('bind-') && user.admin"
          :key="page"
          :section="page.slice(5)"
          @changed="loadZones"
        />
        <a-result
          v-else-if="page.startsWith('bind-')"
          status="403"
          title="需要管理员权限"
        />
        <DhcpView
          v-else-if="page.startsWith('dhcp-')"
          :key="page"
          :section="section()"
          :admin="user.admin"
        /><GatewayView
          v-else-if="page.startsWith('gateway-') && user.admin"
          :key="page"
          :section="page.slice(8)"
        />
        <a-result
          v-else-if="page.startsWith('gateway-')"
          status="403"
          title="需要管理员权限"
        />
        <AuditView v-else-if="page === 'audit' && user.admin" />
        <footer class="page-footer">
          <span>InfraPilot</span><span>v{{ meta?.version }}</span>
        </footer>
      </div>
    </main>
  </div>
</template>
