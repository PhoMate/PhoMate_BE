package barcode.phomate.domain.edit.domain.repository;

import barcode.phomate.domain.edit.domain.entity.EditSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EditSessionRepository extends JpaRepository<EditSession, Long> {
}
