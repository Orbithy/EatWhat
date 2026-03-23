package you.v50to.eatwhat.service;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import you.v50to.eatwhat.data.vo.Result;
import you.v50to.eatwhat.data.vo.SummaryVO;
import you.v50to.eatwhat.mapper.FoodMapper;
import you.v50to.eatwhat.mapper.RestaurantMapper;
import you.v50to.eatwhat.mapper.UserMapper;

@Service
public class AdminService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private FoodMapper foodMapper;
    @Resource
    private RestaurantMapper restaurantMapper;

    public Result<SummaryVO> summary() {
        SummaryVO vo = new SummaryVO();

        vo.setTotalUsers(userMapper.countTotal());
        vo.setTotalFoods(foodMapper.countTotal());
        vo.setTotalRestaurants(restaurantMapper.countTotal());

        vo.setTodayUsers(userMapper.countToday());
        vo.setTodayFoods(foodMapper.countToday());
        vo.setTodayRestaurants(restaurantMapper.countToday());

        Double rate = userMapper.selectVerifiedRate();
        vo.setVerifiedRate(rate != null ? rate : 0.0);

        return Result.ok(vo);
    }
}
