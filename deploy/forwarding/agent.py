#!/usr/bin/env python3
"""Restricted SSH command for BIND forwarding, not a shell or a general file API.

Install root-owned outside the web application. The public key must use
restrict,from=...,command=".../agent.py" in authorized_keys. One bounded JSON
request is read from stdin. All paths and view names come from local root-owned
configuration, never from the request.
"""
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import uuid

MAX_INPUT = 262144


class Failure(Exception):
    def __init__(self, message, code=409):
        self.message, self.code = message, code


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def canonical(rules):
    if not isinstance(rules, list) or len(rules) > 256:
        raise Failure("规则数量无效，最多 256 条", 400)
    result, domains = [], set()
    for rule in rules:
        if not isinstance(rule, dict):
            raise Failure("规则格式无效", 400)
        def domain(value):
            if not isinstance(value, str):
                raise Failure("域名格式无效", 400)
            try:
                value = value.strip().rstrip(".").encode("idna").decode().lower()
            except UnicodeError:
                raise Failure("域名格式无效", 400)
            if len(value) > 253 or not value or any(
                not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", part)
                for part in value.split(".")
            ):
                raise Failure("请输入完整域名，不支持通配符或根区域", 400)
            return value
        name = domain(rule.get("domain"))
        if "." not in name or name in domains:
            raise Failure("域名重复或不是完整域名", 400)
        domains.add(name)
        transport, mode = rule.get("transport"), rule.get("forward")
        port, addresses = rule.get("port"), rule.get("addresses")
        if transport not in ("DNS", "DOT") or mode not in ("only", "first"):
            raise Failure("转发协议或模式无效", 400)
        if type(port) is not int or not 1 <= port <= 65535:
            raise Failure("端口须为 1–65535", 400)
        if not isinstance(addresses, list) or not 1 <= len(addresses) <= 8:
            raise Failure("请提供 1–8 个上游 IP", 400)
        try:
            ips = sorted({str(ipaddress.ip_address(a)) for a in addresses})
        except (ValueError, TypeError):
            raise Failure("上游必须是 IPv4 或 IPv6 地址", 400)
        if any(ipaddress.ip_address(a).is_unspecified or ipaddress.ip_address(a).is_multicast for a in ips):
            raise Failure("上游不能是未指定地址或组播地址", 400)
        hostname = domain(rule.get("tlsServerName", "")) if transport == "DOT" else ""
        description = rule.get("description", "")
        if not isinstance(description, str) or len(description) > 200:
            raise Failure("备注最多 200 个字符", 400)
        result.append(dict(domain=name, transport=transport, forward=mode, port=port,
                           addresses=ips, tlsServerName=hostname, description=description.strip()))
    return sorted(result, key=lambda r: r["domain"])


def render(rules):
    tls, zones, profiles = [], [], set()
    for r in canonical(rules):
        transport = ""
        if r["transport"] == "DOT":
            name = "infrapilot-dot-" + hashlib.sha256(r["tlsServerName"].encode()).hexdigest()[:16]
            if name not in profiles:
                tls.append(f'tls "{name}" {{ remote-hostname "{r["tlsServerName"]}"; '
                           'ca-file "/etc/ssl/certs/ca-certificates.crt"; };\n')
                profiles.add(name)
            transport = f' tls "{name}"'
        zones.append(f'zone "{r["domain"]}" {{ type forward; forward {r["forward"]}; '
                     f'forwarders port {r["port"]}{transport} {{ '
                     + " ".join(a + ";" for a in r["addresses"]) + " }; };\n")
    return {"tls.conf": "".join(tls), "zones.conf": "".join(zones),
            "policies.json": json.dumps(canonical(rules), ensure_ascii=False, indent=2) + "\n"}


TOKEN = re.compile(r'\s+|//[^\n]*|\#[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{};]|[^\s{};"]+', re.S)


def statements(source):
    """Small structural parser; BIND's own named-checkconf remains the validator."""
    tokens = [(m.group(), m.start(), m.end()) for m in TOKEN.finditer(source)
              if not m.group().isspace() and not m.group().startswith(("//", "#", "/*"))]
    def parse(index, nested=False):
        result = []
        while index < len(tokens) and tokens[index][0] != "}":
            start, words = tokens[index][1], []
            while index < len(tokens) and tokens[index][0] not in ("{", ";", "}"):
                value = tokens[index][0]
                words.append(value[1:-1] if value.startswith('"') else value)
                index += 1
            if index >= len(tokens) or (not words and tokens[index][0] != "{"):
                raise Failure("无法解析 BIND 配置", 502)
            children, suffix = None, []
            if tokens[index][0] == "{":
                children, index = parse(index + 1, True)
                # Compound statements such as controls' "allow { ... } keys { ... };"
                # have another block after the first. Keep its exact tokens for the
                # non-forwarding fingerprint; do not interpret or discard them.
                depth = 0
                while index < len(tokens) and not (tokens[index][0] == ";" and depth == 0):
                    value = tokens[index][0]
                    if value == "{": depth += 1
                    elif value == "}":
                        if depth == 0: break
                        depth -= 1
                    suffix.append(value)
                    index += 1
            if index >= len(tokens) or tokens[index][0] != ";":
                raise Failure("无法解析 BIND 配置分隔符", 502)
            result.append(dict(words=words, children=children, suffix=suffix, start=start, end=tokens[index][2]))
            index += 1
        if nested:
            if index >= len(tokens) or tokens[index][0] != "}":
                raise Failure("BIND 配置括号不完整", 502)
            index += 1
        return result, index
    return parse(0)[0]


def zone_statements(tree):
    for s in tree:
        if s["words"][0] == "view":
            for z in s["children"] or []:
                if z["words"][0] == "zone":
                    yield s["words"][1], z
        elif s["words"][0] == "zone":
            yield "_default", s


def forward_rules(source):
    tree = statements(source)
    profiles = {s["words"][1]: {c["words"][0]: c["words"][1:] for c in s["children"] or []}
                for s in tree if s["words"][0] == "tls"}
    result, protected = [], set()
    for view, zone in zone_statements(tree):
        fields = {c["words"][0]: c for c in zone["children"] or []}
        if fields.get("type", {}).get("words") != ["type", "forward"]:
            protected.add(zone["words"][1].rstrip(".").lower())
            continue
        if set(fields) - {"type", "forward", "forwarders"} or "forwarders" not in fields:
            raise Failure("存在不受支持的条件转发选项，已停止写入以保留配置", 409)
        f = fields["forwarders"]
        words = f["words"][1:]
        def option(key, fallback):
            return words[words.index(key) + 1] if key in words else fallback
        tls = option("tls", "")
        profile = profiles.get(tls, {})
        if tls and (set(profile) - {"remote-hostname", "ca-file"} or
                    profile.get("ca-file") != ["/etc/ssl/certs/ca-certificates.crt"]):
            raise Failure("TLS 配置包含不受支持的选项，已停止写入", 409)
        if any(c["children"] is not None or len(c["words"]) != 1 for c in f["children"] or []):
            raise Failure("存在按地址配置的转发选项，需先人工接入", 409)
        rule = dict(domain=zone["words"][1], transport="DOT" if tls else "DNS",
                    forward=fields.get("forward", {}).get("words", ["forward", "first"])[1],
                    port=int(option("port", 853 if tls else 53)),
                    addresses=[c["words"][0] for c in f["children"] or []],
                    tlsServerName=profile.get("remote-hostname", [""])[0], description="")
        result.append((view, canonical([rule])[0]))
    return result, protected


def atomic(path, data, mode=0o600, gid=None):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix=".infrapilot-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as f:
            os.fchmod(f.fileno(), mode)
            if gid is not None:
                os.fchown(f.fileno(), -1, gid)
            f.write(data.encode() if isinstance(data, str) else data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
        d = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(d)
        finally:
            os.close(d)
    finally:
        if os.path.exists(name):
            os.unlink(name)


class Agent:
    def __init__(self, config="/etc/infrapilot-forwarding.json", runner=None):
        self.config = json.loads(Path(config).read_text())
        self.main = Path(self.config["main"])
        self.managed = Path(self.config["managedDirectory"])
        self.state = Path(self.config["stateDirectory"])
        self.runner = runner or self.run

    def run(self, args):
        # The full configuration can reference plugins managed by the BIND UI.
        # Loading those while validating must use named's account, never root.
        if args[0] == "named-checkconf":
            args = ["runuser", "-u", "bind", "--"] + args
        try:
            p = subprocess.run(args, capture_output=True, text=True, timeout=25,
                               env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C"})
        except subprocess.TimeoutExpired:
            raise Failure("BIND 命令超时，需核实事务状态", 502)
        if p.returncode:
            # Do not return raw named-checkconf stderr: it may contain config fragments.
            raise Failure("BIND 配置校验或重载失败，请检查节点日志", 502)
        return p.stdout

    def generation(self):
        target = os.readlink(self.managed / "current")
        if not re.fullmatch(r"generations/[a-zA-Z0-9-]{1,80}", target):
            raise Failure("配置代次指针无效", 502)
        return target

    def snapshot(self):
        generation = self.generation()
        rules = canonical(json.loads((self.managed / generation / "policies.json").read_text()))
        rendered = render(rules)
        for name, data in rendered.items():
            if (self.managed / generation / name).read_text() != data:
                raise Failure("受管配置被外部修改，已暂停写入", 409)
        effective = self.runner(["named-checkconf", "-p", "-x", str(self.main)])
        actual, protected = forward_rules(effective)
        expected = [{**r, "description": ""} for r in rules]
        drift = (set(v for v, _ in actual) != set(self.config["views"]) if rules else bool(actual))
        for view in self.config["views"]:
            if canonical([r for v, r in actual if v == view]) != expected:
                drift = True
        pending = json.loads((self.state / "active.json").read_text()) if (self.state / "active.json").exists() else None
        bind_pending = Path(self.config.get("bindStateDirectory", "/var/lib/infrapilot-bind")) / "active.json"
        if pending is None and bind_pending.exists():
            pending = {**json.loads(bind_pending.read_text()), "module": "BIND"}
        return dict(node=self.config["id"], rules=rules, digest=digest(rules),
                    revision=digest([effective, generation, rendered]), drift=drift,
                    pending=pending, protected=sorted(protected), generation=generation)

    def guard(self, rules, protected):
        for r in rules:
            name = r["domain"]
            if any(name == p or name.endswith("." + p) or p.endswith("." + name)
                   for p in set(protected) | set(self.config.get("protectedZones", []))
                   if p != "."):
                raise Failure("转发域名与本地权威区域重叠，已拒绝覆盖", 400)

    def candidate(self, tx, rules):
        path = self.managed / "generations" / tx
        path.mkdir(mode=0o750, exist_ok=True)
        path.chmod(0o750)  # The forced command intentionally starts with umask 077.
        gid = self.managed.stat().st_gid
        os.chown(path, -1, gid)
        for name, data in render(rules).items():
            atomic(path / name, data, 0o640, gid)
        # Validate against all existing authoritative/HA/DDNS/DNSSEC configuration.
        source = self.main.read_text()
        for name in ("tls.conf", "zones.conf"):
            old = str(self.managed / "current" / name)
            if source.count('"' + old + '"') != (1 if name == "tls.conf" else len(self.config["views"])):
                raise Failure("受管 include 结构已改变，已暂停写入", 409)
            source = source.replace('"' + old + '"', '"' + str(path / name) + '"')
        check = path / "validation.conf"
        try:
            atomic(check, source, 0o640, gid)
            self.runner(["named-checkconf", str(check)])
        finally:
            check.unlink(missing_ok=True)
        return "generations/" + tx

    def txfile(self, tx):
        if not isinstance(tx, str) or not re.fullmatch(r"[a-f0-9-]{36}", tx):
            raise Failure("事务标识无效", 400)
        return self.state / (tx + ".json")

    def save(self, record):
        atomic(self.txfile(record["id"]), json.dumps(record, ensure_ascii=False, indent=2) + "\n")

    def point(self, generation):
        path = self.managed / ".next"
        path.unlink(missing_ok=True)
        os.symlink(generation, path)
        os.replace(path, self.managed / "current")
        d = os.open(self.managed, os.O_RDONLY)
        try:
            os.fsync(d)
        finally:
            os.close(d)

    def handle(self, request):
        operation = request.get("operation")
        if operation == "read":
            return self.snapshot()
        if operation in ("validate", "prepare"):
            old = self.snapshot()
            if old["drift"]:
                raise Failure("节点配置存在外部差异，请先核对配置", 409)
            if request.get("revision") != old["revision"]:
                raise Failure("配置版本已变化，请刷新后重试", 409)
            rules = canonical(request.get("rules"))
            self.guard(rules, old["protected"])
            tx = request.get("transaction") if operation == "prepare" else str(uuid.uuid4())
            f = self.txfile(tx)
            if operation == "prepare" and f.exists():
                record = json.loads(f.read_text())
                if record["revision"] != request["revision"] or record["rules"] != rules:
                    raise Failure("事务标识已使用", 409)
                return {"transaction": tx, "status": record["status"]}
            if old["pending"]:
                raise Failure("节点存在待结束事务", 409)
            generation = self.candidate(tx, rules)
            if operation == "validate":
                import shutil
                shutil.rmtree(self.managed / generation)
                return {"valid": True, "digest": digest(rules)}
            record = dict(id=tx, status="prepared", revision=old["revision"],
                          before=old["generation"], after=generation, rules=rules,
                          mainHash=hashlib.sha256(self.main.read_bytes()).hexdigest(), created=time.time())
            self.save(record)
            atomic(self.state / "active.json", json.dumps({"id": tx, "status": "prepared"}))
            return {"transaction": tx, "status": "prepared"}
        tx = request.get("transaction")
        f = self.txfile(tx)
        if not f.exists():
            if operation == "abort":
                return {"transaction": tx, "status": "not-prepared"}
            raise Failure("事务不存在", 404)
        record = json.loads(f.read_text())
        if operation not in ("commit", "abort", "finalize", "status"):
            raise Failure("不支持的操作", 400)
        if operation == "status":
            return {"transaction": tx, "status": record["status"]}
        if record["mainHash"] != hashlib.sha256(self.main.read_bytes()).hexdigest():
            raise Failure("主配置已被外部修改，请人工核实事务", 409)
        active = json.loads((self.state / "active.json").read_text()) if (self.state / "active.json").exists() else None
        if active and active["id"] != tx:
            raise Failure("另一个事务正在运行", 409)
        if operation == "commit":
            if record["status"] in ("applied", "complete"):
                if self.generation() != record["after"]:
                    raise Failure("已提交事务的配置已变化", 409)
                return {"transaction": tx, "status": record["status"], "digest": digest(record["rules"])}
            if record["status"] != "prepared" or self.snapshot()["revision"] != record["revision"]:
                raise Failure("提交前版本检查失败", 409)
            record["status"] = "applying"
            self.save(record)
            self.point(record["after"])
            self.runner(["named-checkconf", str(self.main)])
            self.runner(["rndc", "reconfig"])
            self.runner(["rndc", "status"])
            record["status"] = "applied"
            self.save(record)
            atomic(self.state / "active.json", json.dumps({"id": tx, "status": "applied"}))
            return {"transaction": tx, "status": "applied", "digest": digest(record["rules"])}
        if operation == "abort":
            if record["status"] in ("rolled-back", "aborted"):
                return {"transaction": tx, "status": record["status"]}
            if record["status"] == "complete":
                raise Failure("已完成事务请通过新变更恢复备份", 409)
            if self.generation() not in (record["before"], record["after"]):
                raise Failure("配置代次已被外部修改，拒绝覆盖", 409)
            if record["status"] != "prepared":
                # Verify immutable generations before reverting; never restore modified files.
                for generation in (record["before"], record["after"]):
                    p = self.managed / generation
                    rules = canonical(json.loads((p / "policies.json").read_text()))
                    if any((p / k).read_text() != v for k, v in render(rules).items()):
                        raise Failure("回滚配置被修改，需人工恢复", 409)
                self.point(record["before"])
                self.runner(["named-checkconf", str(self.main)])
                self.runner(["rndc", "reconfig"])
                self.runner(["rndc", "status"])
            record["status"] = "rolled-back"
        else:
            if record["status"] not in ("applied", "complete") or self.generation() != record["after"]:
                raise Failure("事务尚未提交，不能结束", 409)
            record["status"] = "complete"
        self.save(record)
        if active and active["id"] == tx:
            (self.state / "active.json").unlink(missing_ok=True)
        return {"transaction": tx, "status": record["status"]}


def main():
    os.umask(0o077)
    try:
        data = sys.stdin.buffer.read(MAX_INPUT + 1)
        if len(data) > MAX_INPUT:
            raise Failure("请求过大", 413)
        request = json.loads(data)
        if not isinstance(request, dict):
            raise Failure("请求格式无效", 400)
        agent = Agent()
        with (agent.state / ".lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            result = agent.handle(request)
        print(json.dumps({"ok": True, "result": result}, ensure_ascii=False))
    except Failure as e:
        print(json.dumps({"ok": False, "code": e.code, "message": e.message}, ensure_ascii=False))
    except Exception:
        # No traceback on the restricted channel: paths/configuration may be sensitive.
        print(json.dumps({"ok": False, "code": 502, "message": "条件转发代理异常，请检查节点配置"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
