package barcode.phomate.domain.folder.domain.repository;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.member.domain.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FolderRepository extends JpaRepository<Folder, Long> {

    // 내 폴더 목록 조회
    List<Folder> findByOwner(Member owner);
}
