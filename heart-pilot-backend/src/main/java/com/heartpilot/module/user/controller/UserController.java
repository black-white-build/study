package com.heartpilot.module.user.controller;

import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.user.dto.UserDtos;
import com.heartpilot.module.user.entity.AppUser;
import com.heartpilot.module.user.entity.RelationshipProfile;
import com.heartpilot.module.user.repository.AppUserRepository;
import com.heartpilot.module.user.repository.ProfileRepository;
import com.heartpilot.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me")
public class UserController {
    private final CurrentUser current;
    private final AppUserRepository users;
    private final ProfileRepository profiles;

    public UserController(
            CurrentUser current, AppUserRepository users, ProfileRepository profiles) {
        this.current = current;
        this.users = users;
        this.profiles = profiles;
    }

    @GetMapping
    public UserDtos.UserResponse me() {
        return UserDtos.UserResponse.from(currentUser());
    }

    @PatchMapping
    @Transactional
    public UserDtos.UserResponse update(@Valid @RequestBody UserDtos.UpdateUserRequest request) {
        AppUser user = currentUser();
        if (request.nickname() != null && !request.nickname().isBlank())
            user.setNickname(request.nickname().trim());
        if (request.emotionStatus() != null) user.setEmotionStatus(request.emotionStatus());
        if (request.avatarUrl() != null) user.setAvatarUrl(request.avatarUrl());
        return UserDtos.UserResponse.from(user);
    }

    @GetMapping("/relationship-profile")
    public UserDtos.ProfileResponse profile() {
        RelationshipProfile profile =
                profiles.findByUserId(current.id())
                        .orElseGet(
                                () -> {
                                    RelationshipProfile created = new RelationshipProfile();
                                    created.setUserId(current.id());
                                    return profiles.save(created);
                                });
        return UserDtos.ProfileResponse.from(profile);
    }

    @PutMapping("/relationship-profile")
    @Transactional
    public UserDtos.ProfileResponse profile(@Valid @RequestBody UserDtos.ProfileRequest request) {
        RelationshipProfile profile =
                profiles.findByUserId(current.id()).orElseGet(RelationshipProfile::new);
        profile.setUserId(current.id());
        profile.setRelationshipStatus(request.relationshipStatus());
        profile.setRelationshipMonths(request.relationshipMonths());
        profile.setCommunicationStyle(request.communicationStyle());
        profile.setConcerns(request.concerns());
        profile.setPreferences(request.preferences());
        profile.setBoundaries(request.boundaries());
        return UserDtos.ProfileResponse.from(profiles.save(profile));
    }

    private AppUser currentUser() {
        return users.findById(current.id()).orElseThrow(() -> ApiException.notFound("用户不存在"));
    }
}
