#!/usr/bin/env python3
"""Linux filesystem acceptance only: run in a disposable, networkless container.

Uses synthetic unit-fixture files, then removes ALL fixture guard adaptations.
No Docker socket, SSH credentials, database, runtime observations or RPC mocks.
"""
import base64, importlib.util, json, os, shutil, sys
from pathlib import Path

REPO=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('release_fixture',REPO/'scripts/test-cluster-release-core.py');fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
def require(value):
    if not value:raise AssertionError('Linux filesystem acceptance failed')
require(sys.platform=='linux' and os.geteuid()==0)
case=fixture.CoreChecks();case.setUp();core=fixture.core
try:
    for adaptation in reversed(case.patches):adaptation.stop()
    require(core.ROOT_UID==0 and core.APP_UID==10001 and core.APP_GID==10001)
    root=core.BASE/case.header['serverId'];shutil.copytree(case.root,root)
    old=str(case.root)
    for path in root.rglob('*'):
        if path.is_file():path.write_bytes(path.read_bytes().replace(old.encode(),str(root).encode()))
    compose=json.loads((root/'deploy/compose.json').read_text());compose['services']['app']['user']='10001:10001';(root/'deploy/compose.json').write_text(json.dumps(compose))
    os.chown(root/'config',10001,10001);os.chown(root/'app-state',10001,10001)
    for name in ['application.properties','control.token']:os.chown(root/'config'/name,10001,10001)
    core.RELEASE_SOURCE=base64.b64encode((REPO/'deploy/remote-release.py').read_bytes()).decode()
    before={str(p.relative_to(root)):(p.read_bytes(),p.stat().st_mtime_ns) for p in root.rglob('*') if p.is_file()}
    context=core.ReleaseContext(case.header);require(core.digest(context.agent.jar)==case.header['fromSha256']);require(not (root/'private/deployments').exists())
    require(before=={str(p.relative_to(root)):(p.read_bytes(),p.stat().st_mtime_ns) for p in root.rglob('*') if p.is_file()})
    def rejected(action,code):
        try:action()
        except core.ReleaseRejected as failure:require(failure.code==code);return
        raise AssertionError('Unsafe filesystem was accepted')
    with context.project_lock():rejected(lambda:context.project_lock().__enter__(),'RELEASE_PROJECT_BUSY')
    base_mode=core.BASE.stat().st_mode&0o777;core.BASE.chmod(0o777)
    try:rejected(lambda:core.ReleaseContext(case.header),'RELEASE_UNSAFE_DIRECTORY')
    finally:core.BASE.chmod(base_mode)
    token=root/'config/control.token';token.chmod(0o644)
    try:rejected(lambda:core.ReleaseContext(case.header),'RELEASE_PRIVATE_FILE_REQUIRED')
    finally:token.chmod(0o600)
    os.chown(token,10002,10002)
    try:rejected(lambda:core.ReleaseContext(case.header),'RELEASE_PRIVATE_FILE_REQUIRED')
    finally:os.chown(token,10001,10001)
    token.rename(token.with_suffix('.saved'));token.symlink_to(root/'releases/current.jar')
    try:rejected(lambda:core.ReleaseContext(case.header),'RELEASE_UNSAFE_PATH')
    finally:token.unlink();token.with_suffix('.saved').rename(token)
    props=root/'config/application.properties';props.write_bytes(props.read_bytes()+b'app.fixture.changed=true\n')
    require(core.ReleaseContext(case.header).configuration_proof!=context.configuration_proof)
    print(json.dumps({'status':'PASSED','checks':8,'scope':'ACTUAL_LINUX_ROOT_APP_OWNERSHIP_ANCESTORS_PRIVATE_FILES_SYMLINKS_FLOCK_READ_ONLY_AND_CONFIG_PROOF','ssh':False,'compose':False,'runtimeObservations':False,'network':False}))
finally:case.tearDown()
