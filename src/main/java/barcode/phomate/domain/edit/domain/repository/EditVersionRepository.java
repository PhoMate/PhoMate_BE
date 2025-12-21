package barcode.phomate.domain.edit.domain.repository;

import barcode.phomate.domain.edit.domain.entity.EditSession;
import barcode.phomate.domain.edit.domain.entity.EditVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EditVersionRepository extends JpaRepository<EditVersion, Long> {

    List<EditVersion> findByEditSessionOrderByVersionIndexAsc(EditSession session);

    Optional<EditVersion> findByEditSessionAndVersionIndex(EditSession session, int versionIndex);
}
