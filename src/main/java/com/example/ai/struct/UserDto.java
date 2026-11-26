package com.example.ai.struct;

import java.util.List;

/**
 * ★ 结构化输出演示 DTO ① —— 单个对象。
 * <p>
 * 让模型把一句自然语言解析成这个 record：姓名 / 年龄 / 城市 / 爱好列表。
 * record 字段即"契约"：模型输出会按这些字段约束成 JSON，框架自动反序列化。
 */
public record UserDto(String name, Integer age, String city, List<String> hobbies) {
}
