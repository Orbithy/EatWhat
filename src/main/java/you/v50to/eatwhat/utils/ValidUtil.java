package you.v50to.eatwhat.utils;

public class ValidUtil {

    public static Integer validPage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    public static Integer validPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 100);
    }
}
