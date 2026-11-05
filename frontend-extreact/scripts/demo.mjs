import { spawn } from "node:child_process";
import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const jar = resolve(root, "../backend/target/infrapilot-0.1.0.jar");
if (!existsSync(jar))
  throw Error(
    "Build the backend first: mvn -f ../backend/pom.xml clean package -DskipTests",
  );
if (!existsSync(join(root, "public/vendor/ext/build/ext-modern-all.js")))
  throw Error("Install the GPL preview runtime first: npm run sdk");
const work = mkdtempSync(join(tmpdir(), "infrapilot-extreact-demo-"));
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin/java")
  : "java";
let web,
  stopping = false;
const backend = spawn(
  java,
  [
    "-jar",
    jar,
    "--server.address=127.0.0.1",
    "--server.port=18085",
    "--infrapilot.mode=demo",
    "--spring.datasource.url=jdbc:h2:mem:extreact-preview",
  ],
  { cwd: work, stdio: ["ignore", "pipe", "pipe"] },
);
let log = "";
for (const stream of [backend.stdout, backend.stderr])
  stream.on("data", (chunk) => {
    log = (log + chunk).slice(-8000);
  });
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  web?.kill();
  backend.kill();
  setTimeout(() => process.exit(code), 100);
}
backend.on("error", (e) => {
  console.error(e.message);
  stop(1);
});
backend.on("exit", (code) => {
  if (!stopping) {
    console.error(log);
    stop(code || 1);
  }
});
process.on("SIGINT", () => stop());
process.on("SIGTERM", () => stop());
for (let i = 0; i < 60 && !stopping; i++) {
  try {
    const r = await fetch("http://127.0.0.1:18085/api/meta");
    if (r.ok && (await r.json()).demo) break;
  } catch {}
  if (i === 59) {
    console.error("Demo backend did not become ready.\n" + log);
    stop(1);
  }
  await new Promise((r) => setTimeout(r, 500));
}
if (!stopping) {
  web = spawn(
    process.execPath,
    [
      join(root, "node_modules/vite/bin/vite.js"),
      "--host",
      "127.0.0.1",
      "--port",
      "5181",
      "--strictPort",
    ],
    {
      cwd: root,
      stdio: "inherit",
      env: { ...process.env, API_PROXY_TARGET: "http://127.0.0.1:18085" },
    },
  );
  web.on("exit", (code) => stop(code || 0));
  console.log(
    "ExtReact preview: http://127.0.0.1:5181\nDemo data is isolated; use “填入演示账户”.",
  );
}
