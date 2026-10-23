package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import you.v50to.eatwhat.data.po.Campus;

import java.util.List;

@Mapper
public interface CampusMapper extends BaseMapper<Campus> {

    @Select("SELECT id, name, gcj_lng, gcj_lat, is_default, open, sort FROM campus WHERE open ORDER BY sort, id")
    List<Campus> selectOpen();

    @Select("SELECT id, name, gcj_lng, gcj_lat, is_default, open, sort FROM campus WHERE is_default")
    Campus selectDefault();
}
