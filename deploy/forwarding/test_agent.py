import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location("agent", Path(__file__).with_name("agent.py"))
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)
import_spec = importlib.util.spec_from_file_location("import_config", Path(__file__).with_name("import_config.py"))
import sys
sys.modules["agent"] = agent
importer = importlib.util.module_from_spec(import_spec)
import_spec.loader.exec_module(importer)


def rule(domain="example.net", **values):
    return dict(domain=domain, transport="DOT", forward="only", port=853,
                addresses=["192.0.2.53"], tlsServerName="resolver.example.net", description="", **values)


class AgentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.managed, self.state = self.root / "managed", self.root / "state"
        self.state.mkdir()
        initial = self.managed / "generations/initial"
        initial.mkdir(parents=True)
        for name, text in agent.render([rule()]).items():
            (initial / name).write_text(text)
        (self.managed / "current").symlink_to("generations/initial")
        self.main = self.root / "named.conf"
        self.main.write_text(f'include "{self.managed}/current/tls.conf";\n'
                             f'view "readers" {{ zone "intra.example.com" {{ type primary; file "db"; }}; '
                             f'include "{self.managed}/current/zones.conf"; }};\n')
        self.config = self.root / "agent.json"
        self.config.write_text(json.dumps(dict(id="bind1", main=str(self.main), managedDirectory=str(self.managed),
                                                stateDirectory=str(self.state), views=["readers"])))
        self.calls, self.fail_reconfig = [], False
        self.instance = agent.Agent(self.config, self.run_command)

    def expand(self, path):
        import re
        return re.sub(r'include "([^"]+)";', lambda m: self.expand(m.group(1)), Path(path).read_text())

    def run_command(self, args):
        self.calls.append(args)
        if args[0] == "named-checkconf":
            return self.expand(args[-1]) if "-p" in args else ""
        if args == ["rndc", "reconfig"] and self.fail_reconfig:
            raise agent.Failure("simulated reload failure", 502)
        return "running"

    def prepare(self, rules=None):
        tx = str(uuid.uuid4())
        before = self.instance.snapshot()
        self.instance.handle(dict(operation="prepare", transaction=tx, revision=before["revision"],
                                  rules=rules if rules is not None else [rule("example.org")]))
        return tx, before

    def test_prepare_is_offline_and_abort_is_idempotent(self):
        tx, before = self.prepare()
        self.assertEqual(before["generation"], self.instance.generation())
        self.assertFalse(any(a[0] == "rndc" for a in self.calls))
        for _ in range(2):
            self.instance.handle(dict(operation="abort", transaction=tx))
        self.assertEqual(before["revision"], self.instance.snapshot()["revision"])
        self.assertIsNone(self.instance.snapshot()["pending"])

    def test_bind_manager_pending_transaction_blocks_forwarding_mutations(self):
        state = self.root / "bind-state"
        state.mkdir()
        self.instance.config["bindStateDirectory"] = str(state)
        before = self.instance.snapshot()
        (state / "active.json").write_text(json.dumps(dict(id=str(uuid.uuid4()), status="prepared")))
        self.assertEqual("BIND", self.instance.snapshot()["pending"]["module"])
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="prepare", transaction=str(uuid.uuid4()), revision=before["revision"], rules=[rule("example.org")]))

    def test_prepared_files_remain_readable_by_bind_with_private_process_umask(self):
        previous = os.umask(0o077)
        try:
            tx, _ = self.prepare()
        finally:
            os.umask(previous)
        directory = self.managed / "generations" / tx
        self.assertEqual(0o750, directory.stat().st_mode & 0o777)
        for name in ("tls.conf", "zones.conf", "policies.json"):
            self.assertEqual(0o640, (directory / name).stat().st_mode & 0o777)

    def test_committed_generation_rolls_back_after_restart(self):
        tx, before = self.prepare()
        self.instance.handle(dict(operation="commit", transaction=tx))
        restarted = agent.Agent(self.config, self.run_command)
        self.assertEqual("example.org", restarted.snapshot()["rules"][0]["domain"])
        restarted.handle(dict(operation="abort", transaction=tx))
        self.assertEqual(before["revision"], restarted.snapshot()["revision"])
        self.assertEqual(2, self.calls.count(["rndc", "reconfig"]))

    def test_failed_reconfig_keeps_recoverable_transaction(self):
        tx, before = self.prepare()
        self.fail_reconfig = True
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="commit", transaction=tx))
        self.assertEqual("applying", json.loads((self.state / (tx + ".json")).read_text())["status"])
        self.fail_reconfig = False
        self.instance.handle(dict(operation="abort", transaction=tx))
        self.assertEqual(before["revision"], self.instance.snapshot()["revision"])

    def test_completed_transaction_is_not_overwritten_by_old_abort(self):
        tx, _ = self.prepare()
        self.instance.handle(dict(operation="commit", transaction=tx))
        for _ in range(2):
            self.instance.handle(dict(operation="finalize", transaction=tx))
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="abort", transaction=tx))
        self.assertEqual("example.org", self.instance.snapshot()["rules"][0]["domain"])

    def test_live_configuration_guard_blocks_stale_commit(self):
        tx, _ = self.prepare()
        self.main.write_text(self.main.read_text() + "// external edit\n")
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="commit", transaction=tx))
        self.assertEqual("generations/initial", self.instance.generation())

    def test_external_forward_zone_is_detected_and_not_silently_removed(self):
        self.main.write_text(self.main.read_text().replace('view "readers" {',
            'view "readers" { zone "extra.example.org" { type forward; forward only; forwarders { 192.0.2.60; }; };'))
        snapshot = self.instance.snapshot()
        self.assertTrue(snapshot["drift"])
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="prepare", transaction=str(uuid.uuid4()),
                                      revision=snapshot["revision"], rules=[]))

    def test_authoritative_parent_and_child_are_protected(self):
        for name in ["intra.example.com", "child.intra.example.com", "example.com"]:
            with self.subTest(domain=name), self.assertRaises(agent.Failure):
                self.prepare([rule(name)])

    def test_configuration_injection_and_path_traversal_are_rejected(self):
        invalid = [dict(rule(), domain='x"; include "/etc/shadow";'),
                   dict(rule(), addresses=['192.0.2.53; };']),
                   dict(rule(), tlsServerName='example.org";'), dict(rule(), port=True),
                   dict(rule(), domain="*"), dict(rule(), domain="."), dict(rule(), addresses=["0.0.0.0"])]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(agent.Failure):
                agent.canonical([value])
        with self.assertRaises(agent.Failure):
            self.instance.handle(dict(operation="abort", transaction="../../secret"))

    def test_parser_handles_comments_nested_acls_and_quoted_braces(self):
        source = '/* zone "evil" { }; */ options { directory "/tmp/{x}"; }; '\
                 'view "readers" { match-clients { key "key"; any; }; '\
                 'zone "example.net" { type forward; forwarders port 53 { 192.0.2.53; }; }; };'
        actual, protected = agent.forward_rules(source)
        self.assertEqual("DNS", actual[0][1]["transport"])
        self.assertEqual("readers", actual[0][0])
        self.assertEqual(set(), protected)

    def test_import_keeps_authoritative_acl_and_tsig_configuration(self):
        source = 'controls { inet 127.0.0.1 port 953 allow { 127.0.0.1; } keys { "rndc-key"; }; }; '\
                 'key "key" { algorithm hmac-sha256; secret "REDACTED"; }; '\
                 'view "writers" { match-clients { key "key"; }; '\
                 'zone "intra.example.com" { type primary; update-policy { grant key zonesub A; }; }; '\
                 'zone "example.net" { type forward; forward only; forwarders { 192.0.2.53; }; }; }; '\
                 'view "readers" { zone "intra.example.com" { type secondary; primaries { 192.0.2.10; }; }; '\
                 'zone "example.net" { type forward; forward only; forwarders { 192.0.2.53; }; }; };'
        transformed = importer.adopt(source, self.managed, ["writers", "readers"])
        expanded = transformed.replace(f'include "{self.managed}/current/tls.conf";', '')\
            .replace(f'include "{self.managed}/current/zones.conf";', '')
        self.assertEqual(importer.base(source), importer.base(expanded))
        self.assertIn('grant key zonesub A;', transformed)
        self.assertEqual(2, transformed.count('current/zones.conf'))


if __name__ == "__main__":
    unittest.main()
