import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import uuid

spec = importlib.util.spec_from_file_location('bind_agent', Path(__file__).with_name('agent.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.main = self.root / 'named.conf'
        self.main.write_text('key "application" { algorithm hmac-sha256; secret "ORIGINAL_PRIVATE_VALUE"; };\noptions { recursion yes; };\n')
        for d in ['state', 'forward', 'managed', 'zones']:
            (self.root / d).mkdir()
        config = dict(main=str(self.main), stateDirectory=str(self.root/'state'), managedDirectory=str(self.root/'managed'), zoneDirectory=str(self.root/'zones'), forwardingState=str(self.root/'forward'), views=['_default'], primaryView='_default', id='bind1', address='192.0.2.10', peerAddress='192.0.2.11', role='primary', applicationKey='application', transferKey='transfer', localTransferKey='local', protectedZones=['intra.example.com'])
        self.conf = self.root/'agent.json'; self.conf.write_text(json.dumps(config))
        self.fail_reload = False
        def runner(args, optional=False):
            if args[:2] == ['rndc', 'reconfig'] and self.fail_reload:
                self.fail_reload = False
                raise module.Failure('reload failed', 502)
            if args[:3] == ['named-checkconf', '-p', '-x']:
                return module.mask(Path(args[-1]).read_text(), 'effective')[0]
            if args == ['named', '-V']: return 'BIND 9.20.29\nbuild --enable-dnstap'
            return 'server is up and running'
        self.agent = module.Agent(self.conf, runner)

    def tearDown(self): self.tmp.cleanup()

    def request(self, op, **kwargs): return self.agent.handle(dict(operation=op, **kwargs))

    def edit(self):
        old = self.request('read')
        text = old['files'][0]['content'].replace('recursion yes', 'recursion no')
        return dict(revision=old['revision'], files=[dict(path=str(self.main), content=text)], assets=[])

    def test_redaction_is_stable_and_roundtrips(self):
        a = self.request('read'); b = self.request('read')
        self.assertNotIn('ORIGINAL_PRIVATE_VALUE', json.dumps(a))
        self.assertEqual(a['revision'], b['revision'])
        self.request('validate', **self.edit())
        self.assertIn('ORIGINAL_PRIVATE_VALUE', self.main.read_text())

    def test_commit_abort_and_restart_recovery(self):
        tx = str(uuid.uuid4()); self.request('prepare', transaction=tx, **self.edit())
        self.request('commit', transaction=tx)
        self.assertIn('recursion no', self.main.read_text())
        self.agent = module.Agent(self.conf, self.agent.runner)
        self.request('abort', transaction=tx)
        self.assertIn('recursion yes', self.main.read_text())
        self.assertIsNone(self.request('read')['pending'])

    def test_lost_commit_ack_is_idempotent(self):
        tx = str(uuid.uuid4()); self.request('prepare', transaction=tx, **self.edit())
        self.request('commit', transaction=tx); self.request('commit', transaction=tx)
        self.request('finalize', transaction=tx); self.request('finalize', transaction=tx)
        with self.assertRaises(module.Failure): self.request('abort', transaction=tx)

    def test_reload_failure_can_be_rolled_back(self):
        tx = str(uuid.uuid4()); self.request('prepare', transaction=tx, **self.edit()); self.fail_reload = True
        with self.assertRaises(module.Failure): self.request('commit', transaction=tx)
        self.request('abort', transaction=tx)
        self.assertIn('recursion yes', self.main.read_text())

    def test_foreign_pending_fences_prepare_and_commands(self):
        change = self.edit()
        (self.root/'forward/active.json').write_text(json.dumps(dict(id=str(uuid.uuid4()), status='prepared')))
        with self.assertRaises(module.Failure): self.request('prepare', transaction=str(uuid.uuid4()), **change)
        with self.assertRaises(module.Failure): self.request('command', command='status')

    def test_stale_revision_and_external_changes_are_rejected(self):
        change = self.edit(); self.main.write_text(self.main.read_text()+'// external\n')
        with self.assertRaises(module.Failure): self.request('validate', **change)
        tx = str(uuid.uuid4()); self.request('prepare', transaction=tx, **self.edit()); self.request('commit', transaction=tx)
        self.main.write_text(self.main.read_text()+'// conflicting update\n')
        with self.assertRaises(module.Failure): self.request('finalize', transaction=tx)
        with self.assertRaises(module.Failure): self.request('abort', transaction=tx)

    def test_path_and_include_traversal_rejected(self):
        for file in [dict(path='/etc/shadow', content='x'), dict(path=str(self.main), content='include "/etc/shadow";'), dict(path=str(self.root/'managed/../evil.conf'), content='x')]:
            with self.assertRaises(module.Failure): self.request('validate', revision=self.request('read')['revision'], files=[file])

    def test_secret_placeholder_cannot_be_forged(self):
        change = self.edit(); change['files'][0]['content'] = change['files'][0]['content'].replace('SECRET_', 'SECRET_ffff')
        with self.assertRaises(module.Failure): self.request('validate', **change)

    def test_zone_wizard_installs_primary_and_protects_core(self):
        old = self.request('read')
        plan = self.request('plan', revision=old['revision'], action=dict(operation='zone-create', name='lab.example.net', ns1='ns1.example.net', ns2='ns2.example.net', ttl=300))
        self.assertIn('type primary', plan['files'][0]['content'])
        self.assertEqual(1, len(plan['assets']))
        self.request('validate', revision=old['revision'], **plan)
        with self.assertRaises(module.Failure): self.request('plan', revision=old['revision'], action=dict(operation='zone-delete', name='intra.example.com'))

    def test_typed_commands_reject_shell_and_require_confirmation(self):
        for q in [dict(command='sh', arguments={}), dict(command='flushname', arguments=dict(name='a;rm -rf /'), confirmed=True), dict(command='flush', arguments={})]:
            with self.assertRaises(module.Failure): self.request('command', **q)
        self.assertIn('output', self.request('command', command='flushname', arguments=dict(name='test.example.com'), confirmed=True))

    def test_restore_rehydrates_old_secret_only_on_node(self):
        change = self.edit(); change['files'][0]['content'] = change['files'][0]['content'].replace(module.PLACEHOLDER.search(change['files'][0]['content'])[0], 'NEW_PRIVATE_VALUE')
        tx = str(uuid.uuid4()); self.request('prepare', transaction=tx, **change); self.request('commit', transaction=tx); self.request('finalize', transaction=tx)
        old = self.request('read'); plan = self.request('plan', revision=old['revision'], action=dict(operation='restore', backupId=tx))
        self.assertNotIn('ORIGINAL_PRIVATE_VALUE', json.dumps(plan))
        restore = str(uuid.uuid4()); self.request('prepare', revision=old['revision'], transaction=restore, **plan); self.request('commit', transaction=restore); self.request('finalize', transaction=restore)
        self.assertIn('ORIGINAL_PRIVATE_VALUE', self.main.read_text())
        self.assertNotIn('NEW_PRIVATE_VALUE', self.main.read_text())

    def test_local_control_channel_cannot_be_removed(self):
        self.main.write_text(self.main.read_text() + 'controls { inet 127.0.0.1 port 953 allow { localhost; } keys { "application"; }; };\n')
        old = self.request('read')
        files = [dict(path=str(self.main), content=old['files'][0]['content'].split('controls')[0])]
        with self.assertRaises(module.Failure): self.request('validate', revision=old['revision'], files=files)

    def test_include_comments_are_not_parsed_as_files(self):
        self.main.write_text('// include "/etc/shadow";\n' + self.main.read_text())
        self.assertEqual(1, len(self.request('read')['files']))
        self.request('validate', **self.edit())

    def test_database_credentials_and_pin_are_redacted(self):
        text='dlz "directory" { database "ldap HOST user password"; }; keystore "hsm" { pkcs11-uri "pkcs11:token=signer;pin-value=12345"; };'
        masked, _ = module.mask(text, 'test')
        self.assertNotIn('password', masked); self.assertNotIn('12345', masked)
        self.assertEqual(text, module.reveal(masked, text, 'test'))


if __name__ == '__main__': unittest.main()
