package barcode.phomate.domain.post.domain.repository;

import barcode.phomate.domain.post.domain.entity.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {
    // 최신순
    @Query("""
        select p
        from Post p
        where (:cursorTime is null or p.createdAt < :cursorTime
              or (p.createdAt = :cursorTime and p.id < :cursorId))
        order by p.createdAt desc, p.id desc
    """)
    List<Post> findFeedLatest(
            @Param("cursorTime") LocalDateTime cursorTime,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    // 좋아요순
    @Query("""
        select p
        from Post p
        where (:cursorLike is null or p.likeCount < :cursorLike
              or (p.likeCount = :cursorLike and p.id < :cursorId))
        order by p.likeCount desc, p.id desc
    """)
    List<Post> findFeedLike(
            @Param("cursorLike") Long cursorLike,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
