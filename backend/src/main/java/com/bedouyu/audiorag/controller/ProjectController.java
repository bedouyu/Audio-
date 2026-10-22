package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.model.dto.*;

import com.bedouyu.audiorag.service.AuthService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.ProjectDeletionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final AuthService authService;
    private final AudioRagRepository repository;
    private final ProjectDeletionService deletion;

    public ProjectController(AuthService authService, AudioRagRepository repository, ProjectDeletionService deletion) {
        this.authService = authService;
        this.repository = repository;
        this.deletion = deletion;
    }

    @GetMapping
    public Map<String, List<Map<String, Object>>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = userId(authorization);
        return Map.of("projects", repository.listProjects(userId));
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProjectRequest request) {
        return repository.ensureProject(userId(authorization), request.topic());
    }

    @DeleteMapping("/{projectId}")
    public Map<String, Object> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId) {
        deletion.delete(userId(authorization), projectId);
        return Map.of("deleted", true);
    }

    @GetMapping("/{projectId}/records")
    public Map<String, List<Map<String, Object>>> records(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId) {
        return Map.of("records", repository.projectRecords(userId(authorization), projectId));
    }

    private long userId(String authorization) {
        return ((Number) authService.requireUser(authorization).get("id")).longValue();
    }

}
