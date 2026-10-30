<script setup lang="ts">
import {
  computed,
  onMounted,
  onUnmounted,
  nextTick,
  reactive,
  ref,
  watch,
} from "vue";
import { App as AntdApp } from "ant-design-vue";
import {
  ReloadOutlined,
  PlusOutlined,
  SearchOutlined,
  DeleteOutlined,
} from "@ant-design/icons-vue";
import { api } from "../api";
import type { DhcpState, DhcpSubnet, DhcpLease, Reservation } from "../types";
import HelpHint from "../components/HelpHint.vue";
import DataValues from "../components/DataValues.vue";
import ReservationOptionsForm from "../components/ReservationOptionsForm.vue";
const { message, modal: Modal } = AntdApp.useApp();
const props = defineProps<{
  section: "subnets" | "reservations" | "leases" | "ha";
  admin: boolean;
}>();
const data = ref<DhcpState>(),
  loading = ref(false),
  error = ref(""),
  search = ref(""),
  subnetFilter = ref(0),
  activeOnly = ref(true);
const titles = {
  subnets: "子网与地址池",
  reservations: "保留地址",
  leases: "租约",
  ha: "高可用状态",
};
const descriptions = {
  subnets: "管理 DHCPv4 子网、地址池与客户端选项。",
  reservations: "按 MAC 地址为设备分配固定地址，主备节点同步保存。",
  leases: "查询客户端分配情况、租约有效期与 DNS 更新状态。",
  ha: "检查 DHCP 服务节点、热备通信与配置一致性。",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/dhcp");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
watch(
  () => props.section,
  () => {
    search.value = "";
    subnetFilter.value = 0;
    editor.value = "";
  },
);
const media = window.matchMedia("(max-width:760px)");
const mobile = ref(media.matches),
  mobilePage = ref(1);
function resize(e: MediaQueryListEvent) {
  mobile.value = e.matches;
}
onMounted(() => media.addEventListener("change", resize));
onUnmounted(() => media.removeEventListener("change", resize));
watch(
  [search, subnetFilter, activeOnly, () => props.section],
  () => (mobilePage.value = 1),
);
const canEdit = computed(() => !!data.value?.writable && props.admin);
const activeLeases = computed(
  () => data.value?.leases.filter((l) => l.active) || [],
);
const reservations = computed(
  () =>
    data.value?.subnets.flatMap((s) =>
      s.reservations.map((r) => ({ ...r, subnetId: s.id, prefix: s.prefix })),
    ) || [],
);
const filteredSubnets = computed(
  () =>
    data.value?.subnets.filter((s) =>
      `${s.id} ${s.prefix} ${s.domain} ${s.pools.join(" ")}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
    ) || [],
);
const filteredReservations = computed(() =>
  reservations.value.filter(
    (r) =>
      (!subnetFilter.value || r.subnetId === subnetFilter.value) &&
      `${r.mac} ${r.address} ${r.hostname} ${r.comment} ${r.prefix} ${r.effective?.router} ${r.effective?.dnsServers}`
        .toLowerCase()
        .includes(search.value.toLowerCase()),
  ),
);
const filteredLeases = computed(
  () =>
    data.value?.leases
      .filter(
        (l) =>
          (!subnetFilter.value || l.subnetId === subnetFilter.value) &&
          (!activeOnly.value || l.active) &&
          `${l.address} ${l.mac} ${l.hostname} ${l.reservationFqdn || ""}`
            .toLowerCase()
            .includes(search.value.toLowerCase()),
      )
      .sort((a, b) =>
        a.address.localeCompare(b.address, undefined, { numeric: true }),
      ) || [],
);
const mobileRows = computed(() =>
  props.section === "subnets"
    ? filteredSubnets.value
    : props.section === "reservations"
      ? filteredReservations.value
      : filteredLeases.value,
);
const mobileSubnets = computed(() =>
  filteredSubnets.value.slice(
    (mobilePage.value - 1) * 12,
    mobilePage.value * 12,
  ),
);
const mobileReservations = computed(() =>
  filteredReservations.value.slice(
    (mobilePage.value - 1) * 12,
    mobilePage.value * 12,
  ),
);
const mobileLeases = computed(() =>
  filteredLeases.value.slice(
    (mobilePage.value - 1) * 12,
    mobilePage.value * 12,
  ),
);
function nodeNames(ids: string[]) {
  return ids
    .map(
      (id) => data.value?.nodes.find((n) => n.id === id)?.label || "服务节点",
    )
    .join(" / ");
}
const editor = ref(""),
  editing = ref(false),
  busy = ref(false),
  review = ref(false),
  editRevision = ref(""),
  editError = ref("");
const subnetForm = reactive({
  id: 1,
  prefix: "",
  pools: "",
  router: "",
  dnsServers: "",
  domain: "",
  searchDomains: "",
  validLifetime: 7200,
});
const reservationForm = reactive({
  dnsMode: "dynamic" as "static" | "dynamic",
  subnetId: 1,
  mac: "",
  address: "",
  hostname: "",
  router: "",
  dnsServers: "",
  domain: "",
  searchDomains: "",
  ntpServers: "",
  mtu: null as number | string | null,
  nextServer: "",
  serverHostname: "",
  bootFileName: "",
  clientClasses: [] as string[],
  comment: "",
  extraOptions: [] as import("../types").DhcpOption[],
});
const selectedReservation = ref<Reservation>();
const reservationDefaults = computed(() =>
  selectedReservation.value?.subnetId === reservationForm.subnetId
    ? selectedReservation.value.inherited
    : data.value?.subnets.find((s) => s.id === reservationForm.subnetId)
        ?.reservationDefaults || {},
);
const reservationReview = computed(() => {
  const settings = reservationForm;
  const network = (key: string, value: string | number | null) =>
    value || `继承：${reservationDefaults.value[key] || "网络设置"}`;
  const fields = [
    { label: "固定 IP", value: settings.address },
    { label: "MAC 地址", value: settings.mac },
    { label: "主机名", value: settings.hostname || "未指定" },
    {
      label: "DNS 维护方式",
      value: settings.dnsMode === "static" ? "静态维护" : "动态注册",
    },
    { label: "网关", value: network("router", settings.router) },
    { label: "DNS 服务器", value: network("dnsServers", settings.dnsServers) },
    { label: "域名", value: network("domain", settings.domain) },
    {
      label: "搜索域",
      value: network("searchDomains", settings.searchDomains),
    },
    { label: "NTP 服务器", value: network("ntpServers", settings.ntpServers) },
    { label: "MTU", value: network("mtu", settings.mtu) },
  ];
  if (settings.nextServer || selectedReservation.value?.nextServer)
    fields.push({
      label: "启动服务器 IP",
      value: network("nextServer", settings.nextServer),
    });
  if (settings.serverHostname || selectedReservation.value?.serverHostname)
    fields.push({
      label: "启动服务器名称",
      value: network("serverHostname", settings.serverHostname),
    });
  if (settings.bootFileName || selectedReservation.value?.bootFileName)
    fields.push({
      label: "启动文件",
      value: network("bootFileName", settings.bootFileName),
    });
  if (
    settings.clientClasses.length ||
    selectedReservation.value?.clientClasses.length
  )
    fields.push({
      label: "客户端分类",
      value: settings.clientClasses.join(", ") || "清除分类",
    });
  if (settings.comment || selectedReservation.value?.comment)
    fields.push({ label: "备注", value: settings.comment || "清除备注" });
  if (
    !settings.extraOptions.length &&
    selectedReservation.value?.extraOptions.length
  )
    fields.push({ label: "附加 DHCP 选项", value: "清除原有附加选项" });
  for (const option of settings.extraOptions)
    fields.push({
      label: `${option.space || "dhcp4"} / ${option.name || option.code}`,
      value: `${option.data || "（空值）"} · ${option["never-send"] ? "禁止发送" : option["always-send"] ? "始终发送" : "按客户端请求"}`,
    });
  return fields;
});
function openSubnet(s?: DhcpSubnet) {
  editing.value = !!s;
  Object.assign(
    subnetForm,
    s
      ? { ...s, pools: s.pools.join("\n") }
      : {
          id: Math.max(0, ...(data.value?.subnets.map((s) => s.id) || [])) + 1,
          prefix: "",
          pools: "",
          router: "",
          dnsServers: "",
          domain: "",
          searchDomains: "",
          validLifetime: 7200,
        },
  );
  open("subnet");
}
function openReservation(r?: Reservation, subnetId?: number) {
  editing.value = !!r && !!r.editable;
  selectedReservation.value = r;
  Object.assign(reservationForm, {
    dnsMode:
      r?.dnsMode || (data.value?.staticDnsAvailable ? "static" : "dynamic"),
    subnetId: subnetId || r?.subnetId || data.value?.subnets[0]?.id || 1,
    mac: r?.mac || "",
    address: r?.address || "",
    hostname: r?.hostname || "",
    router: r?.router || "",
    dnsServers: r?.dnsServers || "",
    domain: r?.domain || "",
    searchDomains: r?.searchDomains || "",
    ntpServers: r?.ntpServers || "",
    mtu: r?.mtu || null,
    nextServer: r?.nextServer || "",
    serverHostname: r?.serverHostname || "",
    bootFileName: r?.bootFileName || "",
    clientClasses: [...(r?.clientClasses || [])],
    comment: r?.comment || "",
    extraOptions: JSON.parse(JSON.stringify(r?.extraOptions || [])).map(
      (o: import("../types").DhcpOption) => ({ "csv-format": true, ...o }),
    ),
  });
  open("reservation");
}
function reserveLease(l: DhcpLease) {
  const existing = reservations.value.find(
    (r) =>
      r.subnetId === l.subnetId && r.mac.toLowerCase() === l.mac.toLowerCase(),
  );
  if (existing) {
    if (existing.editable) openReservation(existing);
    return;
  }
  openReservation(undefined, l.subnetId);
  Object.assign(reservationForm, {
    dnsMode:
      l.ddnsForward || l.ddnsReverse
        ? "dynamic"
        : data.value?.staticDnsAvailable
          ? "static"
          : "dynamic",
    subnetId: l.subnetId,
    mac: l.mac,
    address: l.address,
    hostname: l.hostname,
  });
}
function open(kind: string) {
  editor.value = kind;
  review.value = false;
  editError.value = "";
  editRevision.value = data.value?.revision || "";
  nextTick(() => {
    document
      .querySelector(".dhcp-editor")
      ?.scrollIntoView({ block: "nearest" });
    (
      document.querySelector(
        ".dhcp-editor input:not([disabled])",
      ) as HTMLElement
    )?.focus();
  });
}
function payload() {
  return {
    revision: editRevision.value,
    subnetId:
      editor.value === "subnet" ? subnetForm.id : reservationForm.subnetId,
    values:
      editor.value === "subnet"
        ? {
            ...subnetForm,
            pools: subnetForm.pools
              .split("\n")
              .map((s) => s.trim())
              .filter(Boolean),
          }
        : { ...reservationForm },
  };
}
function reservationOperation() {
  return editor.value === "subnet"
    ? "subnet-save"
    : editing.value
      ? "reservation-save"
      : "reservation-create";
}
async function preview() {
  busy.value = true;
  editError.value = "";
  try {
    await api("/dhcp/preview/" + reservationOperation(), {
      method: "POST",
      body: JSON.stringify(payload()),
    });
    review.value = true;
  } catch (e) {
    editError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function save() {
  busy.value = true;
  editError.value = "";
  try {
    await api("/dhcp/changes/" + reservationOperation(), {
      method: "POST",
      body: JSON.stringify(payload()),
    });
    editor.value = "";
    message.success("主备节点已更新并持久化");
    await load();
  } catch (e) {
    editError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function remove(kind: string, s: DhcpSubnet | Reservation) {
  const isSubnet = kind === "subnet";
  const id = isSubnet ? (s as DhcpSubnet).id : (s as Reservation).subnetId!;
  Modal.confirm({
    title: isSubnet ? "删除此子网？" : "删除此保留地址？",
    content: isSubnet
      ? "仍有有效租约或保留地址的子网不能删除。删除会同步到主备节点。"
      : "删除后设备下次分配地址将遵循普通地址池规则，不会强制断开当前连接。",
    okText: "确认删除",
    okType: "danger",
    cancelText: "取消",
    async onOk() {
      try {
        await api(
          "/dhcp/changes/" +
            (isSubnet ? "subnet-delete" : "reservation-delete"),
          {
            method: "POST",
            body: JSON.stringify({
              revision: data.value?.revision,
              subnetId: id,
              values: isSubnet ? {} : { mac: (s as Reservation).mac },
            }),
          },
        );
        message.success("主备节点配置已同步");
        await load();
      } catch (e) {
        message.error((e as Error).message);
        throw e;
      }
    },
  });
}
function leaseAction(l: DhcpLease, action: string) {
  Modal.confirm({
    title: action === "release" ? "释放此租约？" : "重发 DNS 更新？",
    content:
      action === "release"
        ? `${l.address}：从主备节点删除服务端租约，${l.reservationDnsMode === "static" ? "保留静态 DNS 记录" : "并请求清理动态 DNS"}。客户端可能继续使用该地址直到续租或重新获取地址。`
        : `由 DHCP 服务为 ${l.address} 重发 A / PTR 更新，遵循现有 DDNS 策略。`,
    okText: action === "release" ? "确认释放" : "确认重发",
    okType: action === "release" ? "danger" : "primary",
    cancelText: "取消",
    async onOk() {
      try {
        await api("/dhcp/leases", {
          method: "POST",
          body: JSON.stringify({
            revision: l.revision,
            address: l.address,
            action,
          }),
        });
        message.success("租约操作已提交并记录审计");
        await load();
      } catch (e) {
        message.error((e as Error).message);
        throw e;
      }
    },
  });
}
const subnetColumns = [
  { title: "子网", key: "prefix", width: 210 },
  { title: "地址池", key: "pools" },
  { title: "DNS 服务器", key: "dnsServers", width: 210 },
  { title: "租约 / 保留", key: "usage", width: 120 },
  { title: "操作", key: "actions", width: 150 },
];
const reservationColumns = [
  { title: "固定地址", key: "address", dataIndex: "address", width: "12%" },
  { title: "MAC 地址", key: "mac", dataIndex: "mac", width: "15%" },
  { title: "主机名", key: "hostname", width: "20%" },
  { title: "子网", key: "prefix", dataIndex: "prefix", width: "15%" },
  { title: "网关", key: "router", width: "15%" },
  { title: "DNS 服务器", key: "dnsServers", width: "15%" },
  { title: "操作", key: "actions", width: "8%" },
];
const leaseColumns = [
  { title: "IP 地址", key: "address", dataIndex: "address", width: 170 },
  { title: "客户端", key: "client", width: 240 },
  { title: "到期时间", key: "expires", width: 180 },
  { title: "DNS / 副本", key: "dns", width: 150 },
  { title: "操作", key: "actions", width: 230 },
];
</script>
<template>
  <div class="page-heading resource-heading">
    <div>
      <div class="resource-eyebrow">DHCP / IPv4</div>
      <div class="heading-with-help">
        <h1>
          {{ titles[section] }}
        </h1>
        <HelpHint
          :label="titles[section] + '说明'"
          :text="descriptions[section] + '配置与租约操作均记录审计。'"
        />
      </div>
    </div>
    <a-button :loading="loading" @click="load"
      ><ReloadOutlined />刷新状态</a-button
    >
  </div>
  <a-alert v-if="error" :message="error" type="error" show-icon />
  <a-skeleton v-if="loading && !data" active />
  <template v-if="data">
    <a-alert
      v-if="!data.enabled"
      message="尚未连接 DHCP 服务"
      type="info"
      show-icon
      ><template #message
        ><span class="section-label"
          >尚未连接 DHCP 服务<HelpHint
            label="DHCP 接入说明"
            text="请在服务端配置主备节点管理接口和认证信息。" /></span></template
    ></a-alert>
    <template v-else>
      <div class="resource-metrics">
        <div>
          <span>DHCP 子网</span><strong>{{ data.subnets.length }}</strong>
        </div>
        <div>
          <span>有效租约</span><strong>{{ activeLeases.length }}</strong>
        </div>
        <div>
          <span>保留地址</span><strong>{{ reservations.length }}</strong>
        </div>
        <div>
          <span>在线节点</span
          ><strong
            >{{ data.nodes.filter((n) => n.online).length }}
            <small>/ {{ data.nodes.length }}</small></strong
          >
        </div>
      </div>
      <a-alert
        v-if="!data.writable"
        class="readonly-alert"
        message="DHCP 写入暂不可用"
        type="warning"
        show-icon
        ><template #message
          ><span class="section-label"
            >DHCP 写入暂不可用<HelpHint
              label="写入限制说明"
              text="需要两台节点连通、HA 通信正常且子网配置一致，才能同步修改。读取功能仍可使用。" /></span></template
      ></a-alert>
      <div v-if="section !== 'ha'" class="records-toolbar">
        <a-input
          v-model:value="search"
          allow-clear
          placeholder="搜索子网、IP、MAC 或主机名"
          aria-label="搜索 DHCP"
          ><template #prefix><SearchOutlined /></template></a-input
        ><a-select
          v-if="section !== 'subnets'"
          v-model:value="subnetFilter"
          :options="[
            { value: 0, label: '全部子网' },
            ...data.subnets.map((s) => ({ value: s.id, label: s.prefix })),
          ]"
          aria-label="按子网筛选"
          style="min-width: 190px"
        /><a-checkbox v-if="section === 'leases'" v-model:checked="activeOnly"
          >仅有效租约</a-checkbox
        ><a-button
          v-if="section === 'subnets'"
          type="primary"
          :disabled="!canEdit"
          aria-label="添加子网"
          @click="openSubnet()"
          ><PlusOutlined />添加子网</a-button
        ><a-button
          v-if="section === 'reservations'"
          type="primary"
          :disabled="!canEdit"
          aria-label="添加保留地址"
          @click="openReservation()"
          ><PlusOutlined />添加保留地址</a-button
        >
      </div>
      <section
        v-if="editor"
        class="inline-editor dhcp-editor"
        role="region"
        aria-label="DHCP 配置编辑"
      >
        <div class="inline-editor-heading">
          <h2 class="heading-with-help">
            {{
              editor === "subnet"
                ? editing
                  ? "编辑子网"
                  : "添加子网"
                : editing
                  ? "编辑保留地址"
                  : "添加保留地址"
            }}<HelpHint
              :label="editor === 'subnet' ? '子网配置说明' : '保留地址配置说明'"
              :text="
                editor === 'subnet'
                  ? '保留现有中继、客户端分类、DDNS 和 HA 设置。新建子网需要网关与中继能够转发该网络的 DHCP 请求。'
                  : '保留地址在客户端下次获取或续租时生效，DNS 通过动态注册维护。'
              "
            />
          </h2>
          <a-button
            type="text"
            :disabled="busy"
            aria-label="关闭 DHCP 编辑"
            @click="editor = ''"
            >取消</a-button
          >
        </div>
        <a-alert v-if="editError" :message="editError" type="error" show-icon />
        <a-form v-if="!review" layout="vertical"
          ><template v-if="editor === 'subnet'"
            ><div class="dhcp-form-grid">
              <a-form-item label="子网 ID" required
                ><a-input-number
                  v-model:value="subnetForm.id"
                  :min="1"
                  :max="2147483647"
                  :disabled="editing"
                  aria-label="子网 ID" /></a-form-item
              ><a-form-item label="子网前缀" required
                ><a-input
                  v-model:value="subnetForm.prefix"
                  :disabled="editing"
                  placeholder="例如 198.51.100.0/24"
                  aria-label="子网前缀" /></a-form-item
              ><a-form-item label="默认租期（秒）" required
                ><a-input-number
                  v-model:value="subnetForm.validLifetime"
                  :min="60"
                  :max="604800"
                  aria-label="默认租期"
              /></a-form-item>
            </div>
            <a-form-item label="地址池" required
              ><a-textarea
                v-model:value="subnetForm.pools"
                :rows="3"
                placeholder="起始IP - 结束IP，每行一个地址池"
                aria-label="地址池"
            /></a-form-item>
            <div class="dhcp-options-grid">
              <a-form-item label="网关"
                ><a-input
                  v-model:value="subnetForm.router"
                  aria-label="网关" /></a-form-item
              ><a-form-item label="DNS 服务器"
                ><a-input
                  v-model:value="subnetForm.dnsServers"
                  placeholder="多个 IPv4 地址用逗号分隔"
                  aria-label="DNS 服务器" /></a-form-item
              ><a-form-item label="域名"
                ><a-input
                  v-model:value="subnetForm.domain"
                  aria-label="DHCP 域名" /></a-form-item
              ><a-form-item label="搜索域"
                ><a-input
                  v-model:value="subnetForm.searchDomains"
                  placeholder="多个域名用逗号分隔"
                  aria-label="搜索域"
              /></a-form-item>
            </div>
          </template>
          <template v-else
            ><div class="dhcp-options-grid reservation-identity-grid">
              <a-form-item label="所属子网" required
                ><a-select
                  v-model:value="reservationForm.subnetId"
                  :disabled="editing"
                  :options="
                    data.subnets.map((s) => ({ value: s.id, label: s.prefix }))
                  "
                  aria-label="所属子网" /></a-form-item
              ><a-form-item label="MAC 地址" required
                ><a-input
                  v-model:value="reservationForm.mac"
                  :disabled="editing"
                  placeholder="02:00:00:00:00:01"
                  aria-label="MAC 地址" /></a-form-item
              ><a-form-item label="固定 IP" required
                ><a-input
                  v-model:value="reservationForm.address"
                  aria-label="固定 IP" /></a-form-item
              ><a-form-item label="主机名"
                ><a-input
                  v-model:value="reservationForm.hostname"
                  aria-label="保留主机名"
              /></a-form-item>
            </div>
            <ReservationOptionsForm
              :key="
                reservationForm.subnetId +
                '|' +
                (selectedReservation?.mac || 'new')
              "
              :settings="reservationForm"
              :defaults="reservationDefaults"
              :client-classes="data.clientClasses || []"
              :server-domain="data.serverDomain"
              :static-dns-available="data.staticDnsAvailable"
            /> </template
        ></a-form>
        <template v-else
          ><a-alert message="主备节点配置校验通过" type="info" show-icon
            ><template #message
              ><span class="section-label"
                >主备节点配置校验通过<HelpHint
                  label="发布顺序说明"
                  text="确认后先更新备用节点，再更新主节点，并将配置写入磁盘。" /></span></template
          ></a-alert>
          <div class="change-preview">
            <div class="preview-name">待提交变更</div>
            <pre v-if="editor === 'subnet'" class="config-preview">{{
              JSON.stringify(payload().values, null, 2)
            }}</pre>
            <a-descriptions
              v-else
              :column="mobile ? 1 : 2"
              bordered
              size="small"
              class="reservation-review"
            >
              <a-descriptions-item
                v-for="item in reservationReview"
                :key="item.label"
                :label="item.label"
                >{{ item.value }}</a-descriptions-item
              >
            </a-descriptions>
          </div></template
        >
        <div class="editor-footer">
          <a-button
            :disabled="busy"
            @click="review ? (review = false) : (editor = '')"
            >{{ review ? "返回修改" : "取消" }}</a-button
          ><a-button
            v-if="!review"
            type="primary"
            :loading="busy"
            @click="preview"
            >校验并预览</a-button
          ><a-button v-else type="primary" :loading="busy" @click="save"
            >确认同步</a-button
          >
        </div>
      </section>
      <section
        v-if="section === 'subnets' && !mobile"
        class="records-table-panel"
      >
        <div class="table-caption">
          <span>{{ filteredSubnets.length }} 个子网</span
          ><a-tag>双节点同步</a-tag>
        </div>
        <a-table
          table-layout="fixed"
          :columns="subnetColumns"
          :data-source="filteredSubnets"
          row-key="id"
          :scroll="{ x: 950 }"
          size="middle"
          :pagination="{ pageSize: 12 }"
          ><template #bodyCell="{ column, record }"
            ><template v-if="column.key === 'prefix'"
              ><strong>{{ record.prefix }}</strong>
              <div class="record-fqdn">
                ID {{ record.id }} · 租期 {{ record.validLifetime }} 秒
              </div></template
            ><template v-else-if="column.key === 'pools'"
              ><div
                v-for="pool in record.pools"
                :key="pool"
                class="record-value"
              >
                {{ pool }}
              </div></template
            ><template v-else-if="column.key === 'dnsServers'"
              ><DataValues :values="record.dnsServers" separated
            /></template>
            <template v-else-if="column.key === 'usage'"
              >{{
                activeLeases.filter((l) => l.subnetId === record.id).length
              }}
              / {{ record.reservations.length }}</template
            ><template v-else-if="column.key === 'actions'"
              ><a-button
                type="link"
                size="small"
                :disabled="!canEdit"
                aria-label="编辑子网"
                @click="openSubnet(record)"
                >编辑</a-button
              ><a-button
                type="text"
                size="small"
                danger
                :disabled="!canEdit"
                aria-label="删除子网"
                @click="remove('subnet', record)"
                ><DeleteOutlined /></a-button></template></template
        ></a-table>
      </section>
      <section
        v-else-if="section === 'reservations' && !mobile"
        class="records-table-panel"
      >
        <div class="table-caption">
          <span class="section-label"
            >{{ filteredReservations.length }} 个保留地址<HelpHint
              label="保留地址识别说明"
              text="按 MAC 地址识别客户端。"
          /></span>
        </div>
        <a-table
          :columns="reservationColumns"
          table-layout="fixed"
          class="reservation-table"
          :data-source="filteredReservations"
          :row-key="
            (r: Reservation) => r.subnetId + '|' + r.mac + '|' + r.address
          "
          :scroll="{ x: 1100 }"
          size="middle"
          :pagination="{ pageSize: 12 }"
          ><template #headerCell="{ column }"
            ><span
              v-if="['router', 'dnsServers'].includes(column.key)"
              class="section-label"
              >{{ column.title
              }}<HelpHint
                :label="column.title + '列表说明'"
                text="显示此设备的配置值；未单独指定时继承所属网络的设置。条件分类策略由服务端在客户端请求时决定。" /></span
            ><span v-else>{{ column.title }}</span></template
          >
          <template #bodyCell="{ column, record }"
            ><template v-if="column.key === 'hostname'"
              ><span class="reservation-hostname">{{
                record.hostname || "—"
              }}</span>
              <div
                v-if="record.dnsMode === 'static' && record.fqdn"
                class="record-fqdn"
              >
                {{ record.fqdn }}
              </div>
              <a-tooltip v-if="record.comment" :title="record.comment"
                ><div class="reservation-comment">
                  {{ record.comment }}
                </div></a-tooltip
              ></template
            >
            <template v-else-if="['router', 'dnsServers'].includes(column.key)"
              ><DataValues
                :values="record.effective?.[column.key]"
                separated
                :label="column.title"
                :source="record[column.key] ? 'custom' : 'inherited'"
            /></template>
            <template
              v-else-if="['address', 'mac', 'prefix'].includes(column.key)"
              ><DataValues :values="record[column.key]"
            /></template>
            <template v-else-if="column.key === 'actions'"
              ><a-button
                type="link"
                size="small"
                :disabled="!canEdit || !record.editable"
                aria-label="编辑保留地址"
                @click="openReservation(record)"
                >编辑</a-button
              ><a-button
                type="text"
                size="small"
                danger
                :disabled="!canEdit || !record.editable"
                aria-label="删除保留地址"
                @click="remove('reservation', record)"
                ><DeleteOutlined /></a-button></template></template
        ></a-table>
      </section>
      <section
        v-else-if="section === 'leases' && !mobile"
        class="records-table-panel"
      >
        <div class="table-caption">
          <span class="section-label"
            >{{ filteredLeases.length }} 条租约<HelpHint
              label="租约数据说明"
              text="已合并主备租约副本，相同租约不重复显示。"
          /></span>
        </div>
        <a-table
          table-layout="fixed"
          :columns="leaseColumns"
          :data-source="filteredLeases"
          row-key="address"
          :scroll="{ x: 1050 }"
          size="middle"
          :pagination="{ pageSize: 12 }"
          ><template #bodyCell="{ column, record }"
            ><template v-if="column.key === 'client'"
              ><strong>{{
                record.reservationDnsMode === "static"
                  ? record.reservationFqdn
                  : record.hostname || "未提供主机名"
              }}</strong
              ><HelpHint
                v-if="
                  record.reservationFqdn &&
                  record.hostname.replace(/\.$/, '') !== record.reservationFqdn
                "
                label="租约名称说明"
                :text="
                  '设备名称来自静态保留配置。原租约记录名称：' +
                  (record.hostname || '未提供')
                "
              />
              <div class="record-fqdn mono">
                {{ record.mac || "未提供 MAC" }}
              </div></template
            ><template v-else-if="column.key === 'address'"
              ><DataValues :values="record.address"
            /></template>
            <template v-else-if="column.key === 'expires'"
              ><span>{{
                new Date(record.expiresAt * 1000).toLocaleString("zh-CN", {
                  hour12: false,
                })
              }}</span>
              <div class="record-fqdn">
                {{
                  record.active
                    ? "有效"
                    : record.state === 1
                      ? "已拒绝"
                      : record.state === 2
                        ? "已回收"
                        : "已过期"
                }}
              </div></template
            ><template v-else-if="column.key === 'dns'"
              ><span v-if="record.reservationDnsMode === 'static'"
                >静态 DNS</span
              ><span v-else
                >{{ record.ddnsForward ? "A " : ""
                }}{{ record.ddnsReverse ? "PTR" : "" }}</span
              >
              <div class="record-fqdn">
                {{ nodeNames(record.seenOn) }}
              </div></template
            ><template v-else-if="column.key === 'actions'"
              ><div class="row-actions">
                <a-button
                  type="link"
                  size="small"
                  :disabled="
                    !canEdit ||
                    !record.mac ||
                    (record.reserved && !record.reservationEditable)
                  "
                  :aria-label="
                    record.reserved ? '编辑已有保留地址' : '将租约保留'
                  "
                  @click="reserveLease(record)"
                  >{{ record.reserved ? "编辑保留" : "保留" }}</a-button
                ><a-button
                  type="link"
                  size="small"
                  :disabled="
                    !canEdit ||
                    !record.active ||
                    !record.hostname ||
                    record.reservationDnsMode === 'static'
                  "
                  aria-label="重发 DNS"
                  @click="leaseAction(record, 'resend-ddns')"
                  >重发 DNS</a-button
                ><a-button
                  type="link"
                  size="small"
                  danger
                  :disabled="!canEdit"
                  aria-label="释放租约"
                  @click="leaseAction(record, 'release')"
                  >释放</a-button
                >
              </div></template
            ></template
          ></a-table
        >
      </section>
      <template v-else-if="section === 'ha'"
        ><div class="ha-node-grid">
          <section
            v-for="node in data.nodes"
            :key="node.id"
            class="resource-panel ha-node"
          >
            <div class="section-heading">
              <h2>{{ node.label }}</h2>
              <a-badge
                :status="node.online ? 'success' : 'error'"
                :text="node.online ? '在线' : '离线'"
              />
            </div>
            <a-descriptions :column="1" size="small" bordered
              ><a-descriptions-item label="版本">{{
                node.version || "—"
              }}</a-descriptions-item
              ><a-descriptions-item label="角色">{{
                node.role === "primary"
                  ? "主节点"
                  : node.role === "standby"
                    ? "备用节点"
                    : node.role || "—"
              }}</a-descriptions-item
              ><a-descriptions-item label="HA 模式">{{
                node.mode || "—"
              }}</a-descriptions-item
              ><a-descriptions-item label="当前状态">{{
                node.state || node.error || "—"
              }}</a-descriptions-item
              ><a-descriptions-item label="伙伴通信">{{
                node.inTouch ? "正常" : "未确认"
              }}</a-descriptions-item
              ><a-descriptions-item label="租约副本">{{
                node.leaseCount ?? "—"
              }}</a-descriptions-item></a-descriptions
            >
          </section>
        </div>
        <a-alert
          :message="
            data.aligned
              ? '两台节点的子网配置一致'
              : '节点子网配置不一致或有节点离线'
          "
          :type="data.aligned ? 'success' : 'warning'"
          show-icon
          class="ha-summary"
      /></template>
      <section
        v-if="mobile && section !== 'ha'"
        class="records-table-panel dhcp-mobile-cards"
      >
        <div class="table-caption">
          <span>{{ mobileRows.length }} 条{{ titles[section] }}</span
          ><span class="muted">双节点数据</span>
        </div>
        <template v-if="section === 'subnets'"
          ><article
            v-for="s in mobileSubnets"
            :key="s.id"
            class="mobile-record"
          >
            <div class="mobile-record-heading">
              <strong>{{ s.prefix }}</strong
              ><a-tag>ID {{ s.id }}</a-tag>
            </div>
            <div class="mobile-record-content">
              <span>地址池</span>
              <div v-for="pool in s.pools" :key="pool" class="record-value">
                {{ pool }}
              </div>
            </div>
            <dl class="reservation-mobile-settings">
              <div>
                <dt>DNS</dt>
                <dd><DataValues :values="s.dnsServers" separated /></dd>
              </div>
            </dl>
            <div class="mobile-record-meta">
              租期 {{ s.validLifetime }} 秒 · 保留
              {{ s.reservations.length }} 个
            </div>
            <div class="row-actions">
              <a-button
                type="link"
                :disabled="!canEdit"
                aria-label="编辑子网"
                @click="openSubnet(s)"
                >编辑</a-button
              ><a-button
                type="text"
                danger
                :disabled="!canEdit"
                aria-label="删除子网"
                @click="remove('subnet', s)"
                ><DeleteOutlined
              /></a-button>
            </div></article
        ></template>
        <template v-else-if="section === 'reservations'"
          ><article
            v-for="r in mobileReservations"
            :key="r.subnetId + '|' + r.mac"
            class="mobile-record"
          >
            <div class="mobile-record-heading">
              <strong>{{ r.address }}</strong
              ><a-tag>保留</a-tag>
            </div>
            <p class="record-value">{{ r.mac }}</p>
            <p class="record-fqdn">
              {{ r.fqdn || r.hostname || "未提供主机名" }} · {{ r.prefix }}
            </p>
            <dl class="reservation-mobile-settings">
              <div>
                <dt>网关</dt>
                <dd>
                  <DataValues
                    :values="r.effective?.router"
                    separated
                    label="网关"
                    :source="r.router ? 'custom' : 'inherited'"
                  />
                </dd>
              </div>
              <div>
                <dt>DNS</dt>
                <dd>
                  <DataValues
                    :values="r.effective?.dnsServers"
                    separated
                    label="DNS 服务器"
                    :source="r.dnsServers ? 'custom' : 'inherited'"
                  />
                </dd>
              </div>
            </dl>
            <p v-if="r.comment" class="record-fqdn">{{ r.comment }}</p>
            <div class="row-actions">
              <a-button
                type="link"
                :disabled="!canEdit || !r.editable"
                aria-label="编辑保留地址"
                @click="openReservation(r)"
                >编辑</a-button
              ><a-button
                type="text"
                danger
                :disabled="!canEdit || !r.editable"
                aria-label="删除保留地址"
                @click="remove('reservation', r)"
                ><DeleteOutlined
              /></a-button>
            </div></article
        ></template>
        <template v-else
          ><article
            v-for="l in mobileLeases"
            :key="l.address"
            class="mobile-record"
          >
            <div class="mobile-record-heading">
              <strong>{{ l.address }}</strong
              ><a-tag>{{ l.active ? "有效" : "已过期" }}</a-tag>
            </div>
            <p class="record-fqdn">
              {{
                l.reservationDnsMode === "static"
                  ? l.reservationFqdn
                  : l.hostname || "未提供主机名"
              }}
            </p>
            <HelpHint
              v-if="
                l.reservationFqdn &&
                l.hostname.replace(/\.$/, '') !== l.reservationFqdn
              "
              label="租约名称说明"
              :text="'原租约记录名称：' + (l.hostname || '未提供')"
            />
            <p class="record-value">{{ l.mac }}</p>
            <div class="mobile-record-content">
              <span>到期时间</span
              >{{
                new Date(l.expiresAt * 1000).toLocaleString("zh-CN", {
                  hour12: false,
                })
              }}
            </div>
            <div class="mobile-record-meta">
              副本 {{ nodeNames(l.seenOn) }} ·
              {{
                l.reservationDnsMode === "static"
                  ? "静态 DNS"
                  : (l.ddnsForward ? "A " : "") + (l.ddnsReverse ? "PTR" : "")
              }}
            </div>
            <div class="row-actions">
              <a-button
                type="link"
                :disabled="
                  !canEdit || !l.mac || (l.reserved && !l.reservationEditable)
                "
                :aria-label="l.reserved ? '编辑已有保留地址' : '将租约保留'"
                @click="reserveLease(l)"
                >{{ l.reserved ? "编辑保留" : "保留" }}</a-button
              ><a-button
                type="link"
                :disabled="
                  !canEdit ||
                  !l.active ||
                  !l.hostname ||
                  l.reservationDnsMode === 'static'
                "
                aria-label="重发 DNS"
                @click="leaseAction(l, 'resend-ddns')"
                >重发 DNS</a-button
              ><a-button
                type="link"
                danger
                :disabled="!canEdit"
                aria-label="释放租约"
                @click="leaseAction(l, 'release')"
                >释放</a-button
              >
            </div>
          </article></template
        >
        <a-empty v-if="!mobileRows.length" description="没有匹配的数据" />
        <div class="records-pagination">
          <a-pagination
            v-model:current="mobilePage"
            :total="mobileRows.length"
            :page-size="12"
            simple
          />
        </div>
      </section>
    </template>
  </template>
</template>
