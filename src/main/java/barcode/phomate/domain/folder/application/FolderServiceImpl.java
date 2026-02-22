package barcode.phomate.domain.folder.application;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderType;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;
import barcode.phomate.domain.folder.domain.repository.FolderMemberRepository;
import barcode.phomate.domain.folder.domain.repository.FolderRepository;
import barcode.phomate.domain.folder.domain.repository.PhotoFolderRepository;
import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderUpdateRequestDTO;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.s3.application.S3DeleteAsyncService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional
public class FolderServiceImpl implements FolderService {

    private final FolderRepository folderRepository;
    private final FolderMemberRepository folderMemberRepository;
    private final PhotoFolderRepository photoFolderRepository;
    private final MemberRepository memberRepository;
    private final S3DeleteAsyncService s3DeleteAsyncService;
    private final EmbeddingAsyncService embeddingAsyncService;
    private final AfterCommitExecutor afterCommitExecutor;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    // 폴더 생성
    @Override
    public FolderResponseDTO createFolder(Long memberId, FolderCreateRequestDTO request) {

        // 중복 코드 양을 줄이기 위해 헬퍼 메서드 사용
        Member member = findMember(memberId);

        Folder folder = folderRepository.save(Folder.builder()
                .folderName(request.folderName())
                .type(request.type())
                .owner(member)
                .build());

        return FolderResponseDTO.from(folder);
    }

    // 폴더 목록 조회
    @Override
    @Transactional(readOnly = true)
    public List<FolderResponseDTO> getFolderList(Long memberId) {

        Member member = findMember(memberId);

        // 내가 만든 폴더
        List<Folder> myFolder = folderRepository.findByOwner(member);

        // 내가 초대 수락한 공유 폴더
        List<Folder> sharedFolder = folderMemberRepository
                .findByMemberAndIsAcceptedTrue(member)
                .stream()
                .map(fm -> fm.getFolder())
                .toList();

        // 내가 만든 폴더 + 내가 초대 수락한 공유 폴더 합쳐서 목록 반환
        return Stream.concat(myFolder.stream(), sharedFolder.stream())
                .map(FolderResponseDTO::from)
                .toList();
    }

    // 폴더 상세 조회
    @Override
    @Transactional(readOnly = true)
    public FolderDetailResponseDTO getFolderDetail(Long memberId, Long folderId) {

        Member member = findMember(memberId);
        Folder folder = findFolder(folderId);
        checkReadAccess(member, folder);

        List<PhotoFolder> photoFolders = photoFolderRepository.findByFolder(folder);
        return FolderDetailResponseDTO.from(folder, photoFolders, cloudFrontBaseUrl);
    }

    // 폴더 정보(이름) 수정
    // owner만 수정 가능
    @Override
    public void updateFolder(Long memberId, Long folderId, FolderUpdateRequestDTO request) {

        Folder folder = findFolder(folderId);
        checkOwner(memberId, folder);

        folder.updateName(request.folderName());
    }

    // 폴더 삭제
    // owner만 삭제 가능
    @Override
    public void deleteFolder(Long memberId, Long folderId) {

        Folder folder = findFolder(folderId);
        checkOwner(memberId, folder);

        // 공유 폴더일 경우 -> S3 + 벡터DB에서 삭제
        if(folder.getType() == FolderType.SHARED) {
            // 공유 폴더에 속한 photo들 꺼내기
            List<Photo> photos = photoFolderRepository.findByFolder(folder)
                    .stream()
                    .map(PhotoFolder::getPhoto)
                    .toList();

            // DB commit 완료 후에, S3 + 벡터 DB 삭제
            afterCommitExecutor.run(()-> {
                for(Photo photo : photos) {

                    // S3에서 이미지 파일 3개 삭제 (비동기 처리)
                    s3DeleteAsyncService.deletePhotoImages(
                            photo.getId(),
                            photo.getOriginalKey(),
                            photo.getThumbnailKey(),
                            photo.getPreviewKey()
                    );
                    // 벡터 DB에서 벡터값 삭제 (비동기 처리)
                    embeddingAsyncService.deletePhotoVector(photo.getId());
                }
            });
        }

        // DB에서 folder + PhotoFolder 매핑 삭제
        folderRepository.delete(folder);
    }

    // -------------------------헬퍼 메서드-------------------------------------------------

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(()-> new NotFoundException("회원을 찾을 수 없습니다."));
    }

    private Folder findFolder(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(()-> new NotFoundException("폴더를 찾을 수 없습니다."));
    }

    private void checkOwner(Long memberId, Folder folder) {
        if(!folder.getOwner().getId().equals(memberId)) {
            throw new ForbiddenException("폴더 소유자가 아닙니다.");
        }
    }

    private void checkReadAccess(Member member, Folder folder) {
        // 소유자거나 초대를 수락한 멤버면 조회 가능
        boolean isOwner = folder.getOwner().getId().equals(member.getId());
        boolean isMember = folderMemberRepository
                .findByMemberAndFolder(member, folder) // FolderMember에 있는지 확인
                .map(fm -> fm.isAccepted()) // 있으면 isAccepted 값 꺼내기
                .orElse(false); // 없으면 false(애초에 초대하지 않은 사람임)

        if(!isOwner && !isMember) {
            throw new ForbiddenException("조회 권한이 없습니다.");
        }
    }
}
