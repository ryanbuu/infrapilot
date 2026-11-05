import React, { useEffect, useMemo, useRef, useState } from "react";
import { api, csrf, login, encode } from "./api";
import {
  Button,
  Field,
  Container,
  Panel,
  Toolbar,
  Form,
  Tree,
  Label,
  Alert,
  useEvent,
  useSize,
} from "./ui";
import { groups, allRoutes } from "./catalog";
import Workspace from "./Workspace";
const media = matchMedia("(prefers-color-scheme: dark)");
const brand = '<span class="brand-mark">IP</span><strong>InfraPilot</strong>';
function Navigation({ user, route, navigate, onClose, mobile }) {
  const tree = useRef(null);
  const store = useMemo(
    () => ({
      root: {
        expanded: true,
        children: groups
          .filter(
            ([group]) =>
              user.admin || !["管理", "Application Gateway"].includes(group),
          )
          .map(([text, items], i) => ({
            id: "group-" + i,
            text,
            expanded: text !== "Application Gateway",
            selectable: false,
            children: items.map(([id, text, icon]) => ({
              id,
              text,
              iconCls: "x-fa fa-" + icon,
              leaf: true,
            })),
          })),
      },
    }),
    [user.admin],
  );
  const sync = useEvent(() => {
    const c = tree.current;
    if (c && !c.destroyed) {
      const record = c.getStore().getNodeById(route);
      if (record) c.setSelection(record);
    }
  });
  useEffect(sync, [route]);
  return (
    <>
      <Toolbar cls="brand-bar" height={72} padding="12 18">
        <Label cls="brand" html={brand} flex={1} />
        {mobile && (
          <Button
            iconCls="x-fa fa-times"
            ariaLabel="关闭导航"
            onTap={onClose}
          />
        )}
      </Toolbar>
      <Container
        scrollable="vertical"
        flex={1}
        cls="navigation-body"
        padding="12 8"
      >
        <Tree
          cls="native-navigation"
          store={store}
          expanderFirst
          expanderOnly
          singleExpand={false}
          onReady={useEvent(({ cmp }) => {
            tree.current = cmp;
            sync();
          })}
          onSelectionchange={useEvent(({ record }) => {
            if (record?.get("leaf") && record.id !== route) navigate(record.id);
          })}
          onItemclick={useEvent(({ info }) => {
            if (info?.item?.getNode()?.get("leaf")) onClose?.();
          })}
        />
      </Container>
      <Toolbar cls="user-bar" height={70} padding="12 18">
        <Label
          html={`<strong>${encode(user.username)}</strong><small>${user.admin ? "管理员" : "只读用户"}</small>`}
          flex={1}
        />
        <Button
          iconCls="x-fa fa-sign-out"
          ariaLabel="退出登录"
          onTap={async () => {
            await api("/logout", {});
            dispatchEvent(new Event("session-expired"));
          }}
        />
      </Toolbar>
    </>
  );
}
export default function App() {
  const [user, setUser] = useState(null),
    [meta, setMeta] = useState(null),
    [pending, setPending] = useState(true),
    [error, setError] = useState(""),
    [username, setUsername] = useState(""),
    [password, setPassword] = useState(""),
    [route, setRoute] = useState(location.hash.slice(2) || "overview"),
    [theme, setTheme] = useState(
      localStorage.getItem("infrapilot-extreact-theme") || "system",
    ),
    [darkSystem, setDarkSystem] = useState(media.matches),
    [menu, setMenu] = useState(false);
  const { mobile, height } = useSize();
  const dark = theme === "dark" || (theme === "system" && darkSystem);
  useEffect(() => {
    const change = (e) => setDarkSystem(e.matches);
    media.addEventListener("change", change);
    return () => media.removeEventListener("change", change);
  }, []);
  useEffect(() => {
    Ext.theme.Material.setColors({
      base: "green",
      baseWeight: "700",
      accent: "green",
      accentWeight: "700",
      darkMode: dark,
    });
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("infrapilot-extreact-theme", theme);
  }, [theme, dark]);
  useEffect(() => {
    const change = () => {
      const id = location.hash.slice(2) || "overview";
      setRoute(allRoutes.some((r) => r.id === id) ? id : "overview");
      setMenu(false);
    };
    const expired = () => {
      setUser(null);
      setPassword("");
    };
    addEventListener("hashchange", change);
    addEventListener("session-expired", expired);
    Promise.all([api("/meta"), csrf()])
      .then(async ([m]) => {
        setMeta(m);
        try {
          setUser(await api("/session"));
          setError("");
        } catch {}
      })
      .catch((e) => setError(e.message))
      .finally(() => setPending(false));
    return () => {
      removeEventListener("hashchange", change);
      removeEventListener("session-expired", expired);
    };
  }, []);
  const navigate = (id) => {
    location.hash = "#/" + id;
    setRoute(id);
    setMenu(false);
  };
  async function signIn() {
    setPending(true);
    setError("");
    try {
      await login(username, password);
      setUser(await api("/session"));
      setPassword("");
    } catch (e) {
      setError(e.message);
    } finally {
      setPending(false);
    }
  }
  const themeControl = (
    <Field
      kind="select"
      ariaLabel="主题"
      cls="theme-select"
      width={mobile ? 116 : 138}
      value={theme}
      options={[
        { text: "跟随系统", value: "system" },
        { text: "浅色模式", value: "light" },
        { text: "深色模式", value: "dark" },
      ]}
      onChange={setTheme}
    />
  );
  const current = allRoutes.find((r) => r.id === route) || allRoutes[0];
  const showDrawer = useEvent(({ cmp }) => cmp.show());
  const hideDrawer = useEvent(() => setMenu(false));
  return (
    <Container viewport layout="fit" cls="app-viewport">
      {!user ? (
        <Container
          key="login"
          cls="login-page"
          layout={{ type: "vbox", align: "center", pack: "center" }}
          scrollable="vertical"
          padding={20}
        >
          <Toolbar
            docked="top"
            cls="login-theme"
            layout={{ type: "hbox", pack: "end" }}
          >
            {themeControl}
          </Toolbar>
          <Panel
            cls="login-card"
            width={400}
            maxWidth="100%"
            padding={28}
            layout="vbox"
          >
            <Label cls="brand login-brand" html={brand} margin="0 0 24 0" />
            <Label cls="login-title" text="欢迎回来" margin="0 0 20 0" />
            <Form cls="login-form" layout="vbox">
              <Field
                label="用户名"
                name="username"
                value={username}
                onChange={setUsername}
                margin="0 0 16 0"
              />
              <Field
                label="密码"
                name="password"
                inputType="password"
                value={password}
                onChange={setPassword}
                margin="0 0 20 0"
              />
              {error && <Alert text={error} />}
              <Button
                text={pending ? "连接中…" : "登录工作空间"}
                ui="action"
                disabled={pending || !username || !password}
                onTap={signIn}
                height={44}
                margin="0 0 12 0"
              />
              {meta?.demo && (
                <Button
                  text="填入演示账户"
                  ui="flat"
                  height={44}
                  onTap={() => {
                    setUsername("admin");
                    setPassword("infrapilot-demo");
                  }}
                />
              )}
            </Form>
            <Label
              cls="preview-label"
              text="ExtReact 独立预览"
              margin="20 0 0 0"
            />
          </Panel>
        </Container>
      ) : (
        <Container key="workspace" layout="hbox" cls="app-shell">
          {!mobile && (
            <Panel key="desktop-nav" width={236} cls="sidebar" layout="vbox">
              <Navigation user={user} route={route} navigate={navigate} />
            </Panel>
          )}
          <Container flex={1} layout="vbox" cls="main-shell">
            <Toolbar
              cls="app-header"
              height={mobile ? 64 : 72}
              padding={mobile ? "8 12" : "10 28"}
            >
              {mobile && (
                <Button
                  cls="menu-trigger"
                  iconCls="x-fa fa-bars"
                  ariaLabel="打开菜单"
                  onTap={() => setMenu(true)}
                />
              )}
              <Label
                cls="breadcrumb"
                flex={1}
                text={
                  mobile ? current.title : current.group + " / " + current.title
                }
              />
              {themeControl}
              {!mobile && (
                <Label
                  cls="connection"
                  text={meta?.demo ? "● 演示环境" : "● 生产连接"}
                  margin="0 0 0 18"
                />
              )}
            </Toolbar>
            <Container flex={1} layout="fit">
              <Workspace
                key={route}
                route={route}
                user={user}
                navigate={navigate}
              />
            </Container>
          </Container>
          {mobile && menu && (
            <Panel
              key="mobile-nav"
              cls="sidebar mobile-sidebar"
              layout="vbox"
              floated
              modal
              hideOnMaskTap
              left={0}
              top={0}
              width={280}
              height={height}
              shadow
              onReady={showDrawer}
              onHide={hideDrawer}
            >
              <Navigation
                user={user}
                route={route}
                navigate={navigate}
                mobile
                onClose={() => setMenu(false)}
              />
            </Panel>
          )}
        </Container>
      )}
    </Container>
  );
}
