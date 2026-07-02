package ai.conduit.chat.project;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link Project}, scoped by {@code userId}. */
public interface ProjectRepository extends MongoRepository<Project, String> {

    List<Project> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Project> findByIdAndUserId(String id, String userId);

    long deleteByIdAndUserId(String id, String userId);
}
