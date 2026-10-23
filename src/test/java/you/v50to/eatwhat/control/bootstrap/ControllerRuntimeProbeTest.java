package you.v50to.eatwhat.control.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControllerRuntimeProbeTest {
    private Connection connection;
    private final UUID cluster=UUID.randomUUID(),instance=UUID.randomUUID(),state=UUID.randomUUID(),holder=UUID.randomUUID(),leader=UUID.randomUUID();
    private Map<String,Object> expected;
    private List<PreparedStatement> statements;
    private List<ResultSet> rows;
    @BeforeEach void prepare()throws Exception {
        expected=new LinkedHashMap<>(Map.of("clusterId",cluster.toString(),"authoritySiteId","campus","authorityInstanceId",instance.toString(),
                "authorityDatabaseFingerprint","a".repeat(32),"stateId",state.toString(),"controllerName","control-"+"b".repeat(24),"requireStandby",true));
        connection=mock(Connection.class);statements=new ArrayList<>();rows=new ArrayList<>();
        for(int i=0;i<6;i++){
            var statement=mock(PreparedStatement.class);var result=mock(ResultSet.class);
            when(result.next()).thenReturn(true,false);when(statement.executeQuery()).thenReturn(result);statements.add(statement);rows.add(result);
        }
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);assertThat(sql).startsWith("SELECT ");
            if(sql.contains("FROM eatwhat_cluster.state "))return statements.get(0);
            if(sql.contains("FROM eatwhat_cluster.member "))return statements.get(1);
            if(sql.contains("FROM eatwhat_cluster.control_process "))return statements.get(2);
            if(sql.contains("FROM eatwhat_cluster.control_lease "))return statements.get(3);
            if(sql.contains("FROM eatwhat_identity.node_state "))return statements.get(4);
            if(sql.contains("FROM eatwhat_identity.allocation "))return statements.get(5);
            throw new AssertionError(sql);
        });
        when(rows.get(0).getObject(1,UUID.class)).thenReturn(cluster);when(rows.get(0).getLong(2)).thenReturn(2L);
        when(rows.get(1).getObject(1,UUID.class)).thenReturn(instance);when(rows.get(1).getString(2)).thenReturn("CAMPUS");
        when(rows.get(1).getInt(3)).thenReturn(0);when(rows.get(1).getString(4)).thenReturn("ACTIVE");when(rows.get(1).getString(5)).thenReturn("a".repeat(32));
        when(rows.get(2).getObject(1,UUID.class)).thenReturn(holder);when(rows.get(2).getObject(2,UUID.class)).thenReturn(state);
        when(rows.get(3).getObject(1,UUID.class)).thenReturn(leader);when(rows.get(3).getBoolean(2)).thenReturn(true);
        when(rows.get(3).getString(3)).thenReturn("campus-control");when(rows.get(3).getObject(4,UUID.class)).thenReturn(state);
        when(rows.get(4).getString(1)).thenReturn("campus");when(rows.get(4).getString(2)).thenReturn("CAMPUS");when(rows.get(4).getObject(3,UUID.class)).thenReturn(instance);
        when(rows.get(4).getBoolean(4)).thenReturn(true);when(rows.get(4).getBoolean(5)).thenReturn(true);when(rows.get(4).getString(6)).thenReturn("a".repeat(32));
        when(rows.get(5).getObject(1,UUID.class)).thenReturn(cluster);when(rows.get(5).getString(2)).thenReturn("campus");when(rows.get(5).getObject(3,UUID.class)).thenReturn(instance);when(rows.get(5).getInt(4)).thenReturn(0);
    }
    @Test void provesExistingAuthoritySharedStateAndLiveStandbyInReadOnlyTransaction()throws Exception {
        var result=ControllerRuntimeProbe.snapshot(connection,expected);
        assertThat(result).containsEntry("epoch",2L).containsEntry("readOnlyTransaction",true).containsEntry("localLeader",false).containsEntry("leaderName","campus-control");
        var order=inOrder(connection);order.verify(connection).setReadOnly(true);order.verify(connection).setAutoCommit(false);order.verify(connection,times(6)).prepareStatement(anyString());
        verify(connection).rollback();verify(connection,never()).commit();
        for(var statement:statements){verify(statement).setQueryTimeout(5);verify(statement,never()).executeUpdate();}
        verify(statements.get(1)).setString(1,"campus");verify(statements.get(2)).setString(2,expected.get("controllerName").toString());
    }
    @Test void rejectsAnotherClusterAndRollsBack()throws Exception {
        when(rows.get(0).getObject(1,UUID.class)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void rejectsAnotherAuthorityDatabaseFingerprint()throws Exception {
        when(rows.get(1).getString(5)).thenReturn("c".repeat(32));
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);verify(connection).rollback();
    }
    @Test void rejectsInactiveOrExternalAuthority()throws Exception {
        when(rows.get(1).getString(2)).thenReturn("EXTERNAL");
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsUnsharedStateOrDuplicateControllerName()throws Exception {
        when(rows.get(2).getObject(2,UUID.class)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);
        when(rows.get(2).next()).thenReturn(true,true);when(rows.get(0).next()).thenReturn(true,false);when(rows.get(1).next()).thenReturn(true,false);
        when(rows.get(4).next()).thenReturn(true,false);when(rows.get(5).next()).thenReturn(true,false);
        when(rows.get(2).getObject(2,UUID.class)).thenReturn(state);
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);
    }
    @Test void initialVerificationRejectsUnexpectedTakeover()throws Exception {
        when(rows.get(3).getObject(1,UUID.class)).thenReturn(holder);
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);
    }
    @Test void laterReadOnlyVerificationCanObserveAnExplicitManualTakeover()throws Exception {
        when(rows.get(3).getObject(1,UUID.class)).thenReturn(holder);expected.put("requireStandby",false);
        assertThat(ControllerRuntimeProbe.snapshot(connection,expected)).containsEntry("localLeader",true);verify(connection,never()).commit();
    }
    @Test void rejectsExpiredAuthorityLease()throws Exception {
        when(rows.get(3).getBoolean(2)).thenReturn(false);
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);verify(connection).rollback();
    }
    @Test void storedMemberCannotMaskWrongActualDatabaseOid()throws Exception {
        when(rows.get(4).getBoolean(5)).thenReturn(false);
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);verify(connection).rollback();
    }
    @Test void rejectsUnexpectedFieldsBeforeOpeningTransaction()throws Exception {
        expected.put("sql","DELETE FROM users");
        assertThatThrownBy(()->ControllerRuntimeProbe.snapshot(connection,expected)).isInstanceOf(IllegalStateException.class);
        verify(connection,never()).prepareStatement(anyString());
    }
}
