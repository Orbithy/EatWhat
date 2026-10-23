package you.v50to.eatwhat.cluster;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import java.util.UUID;

/** Independent I/O fence, with no business/revision row lock held across storage or HTTP calls. */
final class ClusterFileIo implements AutoCloseable {
    private final Connection connection;
    private ClusterFileIo(Connection connection){this.connection=connection;}
    static ClusterFileIo acquire(JdbcTemplate jdbc,UUID file,int revision){Connection connection=null;try{
        connection=jdbc.getDataSource().getConnection();connection.setAutoCommit(false);
        try(var query=connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended('file-io:'||?::text||':'||?::text,0))")){
            query.setString(1,file.toString());query.setString(2,Integer.toString(revision));try(var result=query.executeQuery()){if(!result.next()||!result.getBoolean(1))throw new ClusterFailure(409,"FILE_IO_BUSY");}
        }
        return new ClusterFileIo(connection);
    }catch(Exception failure){if(connection!=null)try{connection.rollback();connection.close();}catch(Exception ignored){}throw new ClusterFailure(409,"FILE_IO_UNAVAILABLE");}}
    public void close(){try{connection.rollback();}catch(Exception failure){throw new ClusterFailure(503,"FILE_IO_RELEASE_FAILED");}finally{try{connection.close();}catch(Exception failure){throw new ClusterFailure(503,"FILE_IO_CLOSE_FAILED");}}}
}
