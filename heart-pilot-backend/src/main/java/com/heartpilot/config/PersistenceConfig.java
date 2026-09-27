package com.heartpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 持久层（JPA）配置类。
 * 开启 @EnableJpaAuditing，使 BaseEntity 上的 @CreatedDate / @LastModifiedDate 注解生效，
 * 自动填充创建时间与更新时间。
 */
@Configuration
@EnableJpaAuditing
public class PersistenceConfig {}
