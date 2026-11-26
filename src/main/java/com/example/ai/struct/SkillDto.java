package com.example.ai.struct;

/**
 * ★ 结构化输出演示 DTO ② —— 列表里的元素。
 * <p>
 * 从一段文字里提取多个"技能"：技能名 + 熟练度。配合
 * {@code ParameterizedTypeReference<List<SkillDto>>()} 拿回对象集合。
 */
public record SkillDto(String name, String level) {
}
