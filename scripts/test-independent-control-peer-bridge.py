#!/usr/bin/env python3
"""Test-only bridge used by IndependentControlHandoffIT. Refuses production
container names; credentials never leave the isolated container environment.
Runs the actual snapshot/import adapter against two test databases.
"""
import importlib.util,json,pathlib,subprocess,sys

config=json.load(sys.stdin)
inspection=json.loads(subprocess.check_output(['docker','inspect',config['container']]))[0]
assert inspection['Name'].startswith('/eatwhat-test-')
root=pathlib.Path(config['root']).resolve();assert root.is_dir()
spec=importlib.util.spec_from_file_location('peer_test',pathlib.Path(__file__).with_name('independent-control-peer.py'))
module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
pspec=importlib.util.spec_from_file_location('peer_test_policy',pathlib.Path(__file__).with_name('independent-control-policy.py'))
policy=importlib.util.module_from_spec(pspec);sys.modules[pspec.name]=policy;pspec.loader.exec_module(policy)
module.CONFIG=root/'operator.json'

def peer(side):
    d=config[side];obj=object.__new__(module.Peer)
    obj.c={'postgresContainer':config['container'],'database':d['database'],'postgresPort':5432,
        'clusterId':config['cluster'],'controllerId':d['id'],'peerId':config['to'if side=='from'else'from']['id'],
        'stateId':config['state'],'stateDirectory':d['stateDirectory']}
    obj.p=policy
    assert pathlib.Path(d['stateDirectory']).resolve().is_relative_to(root)
    return obj

sender,receiver=peer('from'),peer('to');packet=sender.export()
if config.get('reorder'):
    sender.sql("UPDATE eatwhat_controller.account SET credential_version=credential_version+1 WHERE id=17;")
    fresh=sender.export();receiver.install(fresh);stale=receiver.install(packet)
    version=receiver.sql("SELECT to_json(credential_version)FROM eatwhat_controller.account WHERE id=17;")
    assert stale['state']=='STALE_SNAPSHOT'and version==2
    print('{"delayedSnapshotRefused":true,"credentialVersionRetained":2}');sys.exit(0)
if config.get('forwardRequest'):
    # HTTP realm/authorization is covered by IndependentControlLocalIT; this
    # isolated bridge exercises the operator's atomic SQL/version boundary.
    sender.info=lambda:{'running':True}
    print(json.dumps(sender.forward(config['forwardRequest'])));sys.exit(0)
if config.get('corrupt'):
    packet['files']['controller-state-id']['sha256']='0'*64
    try:receiver.install(packet)
    except RuntimeError:print('{"corruptSnapshotRefused":true}');sys.exit(0)
    raise AssertionError('CORRUPT_SNAPSHOT_ACCEPTED')
result=receiver.install(packet);print(json.dumps(result))
