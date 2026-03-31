package barcode.phomate.domain.folder.application;

import barcode.phomate.domain.folder.domain.entity.Folder;
import barcode.phomate.domain.folder.domain.entity.FolderMember;
import barcode.phomate.domain.folder.domain.entity.FolderRole;
import barcode.phomate.domain.folder.domain.entity.FolderType;
import barcode.phomate.domain.folder.domain.entity.PhotoFolder;
import barcode.phomate.domain.folder.domain.repository.FolderMemberRepository;
import barcode.phomate.domain.folder.domain.repository.FolderRepository;
import barcode.phomate.domain.folder.domain.repository.PhotoFolderRepository;
import barcode.phomate.domain.folder.dto.FolderCreateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderDetailResponseDTO;
import barcode.phomate.domain.folder.dto.FolderFeedResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationReplyRequestDTO;
import barcode.phomate.domain.folder.dto.FolderInvitationResponseDTO;
import barcode.phomate.domain.folder.dto.FolderInviteRequestDTO;
import barcode.phomate.domain.folder.dto.FolderMemberRoleResponseDTO;
import barcode.phomate.domain.folder.dto.FolderResponseDTO;
import barcode.phomate.domain.folder.dto.FolderRoleUpdateRequestDTO;
import barcode.phomate.domain.folder.dto.FolderUpdateRequestDTO;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.photo.domain.entity.Photo;
import barcode.phomate.domain.photo.dto.PhotoFeedResponseDTO;
import barcode.phomate.domain.photo.dto.PhotoResponseDTO;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.fastapi.application.EmbeddingAsyncService;
import barcode.phomate.global.s3.application.S3DeleteAsyncService;
import barcode.phomate.global.tx.AfterCommitExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
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

    // -------------1. 수동 폴더 시작--------------
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
    // -------------수동 폴더 끝--------------

    // -------------2. 공유 폴더 시작--------------
    // 공유 폴더 초대
    @Override
    public void inviteMember(Long requestMemberId, Long folderId, FolderInviteRequestDTO request) {

        Member requester = findMember(requestMemberId);
        Folder folder = findFolder(folderId);

        // 공유 폴더인지 확인
        if(folder.getType() != FolderType.SHARED) {
            throw new ForbiddenException("공유 폴더에만 멤버를 초대할 수 있습니다.");
        }

        // 요청자가 ADMIN인지 확인
        checkAdminRole(requester, folder);

        Member targetMember = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new NotFoundException("가입되지 않은 사용자입니다."));

        // 자기 자신 초대 방지
        if(requestMemberId.equals(targetMember.getId())) {
            throw new ForbiddenException("자기 자신을 초대할 수 없습니다.");
        }

        // 이미 초대된 멤버인지 확인(수락 여부랑은 상관 x)
        folderMemberRepository.findByMemberAndFolder(targetMember, folder).ifPresent(fm -> {
            throw new ForbiddenException("이미 초대된 멤버입니다.");
        });

        // ADMIN 권한은 초대로 부여 불가
        if(request.role() == FolderRole.ADMIN) {
            throw new ForbiddenException("ADMIN 권한은 초대로 부여할 수 없습니다.");
        }

        folderMemberRepository.save(FolderMember.builder()
                .member(targetMember)
                .folder(folder)
                .role(request.role())
                .isAccepted(false)
                .build());
    }

    // 공유 폴더 초대 여부 조회
    @Override
    public FolderInvitationResponseDTO getInvitation(Long memberId, Long folderId) {

        Member member = findMember(memberId);
        Folder folder = findFolder(folderId);

        FolderMember folderMember = folderMemberRepository.findByMemberAndFolder(member, folder)
                .filter(fm -> !fm.isAccepted())
                        .orElseThrow(() -> new NotFoundException("대기 중인 초대가 없습니다."));

        return new FolderInvitationResponseDTO(
                folderMember.getId(),
                folder.getId(),
                folder.getFolderName(),
                folderMember.getRole(),
                folderMember.isAccepted()
        );
    }

    // 공유 폴더 초대 수락/거절
    @Override
    public void replyInvitation(Long memberId, Long folderId, FolderInvitationReplyRequestDTO request) {

        Member member = findMember(memberId);
        Folder folder = findFolder(folderId);

        FolderMember folderMember = folderMemberRepository.findByMemberAndFolder(member, folder)
                .filter(fm -> !fm.isAccepted())
                .orElseThrow(() -> new NotFoundException("대기 중인 초대가 없습니다."));

        if(request.accepted()) {
            folderMember.acceptInvite();
        } else {
            folderMemberRepository.delete(folderMember);
        }
    }

    // 공유 폴더 권한 조회
    @Override
    public FolderMemberRoleResponseDTO getMemberRole(Long requestMemberId, Long folderId, Long targetMemberId) {

        Member requester = findMember(requestMemberId);
        Folder folder = findFolder(folderId);
        Member targetMember = findMember(targetMemberId);

        // 본인 권한 조회거나 ADMIN이면 허용
        boolean isSelf = requestMemberId.equals(targetMemberId);
        boolean isAdmin = isAdminRole(requester, folder);

        if(!isSelf && !isAdmin) {
            throw new ForbiddenException("권한 조회 권한이 없습니다.");
        }

        FolderMember folderMember = folderMemberRepository.findByMemberAndFolder(targetMember, folder)
                .orElseThrow(() -> new NotFoundException("해당 멤버가 공유 폴더에 속해 있지 않습니다."));

        return new FolderMemberRoleResponseDTO(
                targetMember.getId(),
                targetMember.getNickname(),
                folderMember.getRole()
        );
    }

    @Override
    public FolderFeedResponseDTO getFolderFeed(Long memberId, LocalDateTime cursorCreatedAt, Long cursorId, int size) {

        // size+1개 조회해서 다음 페이지 있는지 확인
        PageRequest pageable = PageRequest.of(0, size + 1);

        // 내 폴더 + 공유 폴더 각각 커서 조회
        List<Folder> myFolders = folderRepository.findMyFoldersCursor(
                memberId, cursorCreatedAt, cursorId, pageable);
        List<Folder> sharedFolders = folderRepository.findSharedFoldersCursor(
                memberId, cursorCreatedAt, cursorId, pageable);

        // 합치고 createdAt desc, id desc 정렬
        List<Folder> merged = Stream.concat(myFolders.stream(), sharedFolders.stream())
                .collect(Collectors.toMap(
                        Folder::getId,
                        f -> f,
                        (existing, duplicate) -> existing  // 중복이면 기존 것 유지
                ))
                .values()
                .stream()
                .sorted(Comparator.comparing(Folder::getCreatedAt).reversed()
                        .thenComparing(Comparator.comparing(Folder::getId).reversed()))
                .toList();

        boolean hasNext = merged.size() > size;
        List<Folder> paged = hasNext ? merged.subList(0, size) : merged;

        if (paged.isEmpty()) return FolderFeedResponseDTO.empty();

        // 마지막 항목 커서로 설정
        Folder last = paged.get(paged.size() - 1);
        FolderFeedResponseDTO.Cursor nextCursor = hasNext
                ? new FolderFeedResponseDTO.Cursor(last.getCreatedAt(), last.getId())
                : null;

        List<FolderResponseDTO> items = paged.stream()
                .map(FolderResponseDTO::from)
                .toList();

        return FolderFeedResponseDTO.of(items, nextCursor, hasNext);
    }

    @Override
    public PhotoFeedResponseDTO getFolderPhotoFeed(Long memberId, Long folderId, LocalDateTime cursorShotAt, Long cursorId, int size) {

        Member member = findMember(memberId);
        Folder folder = findFolder(folderId);

        // 접근 권한 확인 (소유자 or 초대 수락한 멤버)
        checkReadAccess(member, folder);

        int pageSize = Math.min(Math.max(size, 1), 50);
        PageRequest pageable = PageRequest.of(0, pageSize);

        List<PhotoFolder> photoFolders = photoFolderRepository.findByFolderCursor(
                folderId, cursorShotAt, cursorId, pageable);

        if (photoFolders.isEmpty()) return PhotoFeedResponseDTO.empty();

        List<PhotoResponseDTO> items = photoFolders.stream()
                .map(pf -> PhotoResponseDTO.of(
                        pf.getPhoto().getId(),
                        cloudFrontBaseUrl + "/" + pf.getPhoto().getThumbnailKey(),
                        cloudFrontBaseUrl + "/" + pf.getPhoto().getPreviewKey(),
                        pf.getPhoto().getShotAt()
                ))
                .toList();

        Photo last = photoFolders.get(photoFolders.size() - 1).getPhoto();
        PhotoFeedResponseDTO.Cursor nextCursor =
                PhotoFeedResponseDTO.Cursor.latest(last.getShotAt().toString(), last.getId());

        boolean hasNext = photoFolders.size() == pageSize;
        return PhotoFeedResponseDTO.of(items, nextCursor, hasNext);
    }

    // 공유 폴더 권한 부여/변경
    @Override
    public void updateMemberRole(Long requestMemberId, Long folderId, Long targetMemberId, FolderRoleUpdateRequestDTO request) {

        Member requester = findMember(requestMemberId);
        Folder folder = findFolder(folderId);
        Member targetMember = findMember(targetMemberId);

        // 요청자가 ADMIN인지 확인
        checkAdminRole(requester, folder);

        // 변경할 권한이 ADMIN이면 불가
        if(request.role() == FolderRole.ADMIN) {
            throw new ForbiddenException("ADMIN 권한은 부여할 수 없습니다.");
        }

        FolderMember folderMember = folderMemberRepository.findByMemberAndFolder(targetMember, folder)
                .orElseThrow(() -> new NotFoundException("해당 멤버가 공유 폴더에 속해 있지 않습니다."));

        // 대상이 ADMIN이면 변경 불가(폴더 생성자니까)
        if(folderMember.getRole() == FolderRole.ADMIN) {
            throw new ForbiddenException("ADMIN 권한은 변경할 수 없습니다.");
        }

        folderMember.updateRole(request.role());
    }

    // -------------2. 공유 폴더 끝--------------

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

    private boolean isAdminRole(Member member, Folder folder) {
        // 폴더 소유자(생성자) : ADMIN
        if(folder.getOwner().getId().equals(member.getId())) {
            return true;
        }
        return folderMemberRepository.findByMemberAndFolder(member, folder)
                .map(fm -> fm.getRole() == FolderRole.ADMIN)
                .orElse(false);
    }

    private void checkAdminRole(Member member, Folder folder) {
        if(!isAdminRole(member, folder)) {
            throw new ForbiddenException("ADMIN 권한이 없습니다.");
        }
    }
}
