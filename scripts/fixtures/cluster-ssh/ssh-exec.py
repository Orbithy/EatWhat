#!/usr/local/bin/python3
"""Accept only exact reviewed-source hashes and their fixed action catalog."""
import base64,contextlib,hashlib,io,json,os,re,shlex,sys,zlib
from pathlib import Path

try:
    command=os.environ.get('SSH_ORIGINAL_COMMAND','')
    if len(command)>100000:raise ValueError()
    parts=shlex.split(command)
    if len(parts)<3 or parts[:2]!=['python3','-c']:raise ValueError()
    match=re.fullmatch(r"exec\(__import__\('zlib'\)\.decompress\(__import__\('base64'\)\.b64decode\('([A-Za-z0-9+/=]+)'\)\)\)",parts[2])
    if match is None:raise ValueError()
    decoder=zlib.decompressobj();source=decoder.decompress(base64.b64decode(match[1],validate=True),262145)
    if len(source)>262144 or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:raise ValueError()
    catalog=json.loads(Path('/fixture-input/allowed-sources.json').read_text())
    digest=hashlib.sha256(source).hexdigest()
    if digest not in catalog or parts[3:] not in catalog[digest]:raise ValueError()
    os.environ.pop('SSH_ORIGINAL_COMMAND',None)
    sys.argv=['<reviewed-fixture-agent>',*parts[3:]]
    if Path('/fixture-input/desktop-adapter.json').is_file():
        # This optional branch exists only in the disposable acceptance image.
        # Load the exact reviewed agent, then adapt host paths/UIDs for Docker
        # Desktop; never replace application, SQL, identity or authority results.
        namespace={'__name__':'__fixture_agent__'}
        exec(compile(source,'<reviewed-fixture-agent>','exec'),namespace)
        from desktop_adapter import adapt
        adapt(namespace)
        captured=io.StringIO()
        try:
            with contextlib.redirect_stdout(captured):namespace['main']()
        finally:
            reply=captured.getvalue()
            try:
                value=json.loads(reply)
                diagnostic={key:value[key] for key in ['code','state','stage'] if isinstance(value.get(key),str) and re.fullmatch('[A-Z_]{1,128}',value[key])}
                diagnostic['action']=sys.argv[1]
                fd=os.open('/run/eatwhat-ssh/agent-diagnostics.jsonl',os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
                with os.fdopen(fd,'w') as out:out.write(json.dumps(diagnostic)+'\n')
            except Exception:pass
            # Optional isolated failure: the real dispatch has already executed.
            # Drop only its first reply; never skip or replace the owning agent.
            recovering=namespace.get('PROTOCOL')=='eatwhat-member-configuration-recovery-v1'
            fault=Path('/fixture-input/drop-recovery-dispatch-reply' if recovering else '/fixture-input/drop-member-dispatch-reply')
            consumed=Path('/run/eatwhat-ssh/recovery-dispatch-reply-dropped' if recovering else '/run/eatwhat-ssh/member-dispatch-reply-dropped')
            if namespace.get('PROTOCOL') in ['eatwhat-member-configure-v1','eatwhat-member-configuration-recovery-v1'] and sys.argv[1]=='dispatch' and fault.exists() and not consumed.exists():
                consumed.touch(mode=0o600)
            else:sys.stdout.write(reply)
    else:
        exec(compile(source,'<reviewed-fixture-agent>','exec'),{'__name__':'__main__'})
except Exception:
    print('{"code":"FIXTURE_COMMAND_NOT_ALLOWED"}')
    sys.exit(1)
