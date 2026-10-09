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

/**
 * 用户信息与关系档案 Controller，路径前缀 /users/me。 负责当前登录用户的基础信息查询/更新（昵称、情绪状态、头像），
 * 以及一对一关系档案（RelationshipProfile）的查询与保存。 所有接口均通过 CurrentUser 取当前用户 ID，仅能操作自己的数据。
 */
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

    /** GET /users/me —— 获取当前登录用户的基础信息。 */
    @GetMapping
    public UserDtos.UserResponse me() {
        return UserDtos.UserResponse.from(currentUser());
    }

    /** PATCH /users/me —— 局部更新用户基础信息。 仅更新传入的非空字段（昵称会 trim），未传字段保持不变。 */
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

    /** GET /users/me/relationship-profile —— 获取当前用户的关系档案。 不存在时自动创建一条空白档案返回，保证前端总能拿到一个可编辑对象。 */
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

    /** PUT /users/me/relationship-profile —— 全量保存关系档案。 档案与用户一对一，存在则覆盖更新，不存在则新建。 */
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

    /** 按当前用户 ID 加载 AppUser，不存在抛 404 */
    private AppUser currentUser() {
        return users.findById(current.id()).orElseThrow(() -> ApiException.notFound("用户不存在"));
    }
}
