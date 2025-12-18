package barcode.phomate.domain.member.domain.repository;

import barcode.phomate.domain.member.domain.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
