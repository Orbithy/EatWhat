package you.v50to.eatwhat.sync.activity;
import java.util.*;import org.junit.jupiter.api.Test;import you.v50to.eatwhat.sync.EventCodec;import static org.junit.jupiter.api.Assertions.*;
class ActivitySourceTimestampTest {
 private static ActivityEvents.Created value(long at,String description){return new ActivityEvents.Created(1000000004L,UUID.fromString("1d1aa698-cdc1-42b7-9ae5-d52e666276b8"),"source",UUID.fromString("a1122aa1-1490-46bb-a9d7-26de402e9f74"),null,description,null,null,List.of("activity/synthetic.png"),at);}
 @Test void restoresExactImmutableTimestampForBothConversionDirections(){
  var original=value(1792719689272L,"unchanged");String proof=EventCodec.sha256(ActivityEvents.createdPayload(ActivityEvents.DINNERS,original));
  for(long offset:new long[]{0,-1,1})assertEquals(original,ActivityDurableCommands.canonicalCreated(ActivityEvents.DINNERS,value(original.createdAt()+offset,"unchanged"),proof));
 }
 @Test void contentChangesAndLargerTimestampChangesCannotPassTheImmutableHash(){
  var original=value(1792719689272L,"unchanged");String proof=EventCodec.sha256(ActivityEvents.createdPayload(ActivityEvents.DINNERS,original));
  assertThrows(IllegalStateException.class,()->ActivityDurableCommands.canonicalCreated(ActivityEvents.DINNERS,value(original.createdAt()-1,"tampered"),proof));
  assertThrows(IllegalStateException.class,()->ActivityDurableCommands.canonicalCreated(ActivityEvents.DINNERS,value(original.createdAt()+2,"unchanged"),proof));
 }
}
