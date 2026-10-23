#!/usr/bin/env python3
"""Real OS stdin regression: child commands cannot consume SSH artifact bytes.

No Docker, network, database or application is simulated as accepted here. A
real child tries to read inherited stdin; explicit bytes and file inputs remain
usable by backup/restore inspection commands.
"""
import hashlib,json,os,subprocess,sys,unittest
from pathlib import Path

HELPER=Path(__file__).resolve().parents[1]/'deploy/remote-release.py'
CALLER=r'''
import hashlib,importlib.util,json,os,sys,tempfile
spec=importlib.util.spec_from_file_location('reviewed_helper',sys.argv[1]);helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
agent=helper.Agent.__new__(helper.Agent);agent.c={'command_timeout_seconds':3};agent.environment={'PATH':os.environ.get('PATH','')};agent.base=[sys.executable,'-c','import sys;print(len(sys.stdin.buffer.read()))']
mode=sys.argv[2]
if mode=='default':result=agent.command([])
elif mode=='bytes':result=agent.command([],input=b'explicit-controlled-input')
else:
 with tempfile.TemporaryFile() as source:
  source.write(b'explicit-controlled-file');source.seek(0);result=agent.command([],stdin=source)
remaining=sys.stdin.buffer.read()
print(json.dumps({'childBytes':int(result.strip()),'remainingBytes':len(remaining),'remainingSha256':hashlib.sha256(remaining).hexdigest()}))
'''
class CommandInputChecks(unittest.TestCase):
    def check(self,mode,expected):
        payload=os.urandom(1048576)
        reply=subprocess.run([sys.executable,'-c',CALLER,str(HELPER),mode],input=payload,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10,check=True)
        value=json.loads(reply.stdout);self.assertEqual(expected,value['childBytes']);self.assertEqual(len(payload),value['remainingBytes']);self.assertEqual(hashlib.sha256(payload).hexdigest(),value['remainingSha256'])
    def test_default_child_gets_eof_and_entire_candidate_remains_for_the_ssh_agent(self):self.check('default',0)
    def test_explicit_byte_input_still_works_without_reading_candidate(self):self.check('bytes',len(b'explicit-controlled-input'))
    def test_explicit_file_input_still_works_without_reading_candidate(self):self.check('file',len(b'explicit-controlled-file'))

if __name__=='__main__':unittest.main()
