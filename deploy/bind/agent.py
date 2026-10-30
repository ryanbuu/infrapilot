#!/usr/bin/env python3
"""BIND administration through a forced SSH command, never an interactive shell.

Root-owned configuration fixes the nodes, paths, service, signing keys and views.
All configuration writes are native-validated, revision-checked transactions.
Raw secrets and rollback copies stay on the DNS host, outside the HTTP service.
"""
import base64
import contextlib
import fcntl
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

_library = Path('/usr/local/libexec/infrapilot-forwarding/agent.py')
if not _library.exists():
    _library = Path(__file__).resolve().parents[1] / 'forwarding/agent.py'
_spec = importlib.util.spec_from_file_location('forwarding_syntax', _library)
_syntax = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_syntax)
Failure, atomic, digest, statements = _syntax.Failure, _syntax.atomic, _syntax.digest, _syntax.statements

MAX_INPUT = 4 * 1024 * 1024
MAX_FILE = 512 * 1024
SECRET = re.compile(r'\b(secret|password|pin|pkcs11-uri|database)\s+("(?:\\.|[^"\\])*"|[^;\s]+)\s*;', re.I)
PLACEHOLDER = re.compile(r'__INFRAPILOT_SECRET_[a-f0-9]{32}__')
INCLUDE = re.compile(r'\binclude\s+"([^"\n]+)"\s*;')
BLOCKS = {
    'options': 'resolver', 'acl': 'access', 'view': 'views', 'zone': 'zones',
    'dnssec-policy': 'dnssec', 'trust-anchors': 'dnssec', 'managed-keys': 'dnssec',
    'key': 'keys', 'tls': 'transport', 'http': 'transport', 'logging': 'logging',
    'statistics-channels': 'logging', 'controls': 'access', 'server': 'resolver',
    'primaries': 'zones', 'masters': 'zones', 'dyndb': 'advanced', 'dlz': 'advanced',
    'keystore': 'dnssec', 'plugin': 'advanced', 'parental-agents': 'dnssec',
    'remote-servers': 'zones', 'include': 'advanced',
}


def domain(value, root=False):
    try:
        text = value.strip().rstrip('.').encode('idna').decode().lower()
    except (AttributeError, UnicodeError):
        raise Failure('域名格式无效', 400)
    if not text and root:
        return '.'
    if not text or len(text) > 253 or any(not re.fullmatch(r'[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?', p) for p in text.split('.')):
        raise Failure('域名格式无效', 400)
    return text


def quoted(value):
    return json.dumps(value, ensure_ascii=True)


def mask(source, identity):
    mapping = {}
    def replace(match):
        token = '__INFRAPILOT_SECRET_' + hashlib.sha256((identity + '\0' + match[2]).encode()).hexdigest()[:32] + '__'
        mapping[token] = match[2]
        return match[1] + ' "' + token + '";'
    return SECRET.sub(replace, source), mapping


def reveal(source, original, identity):
    _, values = mask(original, identity)
    for token in PLACEHOLDER.findall(source):
        if token not in values:
            raise Failure('密钥占位符已失效，请重新加载配置', 409)
        source = source.replace('"' + token + '"', values[token])
    if '__INFRAPILOT_SECRET_' in source:
        raise Failure('密钥占位符无效', 400)
    return source


def includes(source):
    result = []
    def visit(tree):
        for s in tree:
            if s['words'] and s['words'][0] == 'include':
                if len(s['words']) != 2:
                    raise Failure('include 语法无效', 400)
                result.append((s['start'], s['end'], s['words'][1]))
            visit(s['children'] or [])
    visit(statements(source))
    return result


def plain(words):
    return ' '.join(words)


def local_controls(source):
    def shape(s):
        return dict(words=s['words'], suffix=s['suffix'], children=[shape(c) for c in s['children'] or []])
    return [shape(c) for s in statements(source) if s['words'] == ['controls']
            for c in s['children'] or [] if c['words'][:2] in (['inet', '127.0.0.1'], ['inet', '::1'])]


class Agent:
    def __init__(self, config='/etc/infrapilot-bind.json', runner=None):
        self.config = json.loads(Path(config).read_text())
        self.main = Path(self.config['main'])
        self.root = self.main.parent
        self.state = Path(self.config['stateDirectory'])
        self.managed = Path(self.config['managedDirectory'])
        self.assets = Path(self.config['zoneDirectory'])
        self.forward = Path(self.config['forwardingState'])
        self.runner = runner or self.run
        self.gid = self.root.stat().st_gid

    def run(self, args, optional=False):
        # Native validation may load configured plugins. Never load them as root.
        if args[0] in ('named-checkconf', 'named-checkzone', 'named-compilezone'):
            args = ['runuser', '-u', 'bind', '--'] + args
        try:
            p = subprocess.run(args, capture_output=True, text=True, timeout=35,
                               env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C'})
        except subprocess.TimeoutExpired:
            raise Failure('BIND 命令超时，需检查节点状态', 502)
        if p.returncode and not optional:
            # Raw checker output can contain secrets or attacker-controlled config fragments.
            line = re.search(r':(\d+):', p.stdout + p.stderr)
            raise Failure('BIND 校验或运行命令失败' + ('，行号 ' + line[1] if line else '') + '，请检查节点日志', 400)
        output = p.stdout + (p.stderr if optional else '')
        if len(output.encode()) > 4 * 1024 * 1024:
            raise Failure('命令输出超过 4 MiB，请在节点上导出完整数据', 400)
        return output

    def active(self):
        for directory, module in ((self.state, 'BIND'), (self.forward, '条件转发')):
            p = directory / 'active.json'
            if p.exists():
                return {**json.loads(p.read_text()), 'module': module}
        return None

    def files(self):
        files, visiting = {}, set()
        def visit(path):
            path = Path(path)
            if path in visiting:
                raise Failure('配置 include 循环', 409)
            if path in files:
                return
            if not path.is_absolute() or '..' in path.parts or not path.is_relative_to(self.root):
                raise Failure('include 超出 BIND 配置目录', 409)
            if not path.is_file() or path.stat().st_size > MAX_FILE:
                raise Failure('配置文件不存在或过大', 409)
            text = path.read_text()
            files[path] = text
            visiting.add(path)
            for _, _, target in includes(text):
                visit(Path(target))
            visiting.remove(path)
        visit(self.main)
        return files

    def editable(self, path):
        # Conditional forwarding uses its own generation protocol and API.
        return not path.is_relative_to(self.root / 'infrapilot-forwarding')

    def inventory(self, files):
        result = []
        for path, raw in files.items():
            text, _ = mask(raw, str(path))
            def visit(tree, view='_default'):
                for s in tree:
                    words = s['words']
                    if not words:
                        continue
                    kind, name = words[0], words[1] if len(words) > 1 else words[0]
                    if kind in BLOCKS:
                        body = text[s['start']:s['end']]
                        fields = {c['words'][0]: plain(c['words'][1:]) for c in s['children'] or [] if c['words']}
                        result.append(dict(id=digest([str(path), view, kind, name])[:24], kind=kind, name=name,
                                           view=view, file=str(path), category=BLOCKS[kind], source=body,
                                           start=s['start'], end=s['end'], fields=fields,
                                           editable=self.editable(path)))
                    if kind == 'view':
                        visit(s['children'] or [], name)
            visit(statements(text))
        return result

    def snapshot(self):
        files = self.files()
        effective = self.runner(['named-checkconf', '-p', '-x', str(self.main)])
        revision = digest({str(p): hashlib.sha256(v.encode()).hexdigest() for p, v in files.items()})
        return dict(node=self.config['id'], revision=revision, pending=self.active(),
                    files=[dict(path=str(p), content=mask(text, str(p))[0], editable=self.editable(p)) for p, text in files.items()],
                    objects=self.inventory(files), effective=effective,
                    managedDirectory=str(self.managed), zoneDirectory=str(self.assets),
                    views=self.config['views'], primaryView=self.config['primaryView'],
                    address=self.config['address'], protectedZones=self.config.get('protectedZones', []),
                    status=self.runner(['rndc', 'status']),
                    version=self.runner(['named', '-V']).splitlines()[:12],
                    commands=command_catalog())

    def candidate(self, request, old):
        changes = request.get('files', [])
        assets = request.get('assets', [])
        if not isinstance(changes, list) or len(changes) > 40 or not isinstance(assets, list) or len(assets) > 20:
            raise Failure('文件数量无效', 400)
        originals = self.files()
        after, before, seen = {}, {}, set()
        for item in changes:
            p, text = Path(item.get('path', '')), item.get('content')
            if p in seen or not self.editable(p) or not (p in originals or (p.parent == self.managed and re.fullmatch(r'[a-zA-Z0-9_-]{1,64}\.conf', p.name))):
                raise Failure('不能修改此配置路径', 400)
            if not isinstance(text, str) or len(text.encode()) > MAX_FILE or '\0' in text:
                raise Failure('文件内容无效或过大', 400)
            seen.add(p)
            original = originals.get(p, '')
            if request.get('restoreFrom'):
                backup = self.record(request['restoreFrom'])
                if backup['status'] != 'complete' or str(p) not in backup['before']:
                    raise Failure('恢复备份与目标文件不一致', 409)
                _, old_values = mask(backup['before'][str(p)] or '', str(p))
                for token in PLACEHOLDER.findall(text):
                    if token in old_values:
                        text = text.replace('"' + token + '"', old_values[token])
            text = reveal(text, original, str(p))
            if p.is_symlink():
                raise Failure('不允许通过符号链接修改配置', 400)
            # Prevent root's checker from reading arbitrary paths via include.
            for _, _, target in includes(text):
                included = Path(target)
                if included not in originals and not (included.parent == self.managed and re.fullmatch(r'[a-zA-Z0-9_-]{1,64}\.conf', included.name)):
                    raise Failure('include 必须引用已登记文件或管理目录中的配置', 400)
            if text != original:
                before[str(p)] = original if p in originals else None
                after[str(p)] = text
        for item in assets:
            p, text = Path(item.get('path', '')), item.get('content')
            if p.parent != self.assets or not re.fullmatch(r'[a-f0-9]{32}\.zone', p.name) or p.exists() or p in seen:
                raise Failure('区域数据仅能创建于专用目录，已有动态数据须使用记录编辑', 400)
            if not isinstance(text, str) or len(text.encode()) > MAX_FILE or '$INCLUDE' in text.upper() or '$GENERATE' in text.upper():
                raise Failure('区域数据无效，不允许外部 include 或 generate', 400)
            name = domain(item.get('zone'), root=True)
            before[str(p)], after[str(p)] = None, text
            seen.add(p)
        return before, after

    def validate(self, before, after):
        files = {str(p): text for p, text in self.files().items()}
        files.update(after)
        self.managed.mkdir(mode=0o750, parents=True, exist_ok=True)
        self.managed.chmod(0o750)
        os.chown(self.managed, -1, self.gid)
        with tempfile.TemporaryDirectory(prefix='.validate-', dir=self.managed) as directory:
            stage = Path(directory)
            stage.chmod(0o750)
            os.chown(stage, -1, self.gid)
            mapping = {p: stage / (str(i) + '.conf') for i, p in enumerate(files)}
            for p, text in files.items():
                checked = text
                # Zone master files use a different grammar and are checked below.
                for start, end, name in sorted(includes(text) if not Path(p).is_relative_to(self.assets) else [], reverse=True):
                    target = mapping.get(name)
                    if target is None:
                        raise Failure('include 引用了未登记的配置文件', 400)
                    checked = checked[:start] + 'include ' + quoted(str(target)) + ';' + checked[end:]
                atomic(mapping[p], checked, 0o640, self.gid)
                # Parse first to prevent malformed blocks from reaching the runtime.
                if p not in after or not Path(p).is_relative_to(self.assets):
                    statements(text)
            self.runner(['named-checkconf', str(mapping[str(self.main)])])
            # Keep the local management channel reachable throughout commit and
            # rollback. Additional remote channels can still be configured.
            before_controls = local_controls(self.runner(['named-checkconf', '-p', '-x', str(self.main)]))
            after_controls = local_controls(self.runner(['named-checkconf', '-p', '-x', str(mapping[str(self.main)])]))
            if any(c not in after_controls for c in before_controls):
                raise Failure('本地 RNDC 管理通道须保留，可新增其他控制通道', 400)
            for p, text in after.items():
                if Path(p).is_relative_to(self.assets):
                    origin = re.search(r'^\$ORIGIN\s+(\S+)', text, re.M | re.I)
                    if not origin:
                        raise Failure('区域文件必须包含 $ORIGIN', 400)
                    self.runner(['named-checkzone', '-i', 'local', origin[1], str(mapping[p])])

    def plan(self, request, old):
        action = request.get('action', {})
        kind, operation = action.get('kind'), action.get('operation')
        files = {f['path']: f['content'] for f in old['files'] if f['editable']}
        updates, assets = {}, []
        if operation == 'zone-create':
            name = domain(action.get('name'))
            if any(o['kind'] == 'zone' and o['name'].rstrip('.').lower() == name for o in old['objects']):
                raise Failure('区域已存在', 409)
            ttl = action.get('ttl', 300)
            if type(ttl) is not int or not 30 <= ttl <= 2147483647:
                raise Failure('TTL 范围为 30–2147483647', 400)
            ns1, ns2 = domain(action.get('ns1')), domain(action.get('ns2'))
            nskey = quoted(self.config['applicationKey'])
            path = self.assets / (hashlib.sha256(name.encode()).hexdigest()[:32] + '.zone')
            if self.config['role'] == 'primary':
                zone = (f'zone {quoted(name)} {{ type primary; file {quoted(str(path))}; '
                        f'allow-transfer {{ key {quoted(self.config["transferKey"])}; key {quoted(self.config["localTransferKey"])}; key {nskey}; }}; '
                        f'also-notify {{ {self.config["peerAddress"]} key {quoted(self.config["transferKey"])}; }}; '
                        f'update-policy {{ grant {nskey} zonesub A AAAA CNAME MX TXT PTR SRV CAA TLSA SSHFP NAPTR URI SVCB HTTPS DNAME LOC HINFO RP CERT IPSECKEY OPENPGPKEY CSYNC ZONEMD EUI48 EUI64 TYPE65280; }}; }};')
                origin = name + '.'
                serial = int(time.strftime('%Y%m%d01', time.gmtime()))
                source = (f'$ORIGIN {origin}\n$TTL {ttl}\n@ IN SOA {ns1}. hostmaster.{origin} ( {serial} 3600 600 1209600 300 )\n'
                          f'@ IN NS {ns1}.\n@ IN NS {ns2}.\n')
                if action.get('zoneFile'):
                    source = '$ORIGIN ' + origin + '\n' + action['zoneFile']
                if not path.exists():
                    assets.append(dict(path=str(path), content=source, zone=name))
                reader = (f'zone {quoted(name)} {{ type secondary; file {quoted(str(self.assets / (path.stem + "-reader.zone")))}; '
                          f'primaries {{ {self.config["address"]} key {quoted(self.config["localTransferKey"])}; }}; '
                          f'allow-transfer {{ key {nskey}; }}; }};')
                for view in self.config['views']:
                    self.append_scope(files, updates, view, zone if view == self.config['primaryView'] else reader)
            else:
                zone = (f'zone {quoted(name)} {{ type secondary; file {quoted(str(path))}; '
                        f'primaries {{ {self.config["peerAddress"]} key {quoted(self.config["transferKey"])}; }}; allow-transfer {{ key {nskey}; }}; }};')
                self.append_scope(files, updates, '_default', zone)
        elif operation == 'zone-delete':
            name = domain(action.get('name'))
            if name in self.config.get('protectedZones', []):
                raise Failure('基础区域受保护，请通过原生配置审核变更', 400)
            objects = [o for o in old['objects'] if o['kind'] == 'zone' and o['name'].rstrip('.').lower() == name]
            if not objects or any(not o['editable'] for o in objects):
                raise Failure('区域不存在或由其他模块管理', 409)
            for p in {o['file'] for o in objects}:
                text = files[p]
                for o in sorted([o for o in objects if o['file'] == p], key=lambda x: x['start'], reverse=True):
                    text = text[:o['start']] + text[o['end']:]
                updates[p] = text
        elif operation == 'restore':
            backup = self.record(action.get('backupId'))
            if backup['status'] != 'complete':
                raise Failure('仅可恢复已完成的备份', 409)
            for p, text in backup['before'].items():
                # Zone files are live data; configuration rollback never overwrites leases/DDNS.
                if Path(p).is_relative_to(self.assets):
                    continue
                updates[p] = mask(text or '', p)[0]
        elif operation in ('create', 'update', 'delete'):
            if operation == 'create':
                source = action.get('source', '')
                if not isinstance(source, str) or not source.strip():
                    raise Failure('请提供配置块', 400)
                parsed = statements(source)
                if len(parsed) != 1 or parsed[0]['words'][0] not in BLOCKS:
                    raise Failure('每次新增一个完整配置块', 400)
                self.append_scope(files, updates, action.get('view', '_default'), source)
            else:
                obj = next((o for o in old['objects'] if o['id'] == action.get('id')), None)
                if not obj or not obj['editable']:
                    raise Failure('配置对象已变化或由条件转发管理', 409)
                text = files[obj['file']]
                source = '' if operation == 'delete' else action.get('source', '')
                if not isinstance(source, str):
                    raise Failure('配置内容无效', 400)
                if source:
                    parsed = statements(source)
                    if len(parsed) != 1 or parsed[0]['words'][0] != obj['kind']:
                        raise Failure('配置块类型必须保持一致', 400)
                updates[obj['file']] = text[:obj['start']] + source + text[obj['end']:]
        elif operation == 'key-generate':
            # Generation is a preview returning a NEW key only. Existing secrets stay masked.
            name = domain(action.get('name'))
            algorithm = action.get('algorithm', 'hmac-sha256')
            sizes = {'hmac-sha256': 32, 'hmac-sha384': 48, 'hmac-sha512': 64}
            if algorithm not in sizes:
                raise Failure('请选择 SHA-256、SHA-384 或 SHA-512', 400)
            return dict(source=f'key {quoted(name)} {{ algorithm {algorithm}; secret {quoted(base64.b64encode(secrets.token_bytes(sizes[algorithm])).decode())}; }};')
        else:
            raise Failure('不支持的配置操作', 400)
        result = dict(files=[dict(path=p, content=text) for p, text in updates.items()], assets=assets)
        if operation == 'restore':
            result['restoreFrom'] = action.get('backupId')
        return result

    def append_scope(self, files, updates, view, source):
        if view == '_default':
            updates[str(self.main)] = updates.get(str(self.main), files[str(self.main)]) + '\n' + source + '\n'
            return
        for p, original in files.items():
            text = updates.get(p, original)
            for s in statements(text):
                if s['words'][:2] == ['view', view]:
                    close = text.rfind('}', s['start'], s['end'])
                    updates[p] = text[:close] + '\n' + source + '\n' + text[close:]
                    return
        raise Failure('目标 View 不存在', 400)

    def record(self, tx):
        if not isinstance(tx, str) or not re.fullmatch(r'[a-f0-9-]{36}', tx):
            raise Failure('事务标识无效', 400)
        path = self.state / (tx + '.json')
        if not path.exists():
            raise Failure('事务不存在', 404)
        return json.loads(path.read_text())

    def save(self, record):
        atomic(self.state / (record['id'] + '.json'), json.dumps(record, ensure_ascii=False))

    def write_files(self, content):
        # Includes and assets must precede named.conf, both during commit and rollback.
        for p, text in sorted(content.items(), key=lambda x: x[0] == str(self.main)):
            path = Path(p)
            if text is None:
                path.unlink(missing_ok=True)
                continue
            path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
            if path.is_relative_to(self.assets):
                path.parent.chmod(0o770)
                os.chown(path.parent, -1, self.gid)
                atomic(path, text, 0o660, self.gid)
            else:
                atomic(path, text, 0o640, self.gid)

    def assert_unchanged(self, record, applied=False):
        for p, expected in record.get('graph', {}).items():
            if p in record['after']:
                continue
            path = Path(p)
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise Failure('其他 include 配置已被外部修改，请人工核实事务', 409)
        for p in record['after']:
            path = Path(p)
            actual = path.read_text() if path.exists() else None
            if actual not in ((record['after'].get(p),) if applied else (record['before'].get(p), record['after'].get(p))):
                # Zone files change normally through IXFR/DDNS: never roll them back.
                if Path(p).is_relative_to(self.assets):
                    continue
                raise Failure('配置被外部修改，拒绝覆盖，请人工核实事务', 409)

    def health(self):
        for zone in self.config.get('healthZones', []):
            answer = self.runner(['dig', '@' + self.config['address'], '+time=2', '+tries=1', zone, 'SOA'])
            if 'status: NOERROR' not in answer or not re.search(r'\bIN\s+SOA\s', answer):
                raise Failure('基础区域未通过解析检查，需回滚配置', 502)

    def handle(self, request):
        op = request.get('operation')
        if op == 'read':
            return self.snapshot()
        if op == 'telemetry':
            return self.telemetry()
        if op == 'command':
            if self.active():
                raise Failure('配置事务进行中，暂停运行命令', 409)
            return self.command(request)
        if op in ('plan', 'validate', 'prepare'):
            old = self.snapshot()
            tx = request.get('transaction')
            if op == 'prepare' and tx and (self.state / (tx + '.json')).exists():
                r = self.record(tx)
                if r['revision'] != request.get('revision') or r.get('requestHash') != digest(request):
                    raise Failure('事务标识已使用', 409)
                return dict(status=r['status'])
            if old['pending'] or old['revision'] != request.get('revision'):
                raise Failure('配置版本已变化或存在待完成事务，请刷新', 409)
            if op == 'plan':
                return self.plan(request, old)
            before, after = self.candidate(request, old)
            self.validate(before, after)
            response = dict(valid=True, before=[dict(path=p, content=mask(v or '', p)[0]) for p, v in before.items()],
                            after=[dict(path=p, content=mask(v, p)[0]) for p, v in after.items()], digest=digest(after))
            if op == 'validate':
                return response
            if not isinstance(tx, str) or not re.fullmatch(r'[a-f0-9-]{36}', tx):
                raise Failure('事务标识无效', 400)
            record = dict(id=tx, revision=old['revision'], before=before, after=after, status='prepared', created=time.time())
            record['graph'] = {str(p): hashlib.sha256(text.encode()).hexdigest() for p, text in self.files().items()}
            record['requestHash'] = digest(request)
            self.save(record)
            atomic(self.state / 'active.json', json.dumps(dict(id=tx, status='prepared')))
            return dict(status='prepared', digest=digest(after))
        tx = request.get('transaction')
        try:
            record = self.record(tx)
        except Failure as e:
            if op == 'abort' and e.code == 404:
                return dict(status='not-prepared')
            raise
        if op == 'status':
            return dict(status=record['status'], digest=digest(record['after']))
        active = self.active()
        if active and (active['module'] != 'BIND' or active['id'] != tx):
            raise Failure('其他事务正在运行', 409)
        if op == 'commit':
            if record['status'] in ('applied', 'complete'):
                self.assert_unchanged(record, applied=True)
                return dict(status=record['status'], digest=digest(record['after']))
            if record['status'] != 'prepared' or self.snapshot()['revision'] != record['revision']:
                raise Failure('提交前配置版本已变化', 409)
            record['status'] = 'applying'
            self.save(record)
            self.write_files(record['after'])
            self.runner(['named-checkconf', str(self.main)])
            self.runner(['rndc', 'reconfig'])
            self.runner(['rndc', 'status'])
            self.health()
            record['status'] = 'applied'
            self.save(record)
            atomic(self.state / 'active.json', json.dumps(dict(id=tx, status='applied')))
            return dict(status='applied', digest=digest(record['after']))
        if op == 'abort':
            if record['status'] == 'complete':
                raise Failure('已完成事务请创建新的恢复变更', 409)
            if record['status'] not in ('rolled-back', 'aborted'):
                self.assert_unchanged(record)
                if record['status'] != 'prepared':
                    # Keep created zone files for data recovery. Remove their config only.
                    restore = {p: text for p, text in record['before'].items() if not Path(p).is_relative_to(self.assets)}
                    self.write_files(restore)
                    self.runner(['named-checkconf', str(self.main)])
                    self.runner(['rndc', 'reconfig'])
                    self.runner(['rndc', 'status'])
                    self.health()
                record['status'] = 'rolled-back'
        elif op == 'finalize':
            if record['status'] not in ('applied', 'complete'):
                raise Failure('事务尚未提交', 409)
            self.assert_unchanged(record, applied=True)
            record['status'] = 'complete'
        else:
            raise Failure('不支持的操作', 400)
        self.save(record)
        (self.state / 'active.json').unlink(missing_ok=True)
        return dict(status=record['status'])

    def telemetry(self):
        result = dict(status=self.runner(['rndc', 'status']),
                      logs=self.runner(['journalctl', '-u', 'named', '-n', '150', '--no-pager', '-o', 'short-iso'], True))
        try:
            # Fixed loopback endpoint, no caller-controlled URL or proxy environment.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open('http://127.0.0.1:8053/json/v1/server', timeout=5) as response:
                data = response.read(1048577)
            if len(data) > 1048576:
                raise ValueError()
            result['statistics'] = json.loads(data)
        except Exception:
            result['statisticsError'] = '统计接口尚未启用或不可达'
        # Logs can quote an invalid configuration line containing a TSIG secret.
        result['logs'] = mask(result['logs'], 'journal')[0]
        return result

    def command(self, request):
        name, values = request.get('command'), request.get('arguments', {})
        spec = next((c for c in command_catalog() if c['id'] == name), None)
        if spec is None or not isinstance(values, dict):
            raise Failure('运行命令无效', 400)
        args = list(spec['args'])
        for f in spec.get('fields', []):
            value = values.get(f['name'], '')
            if f.get('optional') and value in ('', None):
                continue
            if f['type'] == 'choice':
                if value not in f['choices']:
                    raise Failure('命令选项无效', 400)
            elif f['type'] == 'integer':
                if type(value) is not int or not f.get('min', 0) <= value <= f.get('max', 2147483647):
                    raise Failure('命令数字范围无效', 400)
                value = str(value)
            elif f['type'] == 'domain':
                value = domain(value, root=True)
            elif f['type'] == 'view':
                if value not in self.config['views']:
                    raise Failure('View 不存在', 400)
            else:
                raise Failure('命令字段类型无效', 400)
            if f.get('prefix'):
                args.append(f['prefix'])
            args.append(value)
        if spec.get('zone'):
            zone = domain(values.get('zone'), root=True)
            effective = self.runner(['named-checkconf', '-p', '-x', str(self.main)])
            found = [(v, z) for v, z in _syntax.zone_statements(statements(effective)) if z['words'][1].rstrip('.').lower() == zone.rstrip('.')]
            view = values.get('view') or self.config['primaryView']
            if view not in self.config['views'] or not any(v == view for v, _ in found):
                raise Failure('区域或 View 不存在', 400)
            args.extend([zone, 'IN'])
            if view != '_default':
                args.append(view)
        if spec.get('mutating') and request.get('confirmed') is not True:
            raise Failure('此操作需要确认', 400)
        if name == 'query':
            output = self.runner(['dig', '@127.0.0.1', '+time=3', '+tries=1', '+dnssec'] + args)
        elif name == 'service-restart':
            # A single SSH command cannot restart or stop its peer. The peer must
            # answer an actual DNS query before this node can be restarted.
            answer = self.runner(['dig', '@' + self.config['peerAddress'], '+time=3', '+tries=1', '.', 'NS'])
            if 'status: NOERROR' not in answer:
                raise Failure('另一节点未通过 DNS 检查，拒绝重启', 409)
            self.runner(['systemctl', 'restart', 'named'])
            output = self.runner(['rndc', 'status'])
        elif name == 'zone-export':
            item = next(z for v, z in found if v == view)
            fields = {c['words'][0]: c['words'][1:] for c in item['children'] or []}
            path = Path((fields.get('file') or [''])[0])
            if not path.is_absolute() or '..' in path.parts or not (path.is_relative_to('/var/lib/bind') or path.is_relative_to('/var/cache/bind')):
                raise Failure('区域数据路径不在允许的目录内', 400)
            source_format = (fields.get('masterfile-format') or ['raw' if fields.get('type') in (['secondary'], ['slave']) else 'text'])[0]
            output = self.runner(['named-compilezone', '-q', '-j', '-f', source_format, '-F', 'text', '-o', '-', zone, str(path)])
        else:
            output = self.runner(['rndc'] + args)
        # Zone data is already readable through AXFR/record APIs; preserve its
        # exact presentation format rather than treating TXT data as config.
        return dict(command=name, output=(output if name in ('zone-export', 'query') else mask(output, 'rndc')[0]) or '命令已完成')


def command_catalog():
    """Typed argument schemas; there is deliberately no command-line/shell textbox."""
    commands = []
    def add(id, label, args, fields=None, zone=False, mutating=False):
        commands.append(dict(id=id, label=label, args=args.split(), fields=fields or [], zone=zone, mutating=mutating))
    choice = lambda name, choices, optional=False: dict(name=name, type='choice', choices=choices.split(), optional=optional)
    integer = lambda name, min, max: dict(name=name, type='integer', min=min, max=max)
    view = dict(name='view', type='view', optional=True)
    dom = dict(name='name', type='domain')
    add('status', '服务状态', 'status')
    add('query', 'DNS 查询与 DNSSEC 应答', '', [dom, choice('type', 'A AAAA CNAME MX TXT PTR NS SOA SRV CAA TLSA SSHFP NAPTR URI SVCB HTTPS DNSKEY DS RRSIG ANY')])
    add('service-restart', '重启服务（检查另一节点）', '', mutating=True)
    add('zone-export', '导出区域数据（含动态日志）', '', zone=True)
    add('tcp-status', 'TCP 超时状态', 'tcp-timeouts')
    add('fetchlimit', '解析限流状态', 'fetchlimit', [view])
    add('nta-list', '负信任锚列表', 'nta -dump')
    add('managed-keys-status', '信任锚状态', 'managed-keys status')
    add('serve-stale-status', '过期缓存策略状态', 'serve-stale status')
    for cmd, label in [('zonestatus', '区域状态'), ('showzone', '区域配置'), ('reload', '重载区域'), ('notify', '发送主从通知'),
                       ('refresh', '刷新从区域'), ('retransfer', '重新传送区域'), ('freeze', '暂停区域动态更新'),
                       ('thaw', '恢复区域动态更新'), ('sync', '同步区域日志'), ('sign', '触发区域签名'), ('loadkeys', '加载区域签名密钥')]:
        add('zone-' + cmd, label, cmd, zone=True, mutating=cmd not in ('zonestatus', 'showzone'))
    add('dnssec-status', 'DNSSEC 签名状态', 'dnssec -status', zone=True)
    add('dnssec-step', '执行手动 DNSSEC 策略', 'dnssec -step', zone=True, mutating=True)
    add('dnssec-rollover', 'DNSSEC 密钥轮换', 'dnssec -rollover -key', [integer('keyId', 0, 65535)], zone=True, mutating=True)
    add('dnssec-checkds', '确认父区域 DS 状态', 'dnssec -checkds', [choice('state', 'published withdrawn')], zone=True, mutating=True)
    add('signing-list', '签名任务状态', 'signing -list', zone=True)
    add('signing-clear', '清理已完成签名任务', 'signing -clear all', zone=True, mutating=True)
    add('signing-serial', '设置区域序列号', 'signing -serial', [integer('serial', 0, 4294967295)], zone=True, mutating=True)
    add('signing-nsec3', '移除 NSEC3 链', 'signing -nsec3param none', zone=True, mutating=True)
    add('flush', '清空缓存', 'flush', [view], mutating=True)
    add('flushname', '清理指定名称缓存', 'flushname', [dom, view], mutating=True)
    add('flushtree', '清理指定域名子树缓存', 'flushtree', [dom, view], mutating=True)
    add('querylog', '查询日志开关', 'querylog', [choice('state', 'on off')], mutating=True)
    add('responselog', '响应日志开关', 'responselog', [choice('state', 'on off')], mutating=True)
    add('trace', '设置调试级别', 'trace', [integer('level', 0, 99)], mutating=True)
    add('notrace', '关闭调试日志', 'notrace', mutating=True)
    add('reconfig', '重新加载配置', 'reconfig', mutating=True)
    add('reload', '重新加载配置和区域', 'reload', mutating=True)
    add('scan', '扫描网络接口', 'scan', mutating=True)
    add('stats', '写入统计快照', 'stats', mutating=True)
    add('recursing', '写入递归请求快照', 'recursing', mutating=True)
    add('secroots', '写入安全根快照', 'secroots', [view], mutating=True)
    add('dumpdb', '写入缓存快照', 'dumpdb', [choice('kind', '-all -cache -zones -adb -bad -expired -fail'), view], mutating=True)
    add('dnstap-reopen', '重新打开 dnstap 输出', 'dnstap -reopen', mutating=True)
    add('dnstap-roll', '轮转 dnstap 文件', 'dnstap -roll', [integer('count', 1, 100)], mutating=True)
    add('managed-keys-refresh', '刷新信任锚', 'managed-keys refresh', mutating=True)
    add('managed-keys-sync', '保存信任锚', 'managed-keys sync', mutating=True)
    add('serve-stale', '过期缓存应答开关', 'serve-stale', [choice('state', 'on off reset')], mutating=True)
    add('validation', 'DNSSEC 验证开关', 'validation', [choice('state', 'on off status'), view], mutating=True)
    add('nta-add', '添加临时负信任锚', 'nta -lifetime', [integer('lifetime', 1, 604800), dom, view], mutating=True)
    add('nta-remove', '移除负信任锚', 'nta -remove', [dom, view], mutating=True)
    add('tcp-timeouts', '修改 TCP 超时', 'tcp-timeouts', [integer(n, 0, 65535) for n in ['initial', 'idle', 'keepalive', 'advertised']], mutating=True)
    return commands


def main():
    os.umask(0o077)
    try:
        data = sys.stdin.buffer.read(MAX_INPUT + 1)
        if len(data) > MAX_INPUT:
            raise Failure('请求过大', 400)
        request = json.loads(data)
        if not isinstance(request, dict):
            raise Failure('请求格式无效', 400)
        agent = Agent()
        # Both adapters take the forwarding lock first. Pending markers also fence
        # writes between SSH calls, including across application/container restarts.
        with contextlib.ExitStack() as stack:
            for directory in [agent.forward, agent.state]:
                lock = stack.enter_context((directory / '.lock').open('a'))
                fcntl.flock(lock, fcntl.LOCK_EX)
            result = agent.handle(request)
        print(json.dumps(dict(ok=True, result=result), ensure_ascii=False))
    except Failure as e:
        print(json.dumps(dict(ok=False, code=e.code, message=e.message), ensure_ascii=False))
    except Exception:
        print(json.dumps(dict(ok=False, code=502, message='BIND 管理代理异常，请检查节点日志'), ensure_ascii=False))


if __name__ == '__main__':
    main()
