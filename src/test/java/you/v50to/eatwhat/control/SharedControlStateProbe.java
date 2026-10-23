package you.v50to.eatwhat.control;

import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Operator-invoked acceptance helper, never packaged in the runtime artifact. */
public final class SharedControlStateProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException();String action=args[0];Path base=Path.of(args[1]);UUID id=UUID.fromString(args[2]);String side=args[3];
        if(!Set.of("campus","external").contains(side)||!base.isAbsolute()||Files.isSymbolicLink(base)||!Files.isDirectory(base))throw new IllegalArgumentException();
        var auditRoot=new ControlAudit(base,side.equals("external"));
        if(action.equals("identity")){var store=new ControlServerStore(auditRoot);System.out.println(JsonMapper.builder().build().writeValueAsString(Map.of("stateId",Files.readString(base.resolve("controller-state-id")).strip(),"keyProof",store.fingerprint(id.toString()))));return;}
        Path work=base.resolve("shared-state-proof-"+id);if(!Files.exists(work))try{Files.createDirectory(work,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}catch(FileAlreadyExistsException ignored){}
        if(Files.isSymbolicLink(work)||!Files.getPosixFilePermissions(work).equals(PosixFilePermissions.fromString("rwx------")))throw new IllegalArgumentException();
        Path path=work.resolve("cross-host.lock");
        switch(action){
            case "hold" -> {
                try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
                    Path marker=work.resolve("holder-"+side+".tmp");Files.writeString(marker,side,StandardOpenOption.CREATE_NEW);
                    try(var ready=FileChannel.open(marker,StandardOpenOption.WRITE)){ready.force(true);}Files.move(marker,work.resolve("holder.ready"),StandardCopyOption.ATOMIC_MOVE);
                    Instant until=Instant.now().plusSeconds(90);
                    while(!Files.exists(work.resolve("holder.release"))&&Instant.now().isBefore(until))Thread.sleep(100);
                }
                System.out.println("{\"lockReleased\":true}");
            }
            case "try-lock" -> {try(var channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){var lock=channel.tryLock();boolean acquired=lock!=null;if(lock!=null)lock.release();System.out.println("{\"lockAcquired\":"+acquired+"}");}}
            case "release" -> {Files.writeString(work.resolve("holder.release"),side,StandardOpenOption.CREATE_NEW);System.out.println("{\"releaseRequested\":true}");}
            case "store-write" -> {
                var store=new ControlServerStore(new ControlAudit(work));store.mutate(data->{data.put("acceptanceCipher",store.encrypt(id.toString()));return null;});System.out.println("{\"privateStoreWritten\":true}");
            }
            case "store-verify" -> {
                var store=new ControlServerStore(new ControlAudit(work));if(!id.toString().equals(store.decrypt(store.read().get("acceptanceCipher").toString())))throw new AssertionError("Shared private store differs");store.mutate(data->{data.put("verifiedBy",side);return null;});System.out.println("{\"privateStoreVerified\":true}");
            }
            case "append" -> {
                var audit=new ControlAudit(work);for(int n=0;n<12;n++){audit.append(UUID.randomUUID(),"fixture-"+side,"SHARED_STATE_ACCEPTANCE",id.toString(),"PASS",Map.of("index",n,"body","synthetic".repeat(1024)));Thread.sleep(50);}
                System.out.println("{\"appended\":12}");
            }
            case "verify" -> {
                var mapper=JsonMapper.builder().build();var lines=Files.readAllLines(work.resolve("audit.jsonl"));if(lines.size()!=24)throw new AssertionError("Incomplete audit");var records=new HashSet<String>();
                for(String line:lines){var row=mapper.readTree(line);String actor=row.path("actor").asText();if(!Set.of("fixture-campus","fixture-external").contains(actor)||!row.path("details").path("body").asText().equals("synthetic".repeat(1024)))throw new AssertionError("Invalid audit");records.add(actor+":"+row.path("details").path("index").asInt());}if(records.size()!=24)throw new AssertionError("Duplicate audit");System.out.println("{\"wholeAuditRecords\":24}");
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
