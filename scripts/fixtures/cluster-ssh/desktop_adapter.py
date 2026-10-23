"""Acceptance-only filesystem adapter. Never imported by a production artifact.

All business/container/backup/member/authority checks remain the real checks.
Linux root/10001 permissions and trusted ancestors have separate Linux coverage.
"""
import inspect,json,os,stat,sys,traceback
from pathlib import Path

def adapt(namespace):
    config=json.loads(Path('/fixture-input/desktop-adapter.json').read_text())
    if namespace.get('PROTOCOL')=='eatwhat-member-configuration-recovery-v1':
        from member_adapter import adapt_member
        adapt_member(namespace['member']().__dict__,config);return
    if namespace.get('PROTOCOL')=='eatwhat-member-configure-v1':
        from member_adapter import adapt_member
        adapt_member(namespace,config);return
    base=Path(config['base']);uid=config['uid'];gid=config['gid']
    assert base.is_absolute() and base.name.startswith('eatwhat-first-install-compose-')
    assert type(uid) is int and uid>0 and type(gid) is int and gid>0
    core=namespace['core'];core.BASE=base;core.APP_UID=uid;core.APP_GID=gid
    canonical=Path(config['canonicalHostBase'])
    assert canonical.is_absolute() and canonical.name==base.name
    def same_source(observed,expected):
        if observed==expected:return True
        # Docker Desktop can report the VM /host_mnt alias after recreation.
        # Accept only this fixture's pre-proven canonical host directory; keep
        # the observed JSON untouched and every destination/RW/volume check.
        try:relative=Path(expected).relative_to(base)
        except (TypeError,ValueError):return False
        return observed=='/host_mnt'+str(canonical/relative)
    namespace['bind_source_matches']=same_source
    folder=core.folder;private=core.private
    core.folder=lambda path,owners,mode=0o700:folder(path,set(owners)|{uid},mode)
    core.private=lambda path,limit=262144,owners=None:private(path,limit,set(owners or {0})|{uid})
    def ancestors(root):
        for parent in root.parents:
            info=core.safe(parent).stat()
            core.require(info.st_uid in {0,uid},'RELEASE_UNSAFE_DIRECTORY')
            sticky_tmp=parent in {Path('/tmp'),Path('/private/tmp')} and info.st_mode&stat.S_ISVTX
            core.require(sticky_tmp or not stat.S_IMODE(info.st_mode)&0o022,'RELEASE_UNSAFE_DIRECTORY')
    core.trusted_ancestors=ancestors
    original_require=core.require
    def checked(value,code):
        if not value and code=='RELEASE_CONTAINER_MOUNT_CHANGED':
            frame=inspect.currentframe().f_back
            local=frame.f_locals;mount=local.get('mount',{})
            diagnostic={'line':frame.f_lineno,'service':local.get('service'),'destination':local.get('destination'),'expectedSource':local.get('source'),'actualMount':{k:mount.get(k) for k in ['Type','Source','Destination','RW','Name']},'matchedMounts':[{k:m.get(k) for k in ['Type','Source','Destination','RW','Name']} for m in local.get('match',[])]}
            fd=os.open('/run/eatwhat-ssh/mount-diagnostics.jsonl',os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
            with os.fdopen(fd,'w') as out:out.write(json.dumps(diagnostic)+'\n')
        original_require(value,code)
    core.require=checked
    original_prepare=namespace['Runner'].prepare
    def prepare_with_diagnostic(self):
        try:return original_prepare(self)
        except Exception as failure:
            # Isolated fixture only: retain locations/types, never values,
            # request headers, exception text, credentials or response bodies.
            frames=traceback.extract_tb(failure.__traceback__)
            value={'errorType':type(failure).__name__,'frames':[{'file':Path(f.filename).name,'function':f.name,'line':f.lineno} for f in frames]}
            fd=os.open('/run/eatwhat-ssh/prepare-diagnostics.jsonl',os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
            with os.fdopen(fd,'w') as out:out.write(json.dumps(value)+'\n')
            raise
    namespace['Runner'].prepare=prepare_with_diagnostic
    original_worker=namespace['Runner'].worker
    def worker_with_diagnostic(self,context,fd):
        previous=sys.gettrace()
        def observe(frame,event,arg):
            if event=='exception' and frame.f_code is original_worker.__code__:
                kind,failure,tb=arg
                value={'errorType':kind.__name__,'frames':[{'file':Path(f.filename).name,'function':f.name,'line':f.lineno} for f in traceback.extract_tb(tb)]}
                diagnostic_fd=os.open('/run/eatwhat-ssh/worker-diagnostics.jsonl',os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
                with os.fdopen(diagnostic_fd,'w') as out:out.write(json.dumps(value)+'\n')
            return observe
        sys.settrace(observe)
        try:return original_worker(self,context,fd)
        finally:sys.settrace(previous)
    namespace['Runner'].worker=worker_with_diagnostic
