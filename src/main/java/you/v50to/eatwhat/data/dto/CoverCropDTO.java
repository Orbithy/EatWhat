package you.v50to.eatwhat.data.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 封面圆形裁切（技术架构 13.4）：圆心按原图宽高归一到 0–1，直径是占原图短边的比例，默认 0.5/0.5/1 即居中内切圆。
 * 后端不知道原图尺寸，不校验圆是否超出原图，由客户端保证。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoverCropDTO {

    @NotNull(message = "裁切圆心不能为空")
    @DecimalMin(value = "0", message = "裁切圆心需在 0 到 1 之间")
    @DecimalMax(value = "1", message = "裁切圆心需在 0 到 1 之间")
    @Digits(integer = 1, fraction = 4, message = "裁切参数最多 4 位小数")
    private BigDecimal centerX;

    @NotNull(message = "裁切圆心不能为空")
    @DecimalMin(value = "0", message = "裁切圆心需在 0 到 1 之间")
    @DecimalMax(value = "1", message = "裁切圆心需在 0 到 1 之间")
    @Digits(integer = 1, fraction = 4, message = "裁切参数最多 4 位小数")
    private BigDecimal centerY;

    @NotNull(message = "裁切直径不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "裁切直径需在 0 到 1 之间")
    @DecimalMax(value = "1", message = "裁切直径需在 0 到 1 之间")
    @Digits(integer = 1, fraction = 4, message = "裁切参数最多 4 位小数")
    private BigDecimal diameter;
}
