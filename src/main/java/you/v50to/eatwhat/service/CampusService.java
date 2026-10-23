package you.v50to.eatwhat.service;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.po.Campus;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.mapper.CampusMapper;

import java.util.List;

@Service
public class CampusService {

    @Resource
    private CampusMapper campusMapper;

    /** 已开放的校区，按运营设定的顺序。 */
    public Result<List<Campus>> listOpen() {
        return Result.ok(campusMapper.selectOpen());
    }

    /** 推荐等接口用的校区：传了就必须是已开放的；没传用默认校区。找不到返回 null。 */
    public Campus resolve(Long campusId) {
        if (campusId == null) return campusMapper.selectDefault();
        Campus campus = campusMapper.selectById(campusId);
        return campus == null || !Boolean.TRUE.equals(campus.getOpen()) ? null : campus;
    }

    public boolean exists(Long campusId) {
        return campusId != null && campusMapper.selectById(campusId) != null;
    }
}
