package ai.conduit.chat.project;

import ai.conduit.chat.web.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Project lifecycle, scoped by {@code userId} for per-user isolation. */
@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<Project> list(String userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Project create(String userId, String name, String color) {
        return repository.save(new Project(userId, name, color));
    }

    public Project getOwnedOrThrow(String id, String userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    public Project update(String id, String userId, UpdateProjectRequest patch) {
        Project project = getOwnedOrThrow(id, userId);
        if (patch.name() != null) {
            project.setName(patch.name());
        }
        if (patch.color() != null) {
            project.setColor(patch.color());
        }
        return repository.save(project);
    }

    public void delete(String id, String userId) {
        long removed = repository.deleteByIdAndUserId(id, userId);
        if (removed == 0) {
            throw new NotFoundException("Project not found");
        }
    }
}
