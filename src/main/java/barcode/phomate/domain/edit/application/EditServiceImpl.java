package barcode.phomate.domain.edit.application;

import barcode.phomate.domain.edit.domain.entity.EditSession;
import barcode.phomate.domain.edit.domain.entity.EditSessionStatus;
import barcode.phomate.domain.edit.domain.entity.EditSourceType;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import barcode.phomate.domain.edit.domain.repository.EditSessionRepository;
import barcode.phomate.domain.edit.domain.repository.EditVersionRepository;
import barcode.phomate.domain.member.domain.entity.Member;
import barcode.phomate.domain.member.domain.repository.MemberRepository;
import barcode.phomate.domain.post.domain.entity.Post;
import barcode.phomate.domain.post.domain.repository.PostRepository;
import barcode.phomate.global.exception.BadRequestException;
import barcode.phomate.global.exception.ForbiddenException;
import barcode.phomate.global.exception.NotFoundException;
import barcode.phomate.global.gemini.GeminiImageEditClient;
import barcode.phomate.global.s3.service.S3StorageService;
import barcode.phomate.global.util.ImageJpegUtil;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class EditServiceImpl implements EditService{

    private final PostRepository postRepository;
    private final MemberRepository memberRepository;

    private final EditSessionRepository editSessionRepository;
    private final EditVersionRepository editVersionRepository;

    private final S3StorageService s3StorageService;
    private final GeminiImageEditClient geminiImageEditClient;

    @Value("${app.cdn.base-url}")
    private String cloudFrontBaseUrl;

    // CloudFront URL에서 이미지를 bytes로 가져오는 용도
    private final WebClient downloadClient = WebClient.builder().build();

    // 1. 편집 세션 시작
    @Override
    public EditSession start(Long memberId, Long postId) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(()-> new NotFoundException("멤버를 찾을 수 없습니다."));

        Post post = postRepository.findById(postId)
                .orElseThrow(()-> new NotFoundException("게시물을 찾을 수 없습니다."));

        EditSession session = editSessionRepository.save(EditSession.builder()
                .member(member)
                .post(post)
                .build());

        // 편집 전용 복사본 생성해 jpg로 확장자 두기
        byte[] baseBytes = downloadBytes(cloudFrontUrl(post.previewKey()));
        byte[] baseJpg = toJpgOrThrow(baseBytes);

        String key0 = sessionPrefix(session.getId()) + "/vo.jpg";
        putJpg(key0, baseJpg);

        editVersionRepository.save(EditVersion.builder()
                .editSession(session)
                .versionIndex(0)
                .s3Key(key0)
                .sourceType(EditSourceType.DIRECT)
                .prompt("편집 복사본")
                .build());

        // 기본 currentIndes는 0
        return session;
    }

    // 2. 현재 상태 조회
    // 프론트 새로고침, 재접속
    @Transactional(readOnly = true)
    @Override
    public EditVersion getCurrentVersion(Long memberId, Long editSessionId) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);
        return getVersion(session, session.getCurrentIndex());
    }

    // 3. 챗봇으로 편집 -> 새 버전 생성
    @Override
    public EditVersion chatEdit(Long memberId, Long editSessionId, String prompt) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        // prompt 타입 에러나서 StringUtils 사용
        if (!StringUtils.hasText(prompt)) {
            throw new BadRequestException("prompt는 필수입니다.");
        }

        // 현재 버전
        EditVersion current = getVersion(session, session.getCurrentIndex());

        // 현재 이미지를 bytes로 다운로드하고 jpg로 통일
        byte[] currentBytes = downloadBytes(cloudFrontUrl(current.getS3Key()));
        byte[] currentInputJpg = ensureJpg(currentBytes);

        // gemini 이미지 편집 호출
        byte[] editedBytes = geminiImageEditClient.edit(currentInputJpg, prompt);

        // 결과 jpg 통일
        byte[] editedJpg = ensureJpg(editedBytes);

        // redo들 정리(현재 index 이후 버전 삭제)
        deleteRedoVersions(session);

        // 새 버전 저장
        int nextIndex = session.getCurrentIndex() + 1;
        String nextKey = sessionPrefix(session.getId()) + "/v" + nextIndex + ".jpg";
        putJpg(nextKey, editedJpg);

        EditVersion saved = editVersionRepository.save(EditVersion.builder()
                .editSession(session)
                .versionIndex(nextIndex)
                .s3Key(nextKey)
                .sourceType(EditSourceType.CHAT)
                .prompt(prompt)
                .build());

        // 포인타 이동
        session.moveTo(nextIndex);

        return saved;
    }

    // 4. 직접 편집 결과 업로드 -> 새 버전 생성
    @Override
    public EditVersion directUpload(Long memberId, Long editSessionId, MultipartFile file) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        if(file == null || file.isEmpty()) {
            throw new BadRequestException("file은 필수입니다.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BadRequestException("파일 읽기 실패");
        }

        byte[] jpg = toJpgOrThrow(bytes);

        // redo들 정리
        deleteRedoVersions(session);

        int nextIndex = session.getCurrentIndex() + 1;
        String nextKey = sessionPrefix(session.getId()) + "/v" + nextIndex + ".jpg";
        putJpg(nextKey, jpg);

        EditVersion saved = editVersionRepository.save(EditVersion.builder()
                .editSession(session)
                .versionIndex(nextIndex)
                .s3Key(nextKey)
                .sourceType(EditSourceType.DIRECT)
                .prompt("직접 편집 결과 업로드")
                .build());

        // 포인터 이동
        session.moveTo(nextIndex);

        return saved;
    }

    // 5. undo
    // currentIndex -1
    @Override
    public EditVersion undo(Long memberId, Long editSessionId) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        if (session.getCurrentIndex() <= 0) {
            throw new BadRequestException("undo 실패. 이미 최초 버전");
        }

        session.moveTo(session.getCurrentIndex() - 1);
        return getVersion(session, session.getCurrentIndex());
    }

    // 6. redo
    // currentIndex +1
    @Override
    public EditVersion redo(Long memberId, Long editSessionId) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        int maxIndex = maxVersionIndex(session);
        if (session.getCurrentIndex() >= maxIndex) {
            throw new BadRequestException("redo 실패. 이미 최신 버전");
        }

        session.moveTo(session.getCurrentIndex() + 1);
        return getVersion(session, session.getCurrentIndex());
    }

    // 7.최종 저장 + S3 업로드 및 삭제
    @Override
    public String finalizeAndCleanup(Long memberId, Long editSessionId) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        // 현재 버전
        EditVersion current = getVersion(session, session.getCurrentIndex());

        // final.jpg 저장
        byte[] bytes = downloadBytes(cloudFrontUrl(current.getS3Key()));
        byte[] finalJpg = toJpgOrThrow(bytes);

        String finalKey = sessionPrefix(session.getId()) + "/final.jpg";
        putJpg(finalKey, finalJpg);

        // 나머지 버전 S3 삭제
        List<EditVersion> versions = editVersionRepository.findByEditSessionOrderByVersionIndexAsc(session);

        List<String> deleteKeys = versions.stream()
                .filter(v -> v.getVersionIndex() != session.getCurrentIndex())
                .map(EditVersion::getS3Key)
                .toList();

        // DB 현재 버전만 남기기
        versions.stream()
                .filter(v -> v.getVersionIndex() != session.getCurrentIndex())
                .forEach(editVersionRepository::delete);

        // S3 삭제
        s3StorageService.deleteMany(deleteKeys);

        session.finalizeSession();

        // 최종 다운로드, 표시 URL
        return cloudFrontUrl(finalKey);
    }

    // 8. 취소 + S3 삭제
    @Override
    public void cancelAndCleanup(Long memberId, Long editSessionId) {
        EditSession session = getOwnedActiveSession(memberId, editSessionId);

        List<EditVersion> versions = editVersionRepository.findByEditSessionOrderByVersionIndexAsc(session);

        // S3 키
        List<String> keys = versions.stream().map(EditVersion::getS3Key).toList();

        // DB 정리
        versions.forEach(editVersionRepository::delete);

        // S3 삭제
        s3StorageService.deleteMany(keys);

        session.cancelSession();
    }



    // 헬퍼
    private EditSession getOwnedActiveSession(Long memberId, Long editSessionId) {
        EditSession session = editSessionRepository.findById(editSessionId)
                .orElseThrow(() -> new NotFoundException("EditSession을 찾을 수 없습니다."));

        if (!session.getMember().getId().equals(memberId)) {
            throw new ForbiddenException("세션 주인이 아닙니다.");
        }

        if (session.getStatus() != EditSessionStatus.ACTIVE) {
            throw new BadRequestException("EditSession이 ACTIVE 상태가 아닙니다.");
        }

        return session;
    }

    private EditVersion getVersion(EditSession session, int index) {
        return editVersionRepository.findByEditSessionAndVersionIndex(session, index)
                .orElseThrow(() -> new NotFoundException("EditVersion 찾기 실패 : index=" + index));
    }

    private int maxVersionIndex(EditSession session) {
        List<EditVersion> versions = editVersionRepository.findByEditSessionOrderByVersionIndexAsc(session);
        if (versions.isEmpty()) return 0;

        return versions.get(versions.size() - 1).getVersionIndex();
    }

     // redo들 삭제
     // 현재 index 이후 버전들을 DB & S3에서 삭제한다.
    private void deleteRedoVersions(EditSession session) {
        List<EditVersion> versions = editVersionRepository.findByEditSessionOrderByVersionIndexAsc(session);

        List<EditVersion> redo = versions.stream()
                .filter(v -> v.getVersionIndex() > session.getCurrentIndex())
                .toList();

        if (redo.isEmpty()) return;

        List<String> keys = redo.stream().map(EditVersion::getS3Key).toList();

        redo.forEach(editVersionRepository::delete);
        s3StorageService.deleteMany(keys);
    }

    private String sessionPrefix(Long sessionId) {
        return "edits/" + sessionId;
    }

    private String cloudFrontUrl(String key) {
        return cloudFrontBaseUrl + "/" + key;
    }

    private void putJpg(String key, byte[] jpgBytes) {
        // CloudFront 공개 접근 + 캐시 1년
        String cache = "public, max-age=31536000";
        s3StorageService.putBytes(key, jpgBytes, MediaType.IMAGE_JPEG_VALUE, cache);
    }

    private byte[] toJpgOrThrow(byte[] bytes) {
        try {
            return ImageJpegUtil.toJpg(bytes, 0.98f);
        } catch (Exception e) {
            throw new BadRequestException("이미지를 JPG로 바꾸는 과정이 실패했습니다.: " + e.getMessage());
        }
    }

    private byte[] downloadBytes(String url) {
        try {
            return downloadClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            throw new BadRequestException("이미지 다운로드 실패 : url=" + url);
        }
    }
    private byte[] ensureJpg(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            throw new BadRequestException("이미지 바이트가 비어있습니다.");
        }

        // JPEG magic number: FF D8 FF
        boolean isJpeg = (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF);
        if (isJpeg) return bytes;

        return toJpgOrThrow(bytes);
    }

}
