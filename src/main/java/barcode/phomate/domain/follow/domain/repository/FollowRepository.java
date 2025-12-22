package barcode.phomate.domain.follow.domain.repository;

import barcode.phomate.domain.follow.domain.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    void deleteByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    @Query("""
        select f.followee.id
        from Follow f
        where f.follower.id = :followerId
          and f.followee.id in :followeeIds
    """)
    List<Long> findFollowedMemberIds(@Param("followerId") Long followerId,
                                     @Param("followeeIds") List<Long> followeeIds);

    @Query("""
        select f.followee
        from Follow f
        where f.follower.id = :followerId
        order by f.createdAt desc
    """)
    List<barcode.phomate.domain.member.domain.entity.Member> findFollowees(@Param("followerId") Long followerId);
}
