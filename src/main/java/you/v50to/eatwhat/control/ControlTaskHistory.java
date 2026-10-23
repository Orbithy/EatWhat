package you.v50to.eatwhat.control;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;

/** Local journals only. Listing a task never starts it, reconciles a remote host or returns private plan data. */
public final class ControlTaskHistory {
    private static final Set<String> TYPES=Set.of("INSTALL","UPDATE","RECOVER","ENROLLMENT","DEPLOYMENT","CONTROLLER_INSTALL");
    private static final String FILE="(?:server-job|server-cluster-job|deployment|controller-job)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json";
    private final Path directory;private final BiFunction<String,UUID,Map<String,Object>> reader;
    record Position(long millis,String file){}
    record Entry(Path path,Position position){}
    private static final Comparator<Position> ORDER=Comparator.comparingLong(Position::millis).thenComparing(Position::file);
    public ControlTaskHistory(Path directory,BiFunction<String,UUID,Map<String,Object>> reader){this.directory=directory;this.reader=reader;}
    public Map<String,Object> list(int limit,String type,String state,UUID serverId,String cursor){
        if(limit<1||limit>100||type!=null&&!TYPES.contains(type)||state!=null&&!state.matches("[A-Z][A-Z0-9_]{0,63}"))throw new ControlFailure(400,"INVALID_TASK_FILTER");
        Position after=decode(cursor);var entries=new ArrayList<Entry>();
        try(var files=Files.list(directory)){
            var paths=files.filter(p->p.getFileName().toString().matches(FILE)).limit(10001).toList();
            if(paths.size()>10000)throw new ControlFailure(503,"TASK_HISTORY_SCAN_LIMIT");
            for(var p:paths){if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>1024*1024)throw new ControlFailure(503,"TASK_HISTORY_UNAVAILABLE");var position=new Position(Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toMillis(),p.getFileName().toString());if(after==null||ORDER.compare(position,after)<0)entries.add(new Entry(p,position));}
        }catch(ControlFailure e){throw e;}catch(Exception e){throw new ControlFailure(503,"TASK_HISTORY_UNAVAILABLE");}
        entries.sort(Comparator.comparing(Entry::position,ORDER.reversed()));
        var items=new ArrayList<Map<String,Object>>();Position last=null;boolean more=false;
        for(var entry:entries){
            String filename=entry.position.file();String group=filename.startsWith("server-cluster-job-")?"ENROLLMENT":filename.startsWith("server-job-")?"SERVER":filename.startsWith("controller-job-")?"CONTROLLER_INSTALL":"DEPLOYMENT";
            String prefix=switch(group){case "ENROLLMENT"->"server-cluster-job-";case "SERVER"->"server-job-";case "CONTROLLER_INSTALL"->"controller-job-";default->"deployment-";};
            UUID id=UUID.fromString(filename.substring(prefix.length(),filename.length()-5));Map<String,Object> job;
            try{job=reader.apply(group,id);}catch(Exception e){throw new ControlFailure(503,"TASK_HISTORY_UNAVAILABLE");}
            if(job==null||!id.toString().equals(Objects.toString(job.get("id"))))throw new ControlFailure(503,"TASK_HISTORY_INVALID_RECORD");
            String kind=group.equals("SERVER")?Objects.toString(job.get("kind")):group;
            if(!TYPES.contains(kind))throw new ControlFailure(503,"TASK_HISTORY_INVALID_RECORD");
            String taskState=code(job.get("state"));if(taskState==null)throw new ControlFailure(503,"TASK_HISTORY_INVALID_RECORD");
            boolean matches=(type==null||type.equals(kind))&&(state==null||state.equals(taskState))&&(serverId==null||serverId.toString().equals(Objects.toString(job.get("serverId"))));
            if(matches){if(items.size()==limit){more=true;break;}items.add(summary(id,kind,taskState,job,entry.position));}
            last=entry.position;
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",more&&last!=null?encode(last):null);result.put("observedAt",Instant.now().toString());return result;
    }
    private static Map<String,Object> summary(UUID id,String type,String state,Map<String,Object> job,Position position){
        var row=new LinkedHashMap<String,Object>();row.put("id",id.toString());row.put("type",type);row.put("state",state);row.put("updatedAt",Instant.ofEpochMilli(position.millis).toString());row.put("requiresReconciliation",Set.of("OUTCOME_UNKNOWN","ROLLBACK_FAILED").contains(state));
        row.put("detailRoute","/admin/control/v1/"+(type.equals("ENROLLMENT")?"server-cluster-jobs/":type.equals("DEPLOYMENT")?"deployments/":type.equals("CONTROLLER_INSTALL")?"controller-jobs/":"server-jobs/")+id);
        Object result=job.get("result");Map<?,?> details=result instanceof Map<?,?> m?m:Map.of();
        String stage=code(job.containsKey("stage")?job.get("stage"):details.get("stage"));if(stage!=null)row.put("stage",stage);
        String reason=code(job.containsKey("code")?job.get("code"):job.containsKey("reason")?job.get("reason"):details.get("code"));if(reason!=null)row.put("code",reason);
        for(String key:List.of("serverId","siteId","releaseId","artifactSha256")){Object value=job.get(key);if(value==null)continue;String text=value.toString();boolean valid=key.equals("artifactSha256")?text.matches("[0-9a-f]{64}"):key.equals("serverId")?text.matches("[0-9a-f-]{36}"):text.matches("[A-Za-z0-9_-]{1,64}");if(valid)row.put(key,text);}
        Object created=job.get("createdAt");if(created instanceof String value){try{row.put("createdAt",Instant.parse(value).toString());}catch(Exception ignored){}}
        return row;
    }
    private static String code(Object value){return value instanceof String s&&s.matches("[A-Z][A-Z0-9_]{0,127}")?s:null;}
    private static String encode(Position position){return Base64.getUrlEncoder().withoutPadding().encodeToString((position.millis+"\n"+position.file).getBytes(StandardCharsets.UTF_8));}
    private static Position decode(String cursor){
        if(cursor==null||cursor.isEmpty())return null;
        try{if(cursor.length()>300||!cursor.matches("[A-Za-z0-9_-]+"))throw new Exception();String[] parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);if(parts.length!=2||!parts[0].matches("[0-9]{1,18}")||!parts[1].matches(FILE))throw new Exception();return new Position(Long.parseLong(parts[0]),parts[1]);}
        catch(Exception e){throw new ControlFailure(400,"INVALID_TASK_CURSOR");}
    }
}
