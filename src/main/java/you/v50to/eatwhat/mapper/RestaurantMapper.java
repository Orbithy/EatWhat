package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Restaurant;
import you.v50to.eatwhat.utils.PointTypeHandler;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;
import you.v50to.eatwhat.utils.TimestampTypeHandler;

@Mapper
public interface RestaurantMapper extends BaseMapper<Restaurant> {

    @Results(id = "restaurantResultMap", value = {
            @Result(property = "POI", column = "poi"),
            @Result(property = "accountId", column = "account_id"),
            @Result(property = "location", column = "location", typeHandler = PointTypeHandler.class),
            @Result(property = "pictureUrl", column = "picture_url", typeHandler = StringArrayTypeHandler.class),
            @Result(property = "createdAt", column = "created_at", typeHandler = TimestampTypeHandler.class),
            @Result(property = "updatedAt", column = "updated_at", typeHandler = TimestampTypeHandler.class)
    })
    @Select("""
            SELECT *,
                   ST_Distance(location, ST_SetSRID(ST_MakePoint(#{wgsLng}, #{wgsLat}), 4326)::geography) AS distance
            FROM restaurant
            WHERE (#{keyword} IS NULL OR name LIKE CONCAT('%', #{keyword}, '%'))
              AND (#{radius} IS NULL OR ST_DWithin(location,
                       ST_SetSRID(ST_MakePoint(#{wgsLng}, #{wgsLat}), 4326)::geography,
                       #{radius}))
            ORDER BY distance ASC
            """)
    IPage<Restaurant> searchRestaurants(@Param("keyword") String keyword,
                                        @Param("wgsLng") double wgsLng,
                                        @Param("wgsLat") double wgsLat,
                                        @Param("radius") Double radius,
                                        IPage<Restaurant> page);

    @Select("SELECT COUNT(*) FROM restaurant")
    Long countTotal();

    @Select("SELECT COUNT(*) FROM restaurant WHERE created_at >= CURRENT_DATE")
    Long countToday();

}
