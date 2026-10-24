package you.v50to.eatwhat.utils;

/** Must match eatwhat_sync.food_name_key in V77; display spelling is retained. */
public final class FoodNames {
    private FoodNames() { }
    public static String key(String name) {
        return name.replaceAll("[ \\t\\n\\r\\f\\x0B\\u3000]", "");
    }
}
