package you.v50to.eatwhat.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import you.v50to.eatwhat.data.dto.RecommendationProfileRow;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import you.v50to.eatwhat.data.dto.FoodPreferencesRow;
import you.v50to.eatwhat.utils.StringArrayTypeHandler;

@Mapper
public interface RecommendationProfileMapper {
    @SelectProvider(type = RecommendationProfileSql.class, method = "selectProfile")
    List<RecommendationProfileRow> selectProfile(@Param("accountId") Long accountId);

    @Select("SELECT liked_tags,disliked_tags,(extract(epoch FROM updated_at)*1000)::bigint AS updated_at "
            + "FROM public.user_food_preferences WHERE account_id=#{accountId}")
    @Results({
            @Result(property="likedTags", column="liked_tags", typeHandler=StringArrayTypeHandler.class),
            @Result(property="dislikedTags", column="disliked_tags", typeHandler=StringArrayTypeHandler.class),
            @Result(property="updatedAt", column="updated_at")
    })
    FoodPreferencesRow selectPreferences(@Param("accountId") long accountId);

    @Insert("""
            <script>
            INSERT INTO public.user_food_preferences(account_id,liked_tags,disliked_tags,updated_at)
            VALUES(#{accountId},
                ARRAY[<foreach collection='liked' item='tag' separator=','>#{tag}</foreach>]::text[],
                ARRAY[<foreach collection='disliked' item='tag' separator=','>#{tag}</foreach>]::text[],now())
            ON CONFLICT(account_id) DO UPDATE SET liked_tags=EXCLUDED.liked_tags,
                disliked_tags=EXCLUDED.disliked_tags,updated_at=EXCLUDED.updated_at
            </script>
            """)
    void savePreferences(@Param("accountId") long accountId, @Param("liked") List<String> liked,
                         @Param("disliked") List<String> disliked);

    @Delete("DELETE FROM public.user_food_preferences WHERE account_id=#{accountId}")
    void deletePreferences(@Param("accountId") long accountId);
}
