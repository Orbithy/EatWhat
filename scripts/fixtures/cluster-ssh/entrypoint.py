"""Disposable test SSH only. Never alters host or production SSH configuration."""
import os,shutil,subprocess
from pathlib import Path

root=Path('/run/eatwhat-ssh');root.mkdir(mode=0o700,exist_ok=True)
password=Path('/fixture-input/password').read_text().strip()
if not password or any(c in password for c in '\r\n:'):raise SystemExit('Invalid isolated fixture password')
subprocess.run(['chpasswd'],input=('root:'+password+'\n').encode(),check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
target=root/'authorized_keys';shutil.copyfile('/fixture-input/client.pub',target);target.chmod(0o600)
subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(root/'host_key')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
# Keep the Python process alive for the optional fixture HTTP proxy threads.
from fixture_proxy import start
start()
from member_network import start as start_member_network
start_member_network()
raise SystemExit(subprocess.call(['/usr/sbin/sshd','-D','-e','-f','/fixture/sshd_config']))
