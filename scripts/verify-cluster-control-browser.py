#!/usr/bin/env python3
"""Run optional local browser acceptance against three real isolated Spring servers. No installs/deployment."""
import os, pathlib, subprocess, tempfile, uuid, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[1]
module=pathlib.Path(os.environ['EATWHAT_PLAYWRIGHT_MODULE']).resolve()
assert (module/'package.json').is_file(), 'Use an existing Playwright installation; never download here'
java_home=pathlib.Path(os.environ['JAVA_HOME'])
report=next((root/'target/surefire-reports').glob('TEST-*.xml'))
props={x.get('name'):x.get('value') for x in ET.parse(report).getroot().findall('./properties/property')}
cp=props['surefire.test.class.path']
output=root/'output/playwright'/('cluster-control-'+uuid.uuid4().hex)
output.mkdir(parents=True)
with tempfile.TemporaryDirectory(prefix='eatwhat-cluster-browser-classes-') as temp:
 subprocess.run([str(java_home/'bin/javac'),'-proc:none','-cp',cp,'-d',temp,str(root/'scripts/test-support/ClusterControlBrowserHarness.java')],cwd=root,check=True)
 result=subprocess.run([str(java_home/'bin/java'),'-Xmx512m','-Dsurefire.test.class.path='+cp,'-cp',temp+os.pathsep+cp,'you.v50to.eatwhat.integration.ClusterControlBrowserHarness',str(root/'scripts/cluster-control-browser.mjs'),str(output)],cwd=root,check=False)
 if result.returncode:raise SystemExit('Browser acceptance failed; inspect '+str(output))
print('Browser evidence:',output)
