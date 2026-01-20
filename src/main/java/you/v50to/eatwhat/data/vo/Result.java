package you.v50to.eatwhat.data.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    private Integer code; // 业务状态码
    private Object data;  // 数据
    private String msg;   // 提示信息

}
