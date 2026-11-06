#!/usr/bin/env python3
"""One-time, administrator-run adoption of existing forwarding zones.

This command is deliberately separate from the restricted agent. It only moves
forward zones to managed includes; it checks the rest of the expanded BIND
configuration is structurally identical. Run from a private backup directory.
"""
import argparse
import datetime
import grp
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from agent import Agent, Failure, atomic, canonical, forward_rules, render, statements


def base(source):
    def tree(items):
        out = []
        for item in items:
            words, children = item["words"], item["children"]
            if words and words[0] == "tls" and words[1].startswith("infrapilot-dot-"):
                continue
            if words and words[0] == "zone" and any(c["words"] == ["type", "forward"] for c in children or []):
                continue
            out.append([words, tree(children) if children is not None else None, item["suffix"]])
        return out
    return tree(statements(source))


def adopt(source, managed, views):
    items, edits = statements(source), []
    def forward(item):
        return item["words"][0] == "zone" and any(
            c["words"] == ["type", "forward"] for c in item["children"] or [])
    if views == ["_default"]:
        targets = [s for s in items if forward(s)]
        include = f'include "{managed}/current/zones.conf";'
        if targets:
            edits.extend((s["start"], s["end"], include if i == 0 else "") for i, s in enumerate(targets))
        else:
            edits.append((len(source), len(source), "\n" + include + "\n"))
    else:
        for view in views:
            container = next(s for s in items if s["words"] == ["view", view] or s["words"] == ["view", view, "IN"])
            targets = [s for s in container["children"] or [] if forward(s)]
            include = f'include "{managed}/current/zones.conf";'
            if targets:
                edits.extend((s["start"], s["end"], include if i == 0 else "") for i, s in enumerate(targets))
            else:
                pos = source.rfind("}", container["start"], container["end"])
                edits.append((pos, pos, "\n" + include + "\n"))
    for start, end, replacement in sorted(edits, reverse=True):
        source = source[:start] + replacement + source[end:]
    return f'include "{managed}/current/tls.conf";\n' + source


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--id", required=True, choices=["bind1", "bind2"])
    args.add_argument("--main", default="/etc/bind/named.conf")
    opts = args.parse_args()
    os.umask(0o077)
    if os.geteuid() != 0:
        raise SystemExit("Run as the BIND node administrator")
    main_path = Path(opts.main)
    managed = Path("/etc/bind/infrapilot-forwarding")
    state = Path("/var/lib/infrapilot-forwarding")
    config = Path("/etc/infrapilot-forwarding.json")
    if config.exists():
        status = Agent(config).snapshot()
        if status["drift"] or status["pending"]:
            raise SystemExit("Existing agent configuration needs reconciliation")
        print(json.dumps({"id": opts.id, "alreadyImported": True, "rules": status["rules"]}))
        return
    def run(argv):
        p = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        if p.returncode:
            raise Failure("BIND command failed; no diagnostic config fragments returned", 502)
        return p.stdout
    before = run(["named-checkconf", "-p", "-x", str(main_path)])
    raw = main_path.read_text()
    if str(managed) in raw:
        raise SystemExit("Managed includes exist without agent metadata; reconcile first")
    parsed = statements(raw)
    views = [s["words"][1] for s in parsed if s["words"][0] == "view"] or ["_default"]
    all_rules, protected = forward_rules(before)
    if set(v for v, _ in all_rules) - set(views):
        raise SystemExit("Forwarding zones are in unsupported includes/views")
    rules = canonical([r for v, r in all_rules if v == views[0]])
    if any(canonical([r for v, r in all_rules if v == view]) != rules for view in views):
        raise SystemExit("Views have different forwarding policies; explicit reconciliation required")
    candidate = adopt(raw, managed, views)
    gid = grp.getgrnam("bind").gr_gid
    managed.mkdir(mode=0o750, exist_ok=True); managed.chmod(0o750); os.chown(managed, -1, gid)
    generations = managed / "generations"; generations.mkdir(mode=0o750, exist_ok=True); generations.chmod(0o750); os.chown(generations, -1, gid)
    initial = generations / "initial"; initial.mkdir(mode=0o750, exist_ok=True); initial.chmod(0o750); os.chown(initial, -1, gid)
    state.mkdir(mode=0o700, exist_ok=True)
    backup = state / ("before-import-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup.mkdir(mode=0o700)
    shutil.copy2(main_path, backup / "named.conf")
    (backup / "rules.json").write_text(json.dumps(rules, ensure_ascii=False, indent=2) + "\n")
    for name, data in render(rules).items():
        atomic(initial / name, data, 0o640, gid)
    current = managed / "current"
    if current.is_symlink():
        if os.readlink(current) != "generations/initial":
            raise SystemExit("Unexpected existing configuration generation; reconcile first")
    elif current.exists():
        raise SystemExit("Unexpected current configuration path")
    else:
        current.symlink_to("generations/initial")
    test = managed / "candidate.conf"
    try:
        atomic(test, candidate)
        run(["named-checkconf", str(test)])
        after = run(["named-checkconf", "-p", "-x", str(test)])
        if base(before) != base(after):
            raise Failure("Unrelated BIND configuration changed; import refused")
        original_stat = main_path.stat()
        atomic(main_path, candidate, original_stat.st_mode & 0o777, original_stat.st_gid)
        run(["rndc", "reconfig"]); run(["rndc", "status"])
        metadata = dict(id=opts.id, main=str(main_path), managedDirectory=str(managed),
                        stateDirectory=str(state), views=views, protectedZones=sorted(protected))
        atomic(config, json.dumps(metadata, indent=2) + "\n")
        snapshot = Agent(config).snapshot()
        if snapshot["drift"] or snapshot["rules"] != rules:
            raise Failure("Post-import forwarding verification failed")
        manifest = dict(id=opts.id, backup=str(backup), rules=rules, views=views,
                        beforeHash=hashlib.sha256(raw.encode()).hexdigest(),
                        afterHash=hashlib.sha256(main_path.read_bytes()).hexdigest(),
                        nonForwardingConfigurationPreserved=True)
        atomic(backup / "manifest.json", json.dumps(manifest, indent=2) + "\n")
        print(json.dumps(manifest))
    except Exception:
        shutil.copy2(backup / "named.conf", main_path)
        run(["named-checkconf", str(main_path)]); run(["rndc", "reconfig"])
        config.unlink(missing_ok=True)
        raise
    finally:
        test.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
