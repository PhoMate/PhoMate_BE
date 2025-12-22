package barcode.phomate.domain.member.domain.repository;

import barcode.phomate.domain.member.domain.entity.SocialLogin;
import barcode.phomate.domain.member.domain.entity.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SocialLoginRepository extends JpaRepository<SocialLogin, Long> {
    Optional<SocialLogin> findByProviderAndProviderId(SocialProvider provider, String providerId);
}
