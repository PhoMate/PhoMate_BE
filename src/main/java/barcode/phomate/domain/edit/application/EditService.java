package barcode.phomate.domain.edit.application;

import barcode.phomate.domain.edit.domain.entity.EditSession;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import org.springframework.web.multipart.MultipartFile;

public interface EditService {

    EditSession start(Long memberId, Long postId);

    EditVersion getCurrentVersion(Long memberId, Long editSessionId);

    EditVersion chatEdit(Long memberId, Long editSessionId, String prompt);

    EditVersion directUpload(Long memberId, Long editSessionId, MultipartFile file);

    EditVersion undo(Long memberId, Long editSessionId);

    EditVersion redo(Long memberId, Long editSessionId);

    String finalizeAndCleanup(Long memberId, Long editSessionId);

    void cancelAndCleanup(Long memberId, Long editSessionId);

}


