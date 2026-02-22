package barcode.phomate.domain.folder.domain.repository;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderMember;
import barcode.phomate.domain.member.domain.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FolderMemberRepository extends JpaRepository<FolderMember, Long> {

    // 특정 폴더 멤버 전체 조회(공유 폴더 권한 조회)
    List<FolderMember> findByFolder(Folder folder);

    // 특정 멤버 + 폴더 조합 조회(중복 초대 방지, 권한 변경, 초대 수락)
    Optional<FolderMember> findByMemberAndFolder(Member member, Folder folder);

    // 내가 받은 대기 중인 초대 목록(공유 폴더 초대 여부 조회)
    List<FolderMember> findByMemberAndIsAcceptedFalse(Member member);
}
