package you.v50to.eatwhat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import you.v50to.eatwhat.data.po.ActivityLike;

@Mapper
public interface ActivityLikeMapper extends BaseMapper<ActivityLike> {
    @org.apache.ibatis.annotations.Update("UPDATE activity_likes SET deleted_at=NULL WHERE id=#{id} AND deleted_at IS NOT NULL")
    int restore(@org.apache.ibatis.annotations.Param("id") Long id);

}
