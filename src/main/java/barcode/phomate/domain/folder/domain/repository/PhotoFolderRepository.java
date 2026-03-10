package barcode.phomate.domain.folder.domain.repository;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;
import barcode.phomate.domain.photo.domain.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PhotoFolderRepository extends JpaRepository<PhotoFolder, Long> {

    // 폴더 안 사진 조회(폴더 상세 조회)
    List<PhotoFolder> findByFolder(Folder folder);

    // 중복 추가 방지 (사진을 폴더에 추가할때)
    Optional<PhotoFolder> findByPhotoAndFolder(Photo photo, Folder folder);

    // 사진 삭제 시 연관 PhotoFolder 레코드 정리
    void deleteByPhoto(Photo photo);

    // 내 공유폴더에 속한 photoId 목록 조회(AI 자동 폴더 검색 시 제외용)
    @Query("""
            select pf.photo.id
            from PhotoFolder pf
            where pf.folder.owner.id = :memberId
                        and pf.folder.type = 'SHARED'
            """)
    List<Long> findSharedFolderPhotoIdsByMemberId(@Param("memberId") Long memberId);

}
