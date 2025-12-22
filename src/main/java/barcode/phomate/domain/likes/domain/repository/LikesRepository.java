package barcode.phomate.domain.likes.domain.repository;

import barcode.phomate.domain.likes.domain.entity.Likes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LikesRepository extends JpaRepository<Likes, Long> {

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    void deleteByMemberIdAndPostId(Long memberId, Long postId);

    @Query("""
        select l.post.id
        from Likes l
        where l.member.id = :memberId
          and l.post.id in :postIds
    """)
    List<Long> findLikedPostIds(@Param("memberId") Long memberId,
                                @Param("postIds") List<Long> postIds);
}

