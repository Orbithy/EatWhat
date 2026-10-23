package you.v50to.eatwhat.control;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ControlTaskHistoryTest {
 @TempDir Path root;Map<UUID,Map<String,Object>> jobs=new HashMap<>();
 UUID add(String prefix,String type,String state,UUID server)throws Exception{UUID id=UUID.randomUUID();var p=root.resolve(prefix+"-"+id+".json");Files.writeString(p,"{}");Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));Files.setLastModifiedTime(p,FileTime.fromMillis(1700000000000L));var j=new LinkedHashMap<String,Object>();j.put("id",id.toString());j.put("kind",type);j.put("state",state);j.put("createdAt","2023-11-14T22:13:20Z");if(server!=null)j.put("serverId",server.toString());j.put("_plan",Map.of("credential","secret-value"));j.put("result",Map.of("stage","DATABASE_REDIS","code","FIRST_INSTALL_FAILED_PRESERVED","credential","secret-value"));jobs.put(id,j);return id;}
 ControlTaskHistory history(){return new ControlTaskHistory(root,(kind,id)->jobs.get(id));}
 @Test void tiesPaginateWithoutDroppingOrDuplicatingJobsAndNoPrivateFieldsEscape()throws Exception{
  var expected=new HashSet<UUID>();for(int i=0;i<7;i++)expected.add(add(i%2==0?"server-job":"deployment",i%2==0?"INSTALL":"DEPLOYMENT","FAILED",null));String cursor=null;var actual=new HashSet<UUID>();int pages=0;
  do{var response=history().list(2,null,null,null,cursor);assertFalse(response.toString().contains("secret-value"));assertFalse(response.toString().contains("_plan"));for(var item:(List<Map<String,Object>>)response.get("items")){assertTrue(actual.add(UUID.fromString(item.get("id").toString())));assertEquals("FIRST_INSTALL_FAILED_PRESERVED",item.get("code"));assertEquals("DATABASE_REDIS",item.get("stage"));}cursor=(String)response.get("nextCursor");assertTrue(++pages<10);}while(cursor!=null);assertEquals(expected,actual);assertEquals(4,pages);
 }
 @Test void typeStateAndServerFiltersAreAppliedTogether()throws Exception{
  UUID server=UUID.randomUUID(),match=add("server-job","UPDATE","OUTCOME_UNKNOWN",server);add("server-job","INSTALL","FAILED",server);add("server-job","UPDATE","FAILED",UUID.randomUUID());add("server-cluster-job","ENROLLMENT","SUCCEEDED",server);
  var response=history().list(20,"UPDATE","OUTCOME_UNKNOWN",server,null);var items=(List<Map<String,Object>>)response.get("items");assertEquals(1,items.size());assertEquals(match.toString(),items.getFirst().get("id"));assertEquals(true,items.getFirst().get("requiresReconciliation"));assertEquals("/admin/control/v1/server-jobs/"+match,items.getFirst().get("detailRoute"));
 }
 @Test void invalidFiltersAndTraversalCursorAreRejected(){assertEquals(400,assertThrows(ControlFailure.class,()->history().list(101,null,null,null,null)).status);assertEquals(400,assertThrows(ControlFailure.class,()->history().list(20,"SECRET",null,null,null)).status);String cursor=Base64.getUrlEncoder().encodeToString("1700000000000\n../../config/application.properties".getBytes());assertEquals("INVALID_TASK_CURSOR",assertThrows(ControlFailure.class,()->history().list(20,null,null,null,cursor)).code);}
 @Test void retainedRecoveryHasItsOwnFilterAndUsesExistingServerJobDetails()throws Exception{UUID server=UUID.randomUUID(),id=add("server-job","RECOVER","FAILED",server);add("server-job","INSTALL","FAILED",server);var response=history().list(20,"RECOVER",null,server,null);var items=(List<Map<String,Object>>)response.get("items");assertEquals(1,items.size());assertEquals(id.toString(),items.getFirst().get("id"));assertEquals("/admin/control/v1/server-jobs/"+id,items.getFirst().get("detailRoute"));}
 @Test void corruptOrSymlinkedRecordCannotTurnIntoAnEmptyHealthyHistory()throws Exception{
  UUID id=add("server-job","INSTALL","FAILED",null);jobs.get(id).put("id",UUID.randomUUID().toString());assertEquals("TASK_HISTORY_INVALID_RECORD",assertThrows(ControlFailure.class,()->history().list(20,null,null,null,null)).code);Files.delete(root.resolve("server-job-"+id+".json"));Files.createSymbolicLink(root.resolve("server-job-"+id+".json"),root.resolve("private-secret"));assertEquals("TASK_HISTORY_UNAVAILABLE",assertThrows(ControlFailure.class,()->history().list(20,null,null,null,null)).code);
 }
 @Test void unauthenticatedRequestCannotReadAnyJournal(){var auth=mock(ControlController.class);when(auth.admin()).thenThrow(new ControlFailure(401,"LOGIN_REQUIRED"));var controller=new ControlTaskController(auth,new ControlAudit(root.resolve("state")),null,null,null,null);assertEquals(401,assertThrows(ControlFailure.class,()->controller.list(20,null,null,null,null)).status);verify(auth).admin();}
}
