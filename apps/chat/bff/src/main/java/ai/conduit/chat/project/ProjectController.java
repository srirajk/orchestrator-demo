package ai.conduit.chat.project;

import ai.conduit.chat.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Project CRUD. */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projectService, CurrentUser currentUser) {
        this.projectService = projectService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ProjectDto> list() {
        return projectService.list(currentUser.id()).stream()
                .map(ProjectDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto create(@Valid @RequestBody CreateProjectRequest body) {
        return ProjectDto.from(projectService.create(currentUser.id(), body.name(), body.color()));
    }

    @GetMapping("/{id}")
    public ProjectDto detail(@PathVariable String id) {
        return ProjectDto.from(projectService.getOwnedOrThrow(id, currentUser.id()));
    }

    @PatchMapping("/{id}")
    public ProjectDto update(@PathVariable String id, @RequestBody UpdateProjectRequest body) {
        return ProjectDto.from(projectService.update(id, currentUser.id(), body));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> delete(@PathVariable String id) {
        projectService.delete(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}
