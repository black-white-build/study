package com.heartpilot.repository;

import com.heartpilot.domain.AiConversation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<AiConversation, Long> {
    Page<AiConversation> findByUserIdAndArchivedFalse(Long userId, Pageable pageable);

    Optional<AiConversation> findByIdAndUserId(Long id, Long userId);
}
