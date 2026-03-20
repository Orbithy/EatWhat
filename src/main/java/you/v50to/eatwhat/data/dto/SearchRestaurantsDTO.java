package you.v50to.eatwhat.data.dto;

import lombok.Data;

@Data
public class SearchRestaurantsDTO {
    private String keyword;

    private Double gcjLng;
    private Double gcjLat;

    /** 搜索半径，单位：米，为空时不限制范围 */
    private Double radius;

    private Integer page = 1;
    private Integer pageSize = 20;
}
