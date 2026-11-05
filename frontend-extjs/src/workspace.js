import { api, pretty, encode } from "./api";
import {
  allRoutes,
  gatewayKinds,
  bindSections,
  recordTypes,
  reservationFields,
  bindTemplates,
} from "./catalog";
import {
  field,
  col,
  list,
  toolbar,
  label,
  mobile,
  metricFields,
  actionItems,
  openEditor,
  showDetail,
  showError,
  dialog,
  download,
} from "./ui";
const blankReservation = {
  dnsMode: "static",
  router: "",
  dnsServers: "",
  domain: "",
  searchDomains: "",
  ntpServers: "",
  mtu: "",
  nextServer: "",
  serverHostname: "",
  bootFileName: "",
  comment: "",
  clientClasses: "[]",
  extraOptions: "[]",
};
const options = (items) => items.map(([value, text]) => ({ value, text }));
export default function createWorkspace({ route, user, navigate }) {
  let data = null,
    zones = [],
    zone = sessionStorage.getItem("extjs-zone") || "",
    search = "",
    selected = null,
    busy = false,
    node = "",
    parent = "",
    subnet = 0,
    showSystem = false,
    auditPage = 0,
    bindNode,
    editable = user.admin,
    generation = 0,
    rendering = false;
  const current = allRoutes.find((r) => r.id === route) || allRoutes[0];
  const reload = () => load();
  const setError = (message) => showError(message);
  const windows = new Set();
  function trackWindow(win) {
    windows.add(win);
    win.on("destroy", () => windows.delete(win));
    return win;
  }
  const setShowSystem = (fn) => {
    showSystem = fn(showSystem);
    selected = null;
    render();
  };
  const setAuditPage = (fn) => {
    auditPage = fn(auditPage);
    load();
  };
  const loadParent = Ext.Function.createBuffered(() => load(false), 350);
  const jsonView = (title, value) => trackWindow(showDetail(title, value));
  function open(
    title,
    values,
    path,
    payload,
    preview,
    fields,
    method,
    inspect = false,
  ) {
    trackWindow(
      openEditor({
        title,
        values,
        path,
        payload,
        preview,
        fields,
        method,
        onSaved: (result) => {
          if (!page.destroyed) load();
          if (inspect) jsonView("执行结果", result);
        },
      }),
    );
  }
  function dnsEdit(record) {
    const z = data.zone;
    open(
      record ? "编辑解析记录" : "添加解析记录",
      record
        ? { ...record, values: record.values.join("\n") }
        : { name: "", type: "A", ttl: 300, values: "" },
      "/zones/" + encodeURIComponent(z.id) + "/records",
      (v) => ({
        name: v.name,
        type: v.type,
        ttl: Number(v.ttl),
        values: String(v.values)
          .split("\n")
          .map((x) => x.trim())
          .filter(Boolean),
        ...(record ? { expectedRevision: record.revision } : {}),
      }),
      null,
      [
        { name: "name", label: "名称", readOnly: !!record },
        {
          name: "type",
          label: "类型",
          kind: "select",
          options: recordTypes.map((x) => ({ text: x, value: x })),
          readOnly: !!record,
        },
        { name: "ttl", label: "TTL（秒）", inputType: "number" },
        {
          name: "values",
          label: "记录值（每行一个）",
          kind: "area",
          height: 170,
        },
      ],
      record ? "PUT" : "POST",
    );
  }
  function dnsDelete() {
    open(
      "删除解析记录",
      selected,
      "/zones/" + encodeURIComponent(zone) + "/records/delete",
      () => ({
        name: selected.name,
        type: selected.type,
        expectedRevision: selected.revision,
      }),
    );
  }
  function reservationEdit(r, lease) {
    const id = r?.subnetId || Number(subnet) || data.subnets[0]?.id;
    const values = {
      ...blankReservation,
      ...r,
      subnetId: id,
      mac: r?.mac || lease?.mac || "",
      address: r?.address || lease?.address || "",
      hostname: r?.hostname || lease?.hostname?.split(".")[0] || "",
    };
    values.clientClasses = pretty(r?.clientClasses || []);
    values.extraOptions = pretty(r?.extraOptions || []);
    const operation = r ? "reservation-save" : "reservation-create";
    open(
      r ? "编辑保留地址" : "添加保留地址",
      values,
      "/dhcp/changes/" + operation,
      (v) => ({
        revision: data.revision,
        subnetId: Number(v.subnetId),
        values: {
          ...v,
          mtu: v.mtu === "" ? null : Number(v.mtu),
          clientClasses: JSON.parse(v.clientClasses || "[]"),
          extraOptions: JSON.parse(v.extraOptions || "[]"),
        },
      }),
      "/dhcp/preview/" + operation,
      [
        {
          name: "subnetId",
          label: "所属子网",
          kind: "select",
          options: data.subnets.map((s) => ({ text: s.prefix, value: s.id })),
          readOnly: !!r,
        },
        ...reservationFields.map((f) => ({
          ...f,
          readOnly: f.name === "mac" && !!r,
          placeholder: [
            "router",
            "dnsServers",
            "domain",
            "searchDomains",
            "ntpServers",
          ].includes(f.name)
            ? "留空继承网络设置"
            : undefined,
        })),
      ],
    );
  }
  function subnetEdit(s) {
    open(
      s ? "编辑子网" : "添加子网",
      s
        ? { ...s, pools: s.pools.join("\n") }
        : {
            id: "",
            prefix: "",
            pools: "",
            router: "",
            dnsServers: "",
            domain: "",
            searchDomains: "",
            validLifetime: 3600,
          },
      "/dhcp/changes/subnet-save",
      (v) => ({
        revision: data.revision,
        subnetId: Number(v.id),
        values: {
          ...v,
          id: Number(v.id),
          pools: v.pools.split("\n").filter(Boolean),
          validLifetime: Number(v.validLifetime),
        },
      }),
      "/dhcp/preview/subnet-save",
      [
        ["id", "子网 ID"],
        ["prefix", "网段"],
        ["pools", "地址池（每行一个）", "area"],
        ["router", "网关"],
        ["dnsServers", "DNS 服务器"],
        ["domain", "域名"],
        ["searchDomains", "搜索域"],
        ["validLifetime", "租期（秒）"],
      ].map(([name, label, kind]) => ({
        name,
        label,
        kind,
        readOnly: !!s && name === "id",
      })),
    );
  }
  function dhcpDelete() {
    const isSubnet = route === "dhcp-subnets";
    const op = isSubnet ? "subnet-delete" : "reservation-delete";
    open(
      isSubnet ? "删除子网" : "删除保留地址",
      selected,
      "/dhcp/changes/" + op,
      () => ({
        revision: data.revision,
        subnetId: isSubnet ? selected.id : selected.subnetId,
        values: isSubnet ? {} : { mac: selected.mac },
      }),
      "/dhcp/preview/" + op,
    );
  }
  function leaseAction(action) {
    open(
      action === "release" ? "释放租约" : "重发 DNS 更新",
      { address: selected.address, action },
      "/dhcp/leases",
      () => ({ revision: data.revision, address: selected.address, action }),
    );
  }
  function forwardingEdit(r, remove = false) {
    const rule = r || {
      domain: "",
      transport: "DOT",
      forward: "only",
      addresses: [],
      port: 853,
      tlsServerName: "",
      description: "",
    };
    open(
      remove ? "删除条件转发" : r ? "编辑条件转发" : "添加条件转发",
      { ...rule, addresses: rule.addresses.join("\n") },
      "/forwarding/changes",
      (v) => ({
        operation: remove ? "delete" : r ? "update" : "create",
        revision: data.revision,
        originalDomain: r?.domain,
        ...(!remove
          ? {
              rule: {
                ...v,
                port: Number(v.port),
                addresses: v.addresses.split(/[\s,]+/).filter(Boolean),
              },
            }
          : {}),
      }),
      "/forwarding/preview",
      remove
        ? null
        : [
            ["domain", "域名"],
            [
              "transport",
              "传输协议",
              "select",
              options([
                ["DOT", "DNS over TLS"],
                ["DNS", "DNS"],
              ]),
            ],
            [
              "forward",
              "转发模式",
              "select",
              options([
                ["only", "仅转发"],
                ["first", "优先转发"],
              ]),
            ],
            ["addresses", "上游地址（每行一个）", "area"],
            ["port", "端口"],
            ["tlsServerName", "TLS 校验域名"],
            ["description", "备注"],
          ].map(([name, label, kind, opts]) => ({
            name,
            label,
            kind,
            options: opts,
          })),
    );
  }
  function gatewayEdit(item, remove = false) {
    const kind = route.slice(8);
    const value =
      item?.value ||
      {
        routes: {
          name: "",
          uri: "/*",
          status: 0,
          upstream: { type: "roundrobin", nodes: {} },
        },
        services: { name: "", upstream: { type: "roundrobin", nodes: {} } },
        upstreams: { name: "", type: "roundrobin", nodes: {} },
        ssls: { snis: [], cert: "", key: "" },
      }[kind] ||
      {};
    open(
      remove ? "删除配置" : item ? "编辑配置" : "添加配置",
      { id: item?.id || "", value },
      "/gateway/resources/" + kind + "/changes",
      (v) => ({
        action: remove ? "DELETE" : item ? "UPDATE" : "CREATE",
        id: item?.id || v.id,
        parent: parent || undefined,
        revision: item?.revision,
        ...(!remove ? { value: v.value } : {}),
      }),
      "/gateway/resources/" + kind + "/preview",
    );
  }

  function bindEdit(obj, remove = false) {
    open(
      remove ? "删除 DNS 配置" : obj ? "编辑 DNS 配置" : "添加 DNS 配置",
      {
        kind:
          obj?.kind ||
          bindSections.find((s) => "bind-" + s[0] === route)?.[2][0] ||
          "acl",
        view: obj?.view || bindNode.primaryView || "",
        both: true,
        source:
          obj?.source ||
          bindTemplates[
            bindSections.find((s) => "bind-" + s[0] === route)?.[2][0]
          ] ||
          "",
      },
      "/bind/changes",
      (v) => ({
        revision: data.revision,
        action: {
          operation: remove ? "delete" : obj ? "update" : "create",
          node: bindNode.id,
          both: v.both === true || v.both === "true",
          id: obj?.id,
          kind: v.kind,
          name: obj?.name,
          view: v.view,
          source: v.source,
        },
      }),
      "/bind/preview",
      remove
        ? null
        : [
            { name: "kind", label: "配置类型", readOnly: !!obj },
            { name: "view", label: "View" },
            {
              name: "both",
              label: "同步两台节点",
              kind: "select",
              options: options([
                [true, "同步两台"],
                [false, "仅当前节点"],
              ]),
            },
            { name: "source", label: "原生配置", kind: "area", height: 260 },
          ],
    );
  }

  async function backups(family) {
    const rows = await api("/" + family + "/backups");
    if (page.destroyed) return;
    let chosen;
    const restore = Ext.create("Ext.Button", {
      text: "恢复变更前配置",
      disabled: true,
      handler: () => {
        const payload =
          family === "bind"
            ? {
                revision: data.revision,
                action: { operation: "restore", backupId: chosen.id },
              }
            : {
                revision: data.revision,
                operation: "restore",
                backupId: chosen.id,
              };
        win.close();
        open(
          "恢复变更前配置",
          chosen,
          "/" + family + "/changes",
          () => payload,
          "/" + family + "/preview",
        );
      },
    });
    const detail = Ext.create("Ext.Button", {
      text: "查看详情",
      disabled: true,
      handler: () => {
        win.close();
        jsonView("备份详情", chosen);
      },
    });
    const win = dialog("变更备份", {
      items: [
        {
          xtype: "grid",
          flex: 1,
          store: { data: rows },
          columns: [
            col("时间", "createdAt"),
            col("操作", "operation"),
            col("操作人", "actor"),
            col("状态", "phase"),
          ],
          selectable: { mode: "single" },
          listeners: {
            selectionchange: (_, records) => {
              chosen = records[0]?.data;
              detail.setDisabled(!chosen);
              restore.setDisabled(!chosen || !editable);
            },
          },
        },
        toolbar([detail, restore], {
          docked: "bottom",
          layout: { type: "hbox", pack: "end" },
        }),
      ],
    });
    trackWindow(win).show();
  }
  function definition() {
    bindNode = data?.nodes?.find((n) => n.id === node) || data?.nodes?.[0];
    editable = user.admin && (!data || data.writable !== false);
    let rows = [],
      columns = [],
      actions = [],
      filters = [],
      metrics = null;
    if (data) {
      if (route === "overview") {
        metrics = [
          ["DNS 区域", zones.length],
          ["DHCP 子网", data.subnets?.length],
          ["有效租约", data.leases?.filter((l) => l.active).length],
          [
            "在线节点",
            data.nodes?.filter((n) => n.online).length +
              " / " +
              data.nodes?.length,
          ],
        ];
        rows = zones;
        columns = [
          col("DNS 区域", "name", { flex: 2 }),
          col("记录数", "recordCount"),
          col("维护方式", "readOnly", {
            renderer: (v) => (v ? "动态注册" : "静态配置"),
          }),
          col("主节点", "primary"),
          col("从节点", "secondaries", { renderer: list }),
        ];
        actions = [
          [
            "查看记录",
            () => {
              sessionStorage.setItem("extjs-zone", selected.id);
              navigate("records");
            },
            !selected,
          ],
        ];
      } else if (route === "records") {
        rows = data.records.filter(
          (r) => showSystem || r.editable || data.zone.readOnly,
        );
        columns = [
          col("名称", "name", { flex: 2 }),
          col("类型", "type", { width: 90, flex: 0 }),
          col("记录值", "values", { flex: 3, renderer: list }),
          col("TTL", "ttl", { width: 85, flex: 0 }),
          col("维护方式", "editable", {
            renderer: (v, r) =>
              v ? "静态配置" : r.get("protection") || "系统保护",
          }),
        ];
        filters = [
          field({
            kind: "select",
            label: "DNS 区域",
            value: zone,
            options: zones.map((z) => ({ text: z.name, value: z.id })),
            onChange: (id) => {
              zone = id;
              sessionStorage.setItem("extjs-zone", id);
              load();
            },
          }),
        ];
        actions = [
          ["添加记录", () => dnsEdit(), !user.admin || data.zone.readOnly],
          ["编辑", () => dnsEdit(selected), !user.admin || !selected?.editable],
          ["删除", dnsDelete, !user.admin || !selected?.editable],
          [
            "主从对比",
            async () => {
              try {
                jsonView(
                  "主从对比",
                  await api(
                    "/zones/" +
                      zone +
                      "/probe?name=" +
                      encodeURIComponent(selected.name) +
                      "&type=" +
                      selected.type,
                  ),
                );
              } catch (e) {
                setError(e.message);
              }
            },
            !selected,
          ],
          ["导出", () => download(data.zone.name + ".json", rows)],
          [
            (showSystem ? "隐藏" : "显示") + "系统记录",
            () => setShowSystem((v) => !v),
          ],
        ];
      } else if (route.startsWith("dhcp-")) {
        metrics = [
          ["DHCP 子网", data.subnets.length],
          ["有效租约", data.leases.filter((l) => l.active).length],
          [
            "保留地址",
            data.subnets.reduce((n, s) => n + s.reservations.length, 0),
          ],
          [
            "在线节点",
            data.nodes.filter((n) => n.online).length +
              " / " +
              data.nodes.length,
          ],
        ];
        filters = [
          field({
            kind: "select",
            label: "所属网络",
            value: subnet,
            options: [
              { text: "全部子网", value: 0 },
              ...data.subnets.map((s) => ({ text: s.prefix, value: s.id })),
            ],
            onChange: (v) => {
              subnet = Number(v);
              selected = null;
              render();
            },
          }),
        ];
        if (route === "dhcp-reservations") {
          rows = data.subnets
            .flatMap((s) =>
              s.reservations.map((r) => ({
                ...r,
                subnetId: s.id,
                prefix: s.prefix,
              })),
            )
            .filter((r) => !subnet || r.subnetId === subnet);
          columns = [
            col("主机名", "hostname", {
              renderer: (v, r) =>
                [v, r.get("fqdn")].filter(Boolean).map(encode).join("<br>"),
            }),
            col("固定地址", "address"),
            col("MAC", "mac", { minWidth: 170 }),
            col("所属网络", "prefix"),
            col("网关", "effective", { renderer: (v) => list(v?.router) }),
            col("DNS 服务器", "effective", {
              renderer: (v) => list(v?.dnsServers),
              minWidth: 170,
            }),
          ];
          actions = [
            ["添加保留地址", () => reservationEdit(), !editable],
            [
              "编辑",
              () => reservationEdit(selected),
              !editable || !selected?.editable,
            ],
            ["删除", dhcpDelete, !editable || !selected?.editable],
          ];
        } else if (route === "dhcp-subnets") {
          rows = data.subnets;
          columns = [
            col("子网", "prefix"),
            col("地址池", "pools", { renderer: list, flex: 2 }),
            col("网关", "router"),
            col("DNS 服务器", "dnsServers", { renderer: list, flex: 2 }),
            col("搜索域", "searchDomains"),
            col("租期", "validLifetime"),
          ];
          actions = [
            ["添加子网", () => subnetEdit(), !editable],
            ["编辑", () => subnetEdit(selected), !editable || !selected],
            ["删除", dhcpDelete, !editable || !selected],
          ];
        } else if (route === "dhcp-leases") {
          rows = data.leases.filter((l) => !subnet || l.subnetId === subnet);
          columns = [
            col("IP 地址", "address"),
            col("客户端", "hostname", {
              flex: 2,
              renderer: (v, r) =>
                encode(r.get("reserved") ? r.get("reservationFqdn") || v : v),
            }),
            col("MAC", "mac", { minWidth: 170 }),
            col("到期时间", "expiresAt", {
              renderer: (v) => new Date(v * 1000).toLocaleString(),
              minWidth: 180,
            }),
            col("状态", "active", { renderer: (v) => (v ? "有效" : "已过期") }),
            col("保留", "reserved", { renderer: (v) => (v ? "已保留" : "—") }),
          ];
          actions = [
            [
              "保留地址",
              () => reservationEdit(null, selected),
              !editable || !selected || selected.reserved,
            ],
            [
              "重发 DNS",
              () => leaseAction("resend-ddns"),
              !editable ||
                !selected ||
                selected.reservationDnsMode === "static",
            ],
            ["释放", () => leaseAction("release"), !editable || !selected],
          ];
        } else {
          rows = data.nodes;
          columns = [
            col("节点", "label"),
            col("状态", "online", { renderer: (v) => (v ? "在线" : "离线") }),
            col("角色", "role"),
            col("HA 状态", "state"),
            col("伙伴连接", "inTouch", {
              renderer: (v) => (v ? "正常" : "未连接"),
            }),
            col("版本", "version"),
          ];
        }
      } else if (route === "forwarding") {
        rows = data.rules;
        columns = [
          col("域名", "domain", { flex: 2 }),
          col("传输协议", "transport"),
          col("上游地址", "addresses", { renderer: list }),
          col("端口", "port"),
          col("TLS 校验域名", "tlsServerName", { flex: 2 }),
          col("模式", "forward"),
        ];
        actions = [
          ["添加规则", () => forwardingEdit(), !editable],
          ["编辑", () => forwardingEdit(selected), !editable || !selected],
          [
            "删除",
            () => forwardingEdit(selected, true),
            !editable || !selected,
          ],
          ["变更备份", () => backups("forwarding")],
        ];
      } else if (route.startsWith("gateway-")) {
        const kind = route.slice(8);
        if (kind === "overview") {
          rows = data.resources;
          columns = [
            col("资源类型", "label"),
            col("数量", "count"),
            col("状态", "available", {
              renderer: (v) => (v ? "可用" : "不可用"),
            }),
            col("信息", "message", { flex: 2 }),
          ];
          metrics = [
            ["网关版本", data.version],
            ["插件", data.plugins],
            ["资源类型", rows.length],
            ["连接状态", data.enabled ? "已连接" : "未启用"],
          ];
        } else if (kind === "plugins") {
          rows = (Array.isArray(data) ? data : []).map((name) => ({ name }));
          columns = [col("插件名称", "name")];
          actions = [
            [
              "查看 Schema",
              async () =>
                jsonView(
                  "插件 Schema",
                  await api(
                    "/gateway/schema/routes?plugin=" +
                      encodeURIComponent(selected.name),
                  ),
                ),
              !selected,
            ],
          ];
        } else if (kind === "runtime") {
          rows = Object.entries(data).map(([name, value]) => ({ name, value }));
          columns = [
            col("配置项", "name"),
            col("内容", "value", {
              renderer: (v) => encode(typeof v === "object" ? pretty(v) : v),
              flex: 3,
            }),
          ];
          actions = [["查看完整配置", () => jsonView("节点配置", data)]];
        } else if (kind === "backups") {
          rows = data;
          columns = [
            col("时间", "createdAt"),
            col("资源类型", "kind"),
            col("资源 ID", "resourceId"),
            col("操作", "action"),
            col("操作人", "actor"),
          ];
          actions = [
            [
              "恢复预览",
              async () => {
                const p = await api(
                  "/gateway/backups/" + selected.id + "/preview",
                  {},
                );
                open(
                  "恢复配置",
                  p,
                  "/gateway/backups/" + selected.id + "/restore",
                  () => ({ revision: p.revision }),
                );
              },
              !selected,
            ],
          ];
        } else {
          rows = data.items || [];
          columns = [
            col("ID", "id"),
            col("名称", "value", {
              renderer: (v) => encode(v.name || v.username || v.desc || "—"),
            }),
            col("地址 / 路径", "value", {
              flex: 2,
              renderer: (v) =>
                list(
                  v.hosts ||
                    v.snis ||
                    v.uri ||
                    v.uris ||
                    Object.keys(v.nodes || {}),
                ),
            }),
            col("配置摘要", "value", {
              flex: 3,
              renderer: (v) => encode(pretty(v).slice(0, 160)),
            }),
          ];
          actions = [
            ["添加配置", () => gatewayEdit(), !user.admin || !data.available],
            ["编辑", () => gatewayEdit(selected), !user.admin || !selected],
            [
              "删除",
              () => gatewayEdit(selected, true),
              !user.admin || !selected,
            ],
            ["查看详情", () => jsonView("资源详情", selected.value), !selected],
          ];
          if (kind === "credentials")
            filters = [
              field({
                label: "消费者名称",
                value: parent,
                onChange: (value) => {
                  parent = value;
                  loadParent();
                },
              }),
            ];
        }
      } else if (route.startsWith("bind-")) {
        filters = [
          field({
            kind: "select",
            label: "DNS 节点",
            value: bindNode?.id,
            options: data.nodes.map((n) => ({
              text: n.label + " · " + n.address,
              value: n.id,
            })),
            onChange: (value) => {
              node = value;
              selected = null;
              render();
            },
          }),
        ];
        const section = route.slice(5);
        if (section === "advanced") {
          rows = bindNode?.files || [];
          columns = [
            col("配置文件", "path", { flex: 3 }),
            col("维护方式", "editable", {
              renderer: (v) => (v ? "可编辑" : "受保护"),
            }),
          ];
          actions = [
            [
              "编辑文件",
              () =>
                open(
                  "编辑配置文件",
                  { content: selected.content },
                  "/bind/changes",
                  (v) => ({
                    revision: data.revision,
                    nodes: [
                      {
                        id: bindNode.id,
                        files: [{ path: selected.path, content: v.content }],
                        assets: [],
                      },
                    ],
                  }),
                  "/bind/preview",
                  [
                    {
                      name: "content",
                      label: selected.path,
                      kind: "area",
                      height: 360,
                    },
                  ],
                ),
              !editable || !selected?.editable,
            ],
          ];
        } else if (section === "operations") {
          rows = bindNode?.commands || [];
          columns = [
            col("操作", "label"),
            col("命令", "id"),
            col("类型", "mutating", {
              renderer: (v) => (v ? "修改运行状态" : "查询"),
            }),
          ];
          actions = [
            [
              "运行",
              () =>
                open(
                  selected.label,
                  {
                    command: selected.id,
                    arguments: {},
                    confirmed: !!selected.mutating,
                  },
                  "/bind/commands",
                  (v) => ({
                    ...v,
                    revision: data.revision,
                    nodes: [bindNode.id],
                  }),
                  null,
                  null,
                  "POST",
                  true,
                ),
              !editable || !selected,
            ],
          ];
        } else {
          rows = (bindNode?.objects || []).filter((o) =>
            (bindSections.find((s) => s[0] === section)?.[2] || []).includes(
              o.kind,
            ),
          );
          columns = [
            col("名称", "name"),
            col("类型", "kind"),
            col("View", "view"),
            col("配置来源", "file", { flex: 2 }),
            col("摘要", "fields", {
              flex: 3,
              renderer: (v) =>
                encode(
                  Object.entries(v || {})
                    .map(([k, v]) => k + ": " + v)
                    .join(" · "),
                ),
            }),
          ];
          actions = [
            ["添加配置", () => bindEdit(), !editable],
            [
              "编辑",
              () => bindEdit(selected),
              !editable || !selected?.editable,
            ],
            [
              "删除",
              () => bindEdit(selected, true),
              !editable || !selected?.editable,
            ],
          ];
        }
        actions.push(["变更备份", () => backups("bind"), !user.admin]);
        if (section === "logging")
          actions.push([
            "运行统计与日志",
            async () =>
              jsonView(
                "运行统计与日志",
                await api("/bind/nodes/" + bindNode.id + "/telemetry"),
              ),
          ]);
        if (section === "advanced")
          actions.push([
            "导出配置",
            () => download("dns-config.json", bindNode.files),
          ]);
        if (section === "zones")
          actions.unshift([
            "添加主从区域",
            () =>
              open(
                "添加主从区域",
                { name: "", ns1: "", ns2: "", ttl: 300, zoneFile: "" },
                "/bind/changes",
                (v) => ({
                  revision: data.revision,
                  action: {
                    operation: "zone-create",
                    ...v,
                    ttl: Number(v.ttl),
                  },
                }),
                "/bind/preview",
                [
                  { name: "name", label: "区域名称" },
                  { name: "ns1", label: "主节点域名" },
                  { name: "ns2", label: "从节点域名" },
                  { name: "ttl", label: "TTL（秒）", inputType: "number" },
                  {
                    name: "zoneFile",
                    label: "初始区域数据（可选）",
                    kind: "area",
                    height: 220,
                  },
                ],
              ),
            !editable,
          ]);
        if (section === "dnssec")
          actions.push([
            "生成 TSIG 密钥",
            () =>
              open(
                "生成 TSIG 密钥",
                { name: "", algorithm: "hmac-sha256" },
                "/bind/keys/generate",
                (v) => v,
                null,
                [
                  { name: "name", label: "密钥名称" },
                  { name: "algorithm", label: "算法" },
                ],
                "POST",
                true,
              ),
            !editable,
          ]);
      } else if (route === "audit") {
        rows = Array.isArray(data) ? data : data.items || [];
        columns = [
          col("时间", "occurredAt", { minWidth: 180 }),
          col("操作人", "actor"),
          col("操作", "action"),
          col("对象", "recordName", { flex: 2 }),
          col("状态", "status"),
          col("详情", "detail", { flex: 2 }),
        ];
        actions = [
          ["上一页", () => setAuditPage((v) => v - 1), auditPage === 0],
          ["下一页", () => setAuditPage((v) => v + 1), rows.length < 50],
          ["查看变更", () => jsonView("审计详情", selected), !selected],
        ];
      }
    }

    return { rows, columns, actions, filters, metrics };
  }
  const context = Ext.create("Ext.Container", {
    docked: "top",
    layout: "vbox",
  });
  const tools = Ext.create("Ext.Toolbar", {
    cls: "tools-actions",
    defaultButtonUI: "default",
    shadow: false,
    layout: { type: "hbox", wrap: true },
  });
  const caption = Ext.create("Ext.Component", { flex: 1 });
  const selectionCaption = Ext.create("Ext.Component");
  const grid = Ext.create("Ext.grid.Grid", {
    cls: "native-grid",
    flex: 1,
    store: { data: [] },
    columns: [],
    emptyText: "暂无记录",
    variableHeights: true,
    striped: false,
    selectable: { mode: "single" },
    listeners: {
      selectionchange: (_, records) => {
        if (!rendering) {
          selected = records[0]?.data || null;
          refreshActions();
        }
      },
    },
  });
  const page = Ext.create("Ext.Panel", {
    cls: "workspace",
    title: current.title,
    iconCls: "x-fa fa-" + current.icon,
    layout: "vbox",
    tools: [
      { type: "refresh", tooltip: "刷新", handler: reload },
      {
        type: "help",
        tooltip: "选择记录后可编辑，修改需预览确认。",
        handler: () =>
          Ext.Msg.alert("说明", "选择记录后可编辑，修改需预览确认。"),
      },
    ],
    items: [
      context,
      toolbar(
        [
          {
            xtype: "searchfield",
            flex: 1,
            placeholder: "搜索名称、地址或配置",
            ariaLabel: "搜索",
            listeners: {
              change: (_, value) => {
                search = value || "";
                applySearch();
              },
            },
          },
        ],
        { docked: "top", cls: "table-tools", shadow: false },
      ),
      tools,
      grid,
      toolbar([caption, selectionCaption], {
        docked: "bottom",
        cls: "table-caption",
        shadow: false,
      }),
    ],
    listeners: {
      destroy: () => {
        generation++;
        for (const win of windows) win.destroy();
        Ext.Viewport.un("resize", resize);
      },
    },
  });
  function refreshActions() {
    if (page.destroyed) return;
    tools.removeAll(true);
    tools.add(actionItems(definition().actions, busy, setError));
    selectionCaption.setHtml(selected ? "已选择 1 项" : "");
  }
  function applySearch() {
    if (page.destroyed) return;
    selected = null;
    grid.deselectAll();
    const store = grid.getStore();
    store.clearFilter();
    if (search)
      store.filterBy((r) =>
        pretty(r.data).toLowerCase().includes(search.toLowerCase()),
      );
    caption.setHtml(busy ? "正在读取…" : store.getCount() + " 条记录");
    refreshActions();
  }
  function render(rebuildContext = true) {
    if (page.destroyed) return;
    rendering = true;
    const d = definition();
    if (rebuildContext) {
      context.removeAll(true);
      if (d.metrics) context.add(metricFields(d.metrics));
      if (d.filters.length)
        context.add({
          xtype: "container",
          cls: "filters",
          layout: "vbox",
          maxWidth: 640,
          padding: "0 16",
          items: d.filters,
        });
    }
    grid.setColumns(d.columns);
    grid.setStore({ data: d.rows });
    selected = null;
    rendering = false;
    applySearch();
  }
  async function load(rebuildContext = true) {
    if (page.destroyed) return;
    const seq = ++generation;
    busy = true;
    caption.setHtml("正在读取…");
    refreshActions();
    try {
      if (["overview", "records"].includes(route)) {
        zones = await api("/zones");
        if (!zones.some((z) => z.id === zone)) zone = zones[0]?.id || "";
      }
      let path;
      if (route === "overview" || route.startsWith("dhcp-")) path = "/dhcp";
      else if (route === "records")
        path = "/zones/" + encodeURIComponent(zone) + "/records";
      else if (route === "forwarding") path = "/forwarding";
      else if (route.startsWith("bind-")) path = "/bind";
      else if (route === "audit")
        path = "/audit?limit=50&offset=" + auditPage * 50;
      else if (route.startsWith("gateway-")) {
        const kind = route.slice(8);
        if (kind === "credentials" && !parent) {
          data = { available: false, items: [] };
          busy = false;
          render(rebuildContext);
          return;
        }
        path =
          "/gateway" +
          (kind === "overview"
            ? ""
            : ["plugins", "backups", "runtime"].includes(kind)
              ? "/" + kind
              : "/resources/" +
                kind +
                (parent ? "?parent=" + encodeURIComponent(parent) : ""));
      }
      const result = await api(path);
      if (seq !== generation || page.destroyed) return;
      data = result;
      busy = false;
      render(rebuildContext);
    } catch (e) {
      if (seq === generation && !page.destroyed) {
        busy = false;
        caption.setHtml("读取失败");
        refreshActions();
        showError(e);
      }
    }
  }
  function resize() {
    if (data && !page.destroyed) render();
  }
  Ext.Viewport.on("resize", resize);
  load();
  return page;
}
