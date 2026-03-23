package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Restaurant;

@Mapper
public interface RestaurantMapper extends BaseMapper<Restaurant> {

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
