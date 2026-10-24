package you.v50to.eatwhat.utils;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointTypeHandlerTest {
    @Test void oneMybatisHandlerParsesDifferentPointsConcurrentlyWithoutSharedReaderState() throws Exception {
        var handler=new PointTypeHandler();var pool=Executors.newFixedThreadPool(8);
        var start=new CountDownLatch(1);var jobs=new ArrayList<Future<Void>>();
        try {
            for(int n=0;n<8;n++) {
                final int value=n;
                jobs.add(pool.submit(()->{
                    var expected=new GeometryFactory().createPoint(new Coordinate(117+value/8.0,36+value/16.0));
                    String encoded=value<4?WKBWriter.toHex(new WKBWriter().write(expected)):expected.toText();
                    var row=mock(ResultSet.class);when(row.getObject(1)).thenReturn(encoded);
                    start.await();
                    for(int i=0;i<500;i++)assertTrue(expected.equalsExact(handler.getNullableResult(row,1)));
                    return null;
                }));
            }
            start.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
}
