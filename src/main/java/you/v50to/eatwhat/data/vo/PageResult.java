package you.v50to.eatwhat.data.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果封装
 * @param <T> 数据项类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    
    /**
     * 当前页码（从1开始）
     */
    private Integer currentPage;
    
    /**
     * 数据列表
     */
    private List<T> items;
    
    /**
     * 总页数
     */
    private Integer totalPages;
    
    /**
     * 总记录数
     */
    private Long totalItems;
    
    /**
     * 是否有下一页
     */
    private Boolean hasNext;
    
    /**
     * 构建分页结果
     * @param items 数据列表
     * @param currentPage 当前页码
     * @param pageSize 每页大小
     * @param totalItems 总记录数
     */
    public static <T> PageResult<T> of(List<T> items, Integer currentPage, Integer pageSize, Long totalItems) {
        PageResult<T> result = new PageResult<>();
        result.setCurrentPage(currentPage);
        result.setItems(items);
        result.setTotalItems(totalItems);
        
        // 计算总页数
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        result.setTotalPages(totalPages);
        
        // 判断是否有下一页
        result.setHasNext(currentPage < totalPages);
        
        return result;
    }
}

