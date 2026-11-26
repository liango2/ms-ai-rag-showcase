package com.example.ai.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一行流小工具：{@code MyMap.of("k1", v1, "k2", v2)} —— 直接产出一个能 return 的 Map。
 *
 * <p><b>为什么不用 JDK 自带的 Map.of()？</b>
 * <ul>
 *   <li>Map.of() <b>无序</b>：遍历顺序不保证，接口字段在页面上会跳来跳去；</li>
 *   <li>Map.of() <b>value 不允许 null</b>：我们的返回值里「模型名取不到就是 null」，一塞就抛 NPE。</li>
 * </ul>
 * 所以用 LinkedHashMap：既保序、又允许 null —— 这正好是「接口返回值」的两条刚需。
 *
 * <p>有了它，原来四五行「new LinkedHashMap + 一串 put」就能压成一行：
 * <pre>
 *   // 以前
 *   Map&lt;String, Object&gt; out = new LinkedHashMap&lt;&gt;();
 *   out.put("mode", "both"); out.put("ragMode", mode); out.put("results", results); return out;
 *   // 现在
 *   return MyMap.of("mode", "both", "ragMode", mode, "results", results);
 * </pre>
 */
public final class MyMap {

    private MyMap() {}   // 工具类，别 new 它

    /** {@code MyMap.of("ok", true, "answer", "你好")} → {@code {ok=true, answer=你好}} */
    public static Map<String, Object> of(Object... kv) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
