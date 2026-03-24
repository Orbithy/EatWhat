package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import you.v50to.eatwhat.data.po.Hub;
import you.v50to.eatwhat.utils.MultiPolygonTypeHandler;
import you.v50to.eatwhat.utils.PointTypeHandler;

@Mapper
public interface HubMapper extends BaseMapper<Hub> {

    @Results(id = "hubResultMap", value = {
            @Result(property = "center", column = "center", typeHandler = PointTypeHandler.class),
            @Result(property = "boundary", column = "boundary", typeHandler = MultiPolygonTypeHandler.class)
    })
    @Select("SELECT * FROM hub WHERE id = #{id}")
    Hub selectHubById(@Param("id") Long id);

    @Update("""
            UPDATE restaurant
            SET hub_id = NULL
            WHERE hub_id = #{hubId}
            """)
    int clearRestaurantBindings(@Param("hubId") Long hubId);

    @Update("""
            UPDATE restaurant r
            SET hub_id = #{hubId}
            FROM hub h
            WHERE h.id = #{hubId}
              AND h.boundary IS NOT NULL
              AND ST_Covers(h.boundary, r.location::geometry)
            """)
    int bindCoveredRestaurants(@Param("hubId") Long hubId);
}
