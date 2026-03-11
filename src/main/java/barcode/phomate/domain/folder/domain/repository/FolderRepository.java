package barcode.phomate.domain.folder.domain.repository;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.member.domain.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    // 내 폴더 목록 조회
    List<Folder> findByOwner(Member owner);

    // 내 폴더 목록 무한스크롤(createdAt 최신순)
    @Query("""
        select f
        from Folder f
        where f.owner.id = :memberId
          and (:cursorCreatedAt is null or f.createdAt < :cursorCreatedAt
               or (f.createdAt = :cursorCreatedAt and f.id < :cursorId))
        order by f.createdAt desc, f.id desc
    """)
    List<Folder> findMyFoldersCursor(
            @Param("memberId") Long memberId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    // 초대 수락한 공유 폴더 무한스크롤(createdAt 최신순)
    @Query("""
        select f
        from FolderMember fm
        join fm.folder f
        where fm.member.id = :memberId
          and fm.isAccepted = true
          and (:cursorCreatedAt is null or f.createdAt < :cursorCreatedAt
               or (f.createdAt = :cursorCreatedAt and f.id < :cursorId))
        order by f.createdAt desc, f.id desc
    """)
    List<Folder> findSharedFoldersCursor(
            @Param("memberId") Long memberId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
