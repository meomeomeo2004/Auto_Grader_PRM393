package com.example.grader.repository;

import com.example.grader.entity.AiSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSettingRepository extends JpaRepository<AiSetting, Long> {
}
