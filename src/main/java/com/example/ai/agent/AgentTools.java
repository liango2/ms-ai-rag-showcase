package com.example.ai.agent;

import com.example.ai.common.MyMap;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ★ Agent（智能体 / 自动工具编排）—— 让模型「自主决定调哪个、调几个、怎么串」，再汇总回答。
 * <p>
 * 和 Function Calling（ch24 ToolService）的区别，一句话：
 *   Function Calling = 「你（前端）指定工具，模型一次性调一个」；
 *   Agent = 「你把一篮子工具都给它，它面对提问<b>自己挑、自己排顺序、连续调多个</b>，再综合作答」。
 * <p>
 * 这里只负责<b>定义工具</b>（@Tool 方法），调度编排由模型在 ChatClient 内部循环完成
 * （Spring AI 的 ToolCallingChatModel 会把模型返回的 tool call 自动执行、把结果喂回，循环到模型不再调为止）。
 * <p>
 * 两个接口共用一个工具篮子（4 个工具），靠系统提示引导模型只挑相关的用——
 * 这本身就是教学点：<b>模型面对 4 个工具自主筛选</b>，看 toolCalls 就知道它实际挑了哪几个。
 * <p>
 * 每次工具被调用记进当前线程 {@code toolCalls}，controller 响应时 drain() 出来给前端展示。
 *
 * <p><b>数据源说明（本档刻意最轻，不接真实 API / 不建表）：</b>
 *   时区时间用 JDK 内置 ZoneId 现算（真实）；天气 / 订单 / 政策都是内存 Map 演示数据。
 *   教学重点是「自主编排」，不是「真数据」。将来要接真实数据，只换工具方法内部，调度不变。
 */
@Component
public class AgentTools {

    private static final ThreadLocal<List<Map<String, Object>>> toolCalls =
            ThreadLocal.withInitial(ArrayList::new);

    /** controller 调用后取出本次请求里模型实际发起的工具调用，并清理线程上下文。 */
    public static List<Map<String, Object>> drainCalls() {
        var list = toolCalls.get();
        toolCalls.remove();
        return list;
    }

    private void record(String name, Object input, Object output) {
        toolCalls.get().add(MyMap.of("name", name, "input", String.valueOf(input), "output", String.valueOf(output)));
    }

    /* ===================== ① 旅行规划工具 =====================
     * timeInCity 用 JDK 内置 ZoneId 算真实当前时间（模型本来不知道"现在"），
     * weatherInCity 用内存演示数据（不接真实天气 API，保持最轻）。 */

    private static final Map<String, String> CITY_TZ = Map.of(
            "北京", "Asia/Shanghai",
            "伦敦", "Europe/London",
            "纽约", "America/New_York",
            "东京", "Asia/Tokyo");

    private static final Map<String, String> CITY_WEATHER = Map.of(
            "北京", "晴，15°C",
            "伦敦", "小雨，8°C",
            "纽约", "多云，18°C",
            "东京", "晴，20°C");

    @Tool(name = "timeInCity", description = "返回指定城市的当前时间（含时区）。用于回答『某城市现在几点』『两地时差多少』的问题。城市限：北京/伦敦/纽约/东京。")
    public String timeInCity(String city) {
        var zoneId = CITY_TZ.get(city);
        var r = zoneId == null
                ? "没有 " + city + " 的时区数据（只支持北京/伦敦/纽约/东京）"
                : city + " 现在是 " + ZonedDateTime.now(ZoneId.of(zoneId))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（" + zoneId + "）";
        record("timeInCity", "city=" + city, r);
        return r;
    }

    @Tool(name = "weatherInCity", description = "返回指定城市的天气。用于回答『某城市天气怎么样』『穿什么合适』的问题。城市限：北京/伦敦/纽约/东京。")
    public String weatherInCity(String city) {
        var w = CITY_WEATHER.get(city);
        var r = w == null
                ? "没有 " + city + " 的天气数据（只支持北京/伦敦/纽约/东京）"
                : city + "：" + w;
        record("weatherInCity", "city=" + city, r);
        return r;
    }

    /* ===================== ② 订单售后决策工具 =====================
     * findOrder 查内存假订单，returnPolicy 返回写死政策文本 —— 都是最轻演示数据。
     * 教学点：模型「先查订单 → 再查政策 → 综合判断」，多工具连续调用 + 编排。 */

    private static final Map<String, Map<String, Object>> ORDERS = Map.of(
            "A001", Map.of("category", "电子产品", "amount", "299.00", "status", "已发货", "daysAgo", 3),
            "A002", Map.of("category", "日用品", "amount", "59.00", "status", "已签收", "daysAgo", 12),
            "A003", Map.of("category", "家电", "amount", "899.00", "status", "待发货", "daysAgo", 0));

    @Tool(name = "findOrder", description = "按订单号查询订单信息（品类/金额/状态/下单天数）。回答『这个订单能不能退』之前先调用它查订单。")
    public String findOrder(String orderId) {
        var o = ORDERS.get(orderId);
        var r = o == null
                ? "没有找到订单 " + orderId + "（可用的订单号：A001/A002/A003）"
                : "订单 " + orderId + "：" + o.get("category") + "，金额 ¥" + o.get("amount")
                  + "，" + o.get("status") + "，下单 " + o.get("daysAgo") + " 天前";
        record("findOrder", "orderId=" + orderId, r);
        return r;
    }

    @Tool(name = "returnPolicy", description = "返回本店的退货政策规则。回答『能不能退』『退多少』『要不要运费』时调用它查政策。")
    public String returnPolicy() {
        var r = "退货政策：①未发货订单可随时全额退款；②已发货/已签收订单，签收 7 天内可退，非质量问题退回需买家承担运费；③电子产品拆封后影响二次销售的不支持无理由退货；④超过 7 天未拆封仍可退，但需自理运费。";
        record("returnPolicy", "-", r);
        return r;
    }
}
