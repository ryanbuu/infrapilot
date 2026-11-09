import { api, csrf, login } from "./api";
import { groups, allRoutes } from "./catalog";
import { toolbar, label, mobile, showError } from "./ui";
import createWorkspace from "./workspace";

Ext.define("InfraPilot.controller.Application", {
  extend: "Ext.app.ViewController",
  alias: "controller.infrapilot",
  init() {
    Ext.theme.Material.setDarkMode(false);
    document.documentElement.dataset.theme = "light";
    localStorage.removeItem("infrapilot-theme");
    this.onHashChange = () =>
      this.navigate(location.hash.slice(2) || "overview");
    this.onExpired = () => {
      this.user = null;
      this.showLogin();
    };
    addEventListener("hashchange", this.onHashChange);
    addEventListener("session-expired", this.onExpired);
    Ext.Viewport.on("resize", this.onViewportResize, this);
    this.connect();
  },
  async connect() {
    this.getView().setMasked({ xtype: "loadmask", message: "正在连接…" });
    try {
      [this.meta] = await Promise.all([api("/meta"), csrf()]);
      try {
        this.user = await api("/session");
      } catch {
        this.user = null;
      }
      this.user ? this.showWorkspace() : this.showLogin();
    } catch (e) {
      this.showLogin();
      showError(e);
    } finally {
      this.getView().setMasked(false);
    }
  },
  showLogin() {
    if (this.drawer && !this.drawer.destroyed) this.drawer.destroy();
    this.workspace = this.navigation = this.shell = null;
    const username = Ext.create("Ext.field.Text", {
      label: "用户名",
      labelAlign: "top",
      name: "username",
      cls: "native-field",
    });
    const password = Ext.create("Ext.field.Password", {
      label: "密码",
      labelAlign: "top",
      name: "password",
      cls: "native-field",
    });
    const submit = Ext.create("Ext.Button", {
      text: "登录工作空间",
      ui: "action",
      handler: async () => {
        submit.disable();
        try {
          await login(username.getValue(), password.getValue());
          this.user = await api("/session");
          this.showWorkspace();
        } catch (e) {
          showError(e);
        } finally {
          if (!submit.destroyed) submit.enable();
        }
      },
    });
    password.on("action", () => submit.getHandler()());
    const view = this.getView();
    view.removeAll(true);
    view.add({
      xtype: "container",
      layout: { type: "vbox", align: "center", pack: "center" },
      scrollable: "vertical",
      padding: 16,
      items: [
        {
          xtype: "panel",
          title: "登录 InfraPilot",
          width: 400,
          maxWidth: "100%",
          shadow: true,
          layout: "vbox",
          items: [
            {
              xtype: "formpanel",
              padding: 16,
              layout: "vbox",
              items: [
                {
                  xtype: "fieldset",
                  title: "账户",
                  layout: "vbox",
                  items: [username, password],
                },
                submit,
                ...(this.meta?.demo
                  ? [
                      {
                        xtype: "button",
                        text: "填入演示账户",
                        margin: "12 0 0 0",
                        handler: () => {
                          username.setValue("admin");
                          password.setValue("infrapilot-demo");
                        },
                      },
                    ]
                  : []),
              ],
            },
          ],
        },
      ],
    });
  },
  makeNavigation(isMobile = false) {
    const navigateRecord = (record) => {
      if (!this.syncingNavigation && record?.get("leaf"))
        this.navigate(record.id);
    };
    const tree = Ext.create("Ext.grid.Tree", {
      cls: "native-navigation",
      rootVisible: false,
      hideHeaders: true,
      expanderFirst: true,
      expanderOnly: true,
      singleExpand: false,
      columns: [{ xtype: "treecolumn", dataIndex: "text", flex: 1 }],
      store: {
        type: "tree",
        root: {
          expanded: true,
          children: groups
            .filter(
              ([group]) =>
                this.user.admin ||
                !["管理", "Application Gateway", "UPS"].includes(group),
            )
            .map(([text, items], i) => ({
              id: "group-" + i,
              text,
              expanded: !["Application Gateway", "UPS"].includes(text),
              children: items.map(([id, text, icon]) => ({
                id,
                text,
                iconCls: "x-fa fa-" + icon,
                leaf: true,
              })),
            })),
        },
      },
      selectable: { mode: "single" },
      listeners: {
        childtap: (_, location) => navigateRecord(location.record),
        selectionchange: (_, records) => navigateRecord(records[0]),
      },
    });
    const panel = Ext.create("Ext.Panel", {
      cls: isMobile ? "sidebar mobile-sidebar" : "sidebar",
      width: 280,
      layout: "fit",
      items: [
        toolbar(
          [
            label("InfraPilot", { flex: 1 }),
            ...(isMobile
              ? [
                  {
                    xtype: "button",
                    iconCls: "x-fa fa-times",
                    ariaLabel: "关闭导航",
                    handler: () => this.drawer?.hide(),
                  },
                ]
              : []),
          ],
          { docked: "top" },
        ),
        tree,
        toolbar(
          [
            label(this.user.username, {
              flex: 1,
              tooltip: this.user.admin ? "管理员" : "只读用户",
            }),
            {
              xtype: "button",
              iconCls: "x-fa fa-sign-out",
              ariaLabel: "退出登录",
              handler: async () => {
                try {
                  await api("/logout", {});
                  this.onExpired();
                } catch (e) {
                  showError(e);
                }
              },
            },
          ],
          { docked: "bottom" },
        ),
      ],
      ...(isMobile
        ? {
            floated: true,
            modal: true,
            hideOnMaskTap: true,
            left: 0,
            top: 0,
            height: Ext.getViewportHeight(),
            listeners: {
              hide: () => {
                this.drawer = null;
                panel.destroy();
              },
            },
          }
        : {}),
    });
    if (!isMobile) this.navigation = tree;
    panel.navigation = tree;
    return panel;
  },
  showWorkspace() {
    this.route = null;
    this.sidebar = this.makeNavigation();
    this.sidebar.setHidden(mobile());
    this.menuButton = Ext.create("Ext.Button", {
      cls: "menu-trigger",
      iconCls: "x-fa fa-bars",
      ariaLabel: "打开菜单",
      hidden: !mobile(),
      handler: () => {
        if (this.drawer && !this.drawer.destroyed) return;
        this.drawer = this.makeNavigation(true);
        this.drawer.show();
        this.syncNavigation(this.drawer.navigation);
      },
    });
    this.breadcrumb = Ext.create("Ext.Title", { flex: 1 });
    this.connection = Ext.create("Ext.Component", {
      html: this.meta?.demo ? "演示环境" : "生产连接",
      hidden: mobile(),
      margin: "0 0 0 16",
    });
    this.workspace = Ext.create("Ext.Container", { layout: "fit", flex: 1 });
    const view = this.getView();
    view.removeAll(true);
    this.shell = view.add({
      xtype: "container",
      layout: "vbox",
      items: [
        toolbar([this.menuButton, this.breadcrumb, this.connection], {
          docked: "top",
          cls: "app-header",
        }),
        {
          xtype: "container",
          flex: 1,
          layout: "hbox",
          items: [this.sidebar, this.workspace],
        },
      ],
    });
    this.navigate(location.hash.slice(2) || "overview");
  },
  syncNavigation(tree = this.navigation) {
    if (!tree || tree.destroyed) return;
    const record = tree.getStore().getNodeById(this.route);
    if (record) {
      this.syncingNavigation = true;
      tree.select(record);
      this.syncingNavigation = false;
    }
  },
  navigate(id) {
    if (!this.user || !this.workspace || this.workspace.destroyed) return;
    const current = allRoutes.find((r) => r.id === id) || allRoutes[0];
    if (this.drawer && !this.drawer.destroyed) this.drawer.hide();
    if (this.route === current.id) return;
    this.route = current.id;
    location.hash = "#/" + current.id;
    this.breadcrumb.setTitle(
      mobile() ? current.group : "InfraPilot / " + current.group,
    );
    this.syncNavigation();
    this.workspace.removeAll(true);
    this.workspace.add(
      createWorkspace({
        route: current.id,
        user: this.user,
        navigate: (route) => this.navigate(route),
      }),
    );
  },
  onViewportResize() {
    if (!this.shell || this.shell.destroyed) return;
    this.sidebar.setHidden(mobile());
    this.menuButton.setHidden(!mobile());
    this.connection.setHidden(mobile());
    const current = allRoutes.find((r) => r.id === this.route);
    if (current)
      this.breadcrumb.setTitle(
        mobile() ? current.group : "InfraPilot / " + current.group,
      );
    if (this.drawer && !this.drawer.destroyed) {
      if (!mobile()) this.drawer.hide();
      else this.drawer.setHeight(Ext.getViewportHeight());
    }
  },
  destroy() {
    removeEventListener("hashchange", this.onHashChange);
    removeEventListener("session-expired", this.onExpired);
    Ext.Viewport.un("resize", this.onViewportResize, this);
    this.callParent();
  },
});
