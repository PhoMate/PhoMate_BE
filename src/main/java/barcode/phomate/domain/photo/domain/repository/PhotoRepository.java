package barcode.phomate.domain.photo.domain.repository;

import barcode.phomate.domain.photo.domain.entity.Photo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    // 앨범(휴지통 제외) 최신순 무한스크롤
    @Query("""
        select p
        from Photo p
        where p.member.id = :memberId
          and p.deletedAt is null
          and (:cursorShotAt is null or p.shotAt < :cursorShotAt
               or (p.shotAt = :cursorShotAt and p.id < :cursorId))
        order by p.shotAt desc, p.id desc
    """)
    List<Photo> findAlbumLatest(
            @Param("memberId") Long memberId,
            @Param("cursorShotAt") LocalDateTime cursorShotAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    // 휴지통 목록 최신순 무한스크롤
    @Query("""
        select p
        from Photo p
        where p.member.id = :memberId
          and p.deletedAt is not null
          and (:cursorDeletedAt is null or p.deletedAt < :cursorDeletedAt
               or (p.deletedAt = :cursorDeletedAt and p.id < :cursorId))
        order by p.deletedAt desc, p.id desc
    """)
    List<Photo> findTrashLatest(
            @Param("memberId") Long memberId,
            @Param("cursorDeletedAt") LocalDateTime cursorDeletedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
