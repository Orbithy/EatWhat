import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('remote_release', Path(__file__).resolve().parents[1] / 'remote-release.py')
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / 'app.jar'
        self.jar.write_bytes(b'old-app')
        self.old = remote.sha_file(self.jar)
        self.new_bytes = b'new-app'
        self.new = hashlib.sha256(self.new_bytes).hexdigest()
        self.instance = str(uuid.uuid4())
        self.job = str(uuid.uuid4())
        self.config = dict(state_directory=str(self.root / 'state'), active_jar=str(self.jar), env_file=str(self.root / 'env'),
                           compose_file=str(self.root / 'compose.yml'), node_token_file=str(self.root / 'token'),
                           site_id='campus', instance_id=self.instance, control_host=False, restore_evidence_id='restore-run',
                           project='test-campus', origin='http://127.0.0.1:18081', health_timeout_seconds=5,
                           minimum_free_bytes=0, docker='/usr/bin/true')
        for filename, value in [('env', 'APP_JAR=unused'), ('token', 'x' * 50), ('compose.yml', 'services: {}')]:
            path = self.root / filename
            path.write_text(value)
            path.chmod(0o600)
        config_path = self.root / 'config.json'
        config_path.write_text(json.dumps(self.config)); config_path.chmod(0o600)
        self.agent = remote.Agent(config_path)
        self.calls = []
        self.running = self.old
        self.fail_new = False
        self.fail_rollback = False
        self.backup_fail = False
        def command(args, **kwargs):
            self.calls.append(args)
            if args[:2] == ['config', '--format']:
                return json.dumps({'services': {'app': {'volumes': [dict(type='bind', target='/app/app.jar', source=str(self.jar))]}}}).encode()
            if args[0] == 'up':
                self.running = remote.sha_file(self.jar)
                return b''
            if 'pg_dump' in ' '.join(args):
                if self.backup_fail:
                    raise RuntimeError('database unavailable')
                kwargs['output'].write(b'PGDMP-synthetic')
                return None
            if 'pg_restore' in args:
                self.assertEqual(kwargs['stdin'].read(), b'PGDMP-synthetic')
                return None
            raise AssertionError(args)
        self.agent.command = command
        self.agent.runtime_sha = lambda: self.running
        self.agent.health = lambda site, instance: None
        def wait_ready(sha, site, instance):
            if (sha == self.new and self.fail_new) or (sha == self.old and self.fail_rollback):
                raise remote.Rejected()
            self.assertEqual(self.running, sha)
        self.agent.wait_ready = wait_ready

    def operate(self, action='apply', data=None, **kwargs):
        return self.agent.operate(action, kwargs.get('job', self.job), kwargs.get('sha', self.new),
                                  kwargs.get('site', 'campus'), kwargs.get('instance', self.instance), self.old,
                                  io.BytesIO(self.new_bytes if data is None else data))

    def test_success_backs_up_recreates_only_app_and_duplicate_is_read_only(self):
        self.assertEqual(self.operate('prepare')['state'], 'PREPARED')
        self.assertFalse(any(call[0] == 'up' for call in self.calls))
        result = self.operate()
        self.assertEqual(result['state'], 'SUCCEEDED')
        self.assertEqual(self.jar.read_bytes(), self.new_bytes)
        backup = self.root / 'state' / self.job / 'database.dump'
        self.assertEqual(backup.read_bytes(), b'PGDMP-synthetic')
        self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
        self.assertEqual([c for c in self.calls if c[0] == 'up'], [['up', '-d', '--no-deps', '--force-recreate', 'app']])
        before = len(self.calls)
        self.assertEqual(self.operate()['state'], 'SUCCEEDED')
        self.assertEqual(len(self.calls), before)
        self.assertEqual(self.operate('status')['state'], 'SUCCEEDED')

    def test_failed_new_health_rolls_back_application_without_restoring_database(self):
        self.fail_new = True
        result = self.operate()
        self.assertEqual(result['state'], 'ROLLED_BACK')
        self.assertEqual(self.jar.read_bytes(), b'old-app')
        self.assertEqual(sum(c[0] == 'up' for c in self.calls), 2)
        self.assertFalse(any('pg_restore' in c and '--list' not in c for c in self.calls))

    def test_failed_rollback_is_not_reported_success(self):
        self.fail_new = self.fail_rollback = True
        self.assertEqual(self.operate()['state'], 'ROLLBACK_FAILED')

    def test_upload_hash_mismatch_and_backup_failure_never_replace_app(self):
        self.assertEqual(self.operate(data=b'tampered')['state'], 'FAILED')
        self.assertEqual(self.jar.read_bytes(), b'old-app')
        self.assertFalse(any(c[0] == 'up' for c in self.calls))
        self.job = str(uuid.uuid4()); self.backup_fail = True
        self.assertEqual(self.operate()['state'], 'FAILED')
        self.assertEqual(self.jar.read_bytes(), b'old-app')

    def test_mismatched_identity_or_running_version_rejected_before_backup(self):
        with self.assertRaises(remote.Rejected):
            self.operate(site='external')
        self.running = '0' * 64
        with self.assertRaises(remote.Rejected):
            self.operate()
        self.assertFalse(any('pg_dump' in ' '.join(c) for c in self.calls))

    def test_interrupted_journal_fences_new_job_and_can_be_inspected(self):
        receipt = dict(id=self.job, siteId='campus', instanceId=self.instance, artifactSha256=self.new, fromSha256=self.old, state='DEPLOYING')
        remote.atomic_json(self.agent.state / (self.job + '.json'), receipt)
        self.assertEqual(self.operate('status')['state'], 'DEPLOYING')
        with self.assertRaises(remote.Rejected):
            self.operate()
        with self.assertRaises(remote.Rejected):
            self.operate(job=str(uuid.uuid4()))

    def test_duplicate_job_with_changed_artifact_is_rejected(self):
        self.operate()
        with self.assertRaises(remote.Rejected):
            self.operate(sha='0' * 64)

    def test_unsafe_config_permissions_and_control_host_are_rejected(self):
        config_path = self.root / 'config.json'
        config_path.chmod(0o644)
        with self.assertRaises(remote.Rejected):
            remote.Agent(config_path)
        config_path.chmod(0o600)
        self.config['control_host'] = True
        config_path.write_text(json.dumps(self.config))
        with self.assertRaises(remote.Rejected):
            remote.Agent(config_path)

    def test_http_identity_uses_private_token_and_does_not_follow_redirects(self):
        from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
        import threading
        received = []
        instance = self.instance
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                received.append((self.path, self.headers.get('X-Control-Node-Token')))
                if self.path == '/redirect':
                    self.send_response(302)
                    self.send_header('Location', '/must-not-follow')
                    self.end_headers()
                    return
                if self.path == '/internal/control/v1/snapshot':
                    if self.headers.get('X-Control-Node-Token') != 'x' * 50:
                        self.send_error(401)
                        return
                    value = dict(siteId='campus', instanceId=instance, observationStatus='OBSERVED')
                else:
                    value = dict(status='UP')
                data = json.dumps(value).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
        try:
            self.agent.c['origin'] = 'http://127.0.0.1:' + str(server.server_port)
            remote.Agent.health(self.agent, 'campus', self.instance)
            self.assertEqual(received[-1][1], 'x' * 50)
            with self.assertRaises(Exception):
                self.agent.request('/redirect', True)
            self.assertFalse(any(path == '/must-not-follow' for path, token in received))
        finally:
            server.shutdown();server.server_close();thread.join()

    def test_real_command_timeout_and_environment_are_fixed(self):
        agent = remote.Agent(self.root / 'config.json')
        with patch.object(remote.subprocess, 'run') as run:
            run.return_value.stdout = b'{}'
            agent.command(['config', '--quiet'])
            args, kwargs = run.call_args
            self.assertIn('--env-file', args[0])
            self.assertEqual(kwargs['env']['APP_JAR'], str(self.jar))
            self.assertNotIn('PGPASSWORD', kwargs['env'])
            self.assertEqual(kwargs['stderr'], remote.subprocess.DEVNULL)
            self.assertEqual(kwargs['timeout'], 180)
            self.assertTrue(kwargs['check'])


if __name__ == '__main__':
    unittest.main()
