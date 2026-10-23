import contextlib,io,pathlib,runpy,sys,tempfile,unittest,uuid
from unittest.mock import patch
SOURCE=(pathlib.Path(__file__).resolve().parents[2]/'src/main/resources/control/server-update.py').read_text()
class ServerUpdateClusterGuard(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.base=pathlib.Path(self.temp.name);self.server=str(uuid.uuid4());self.root=self.base/self.server;(self.root/'config').mkdir(parents=True);(self.root/'deploy').mkdir();self.props=self.root/'config/application.properties';self.props.write_text('app.cluster.enabled=false\napp.site.id=fixture\n');self.props.chmod(0o600)
 def tearDown(self):self.temp.cleanup()
 def run_agent(self,action):
  real=pathlib.Path
  with patch.object(pathlib,'Path',side_effect=lambda name:self.base if name=='/opt/eatwhat-sites' else real(name)),patch.object(sys,'argv',['agent',self.server,action,str(uuid.uuid4()),'a'*64,'fixture',str(uuid.uuid4()),'b'*64]),patch.object(runpy,'run_path') as delegate,contextlib.redirect_stdout(io.StringIO()):
   try:exec(compile(SOURCE,'<reviewed-server-update>','exec'),{});accepted=True
   except SystemExit:accepted=False
   return accepted,delegate.call_count
 def test_standalone_prepare_and_apply_use_only_the_existing_reviewed_agent(self):
  for action in ['prepare','apply']:self.assertEqual((True,1),self.run_agent(action))
 def test_joined_shared_authority_is_rejected_before_any_update_or_restore_agent_runs(self):
  self.props.write_text('app.cluster.enabled=true\napp.site.cluster-id='+str(uuid.uuid4())+'\n')
  for action in ['prepare','apply']:self.assertEqual((False,0),self.run_agent(action))
 def test_unknown_ambiguous_and_symlinked_configuration_is_not_treated_as_standalone(self):
  for text in ['app.site.id=fixture\n','app.cluster.enabled=false\napp.cluster.enabled=true\n','app.cluster.enabled=false\napp.cluster.enabled true\n',r'app.cluster.enabled=false'+'\n'+r'\u0061pp.cluster.enabled=true'+'\n']:
   self.props.write_text(text);self.assertEqual((False,0),self.run_agent('apply'))
  self.props.rename(self.root/'config/original');self.props.symlink_to(self.root/'config/original');self.assertEqual((False,0),self.run_agent('apply'))
 def test_read_only_status_remains_available_after_membership_changes(self):
  self.props.write_text('app.cluster.enabled=true\n');self.assertEqual((True,1),self.run_agent('status'))
if __name__=='__main__':unittest.main()
