import os, re, runpy, stat, sys, uuid
from pathlib import Path
try:
    if len(sys.argv)!=8: raise ValueError()
    server,action,job,sha,site,instance,old=sys.argv[1:]
    if str(uuid.UUID(server))!=server or action not in ['prepare','apply','status']: raise ValueError()
    root=Path('/opt/eatwhat-sites')/server
    if root.is_symlink(): raise ValueError()
    script=root/'deploy/remote-release.py';config=root/'deploy/release.json'
    if script.is_symlink() or config.is_symlink(): raise ValueError()
    if action in ['prepare','apply']:
        # A joined authority may share its DB with the running controller.
        # Legacy single-server updates do not bind membership epochs or review
        # compatibility with the controller sharing that authority database.
        properties=root/'config/application.properties'
        if properties.is_symlink() or not properties.is_file() or properties.stat().st_size>65536 or stat.S_IMODE(properties.stat().st_mode)!=0o600:raise ValueError()
        values={}
        for line in properties.read_text(encoding='ISO-8859-1').splitlines():
            if not line.strip() or line.lstrip().startswith(('#','!')):continue
            match=re.fullmatch(r'\s*([^=:\s\\]+)\s*[=:]\s*(.*)',line)
            if match is None or match[1] in values:raise ValueError()
            values[match[1]]=match[2].strip()
        if values.get('app.cluster.enabled')!='false' or values.get('app.site.cluster-id'):raise ValueError()
    sys.argv=[str(script),str(config),action,job,sha,site,instance,old]
    runpy.run_path(str(script),run_name='__main__')
except Exception:
    print('{"code":"REMOTE_DEPLOYMENT_REJECTED_OR_UNKNOWN"}');sys.exit(1)
