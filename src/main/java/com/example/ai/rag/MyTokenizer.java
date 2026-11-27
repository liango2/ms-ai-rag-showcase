package com.example.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 零依赖分词器：中文 bigram（相邻两字）+ 英文/数字整词。
 *
 * <p>为什么不用 IK / Lucene？本项目一贯风格是「看得见、零依赖」。中文技术笔记里真正有区分度的
 * 往往是「向量」「检索」「pgvector」这类词，bigram 已经足够把「向量检索」拆成
 * [向量, 量检, 检索] 并与查询对齐；引一整套分词框架反而把 BM25 的教学价值淹没了。
 *
 * <p>英文/数字走整词并转小写（DashScope / dashscope 视为同一个 token），避免大小写把 IDF 打乱。
 */
public final class MyTokenizer {

    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9]+");

    private MyTokenizer() {}

    /** 返回 token 列表（允许重复，BM25 的 tf 就靠它统计）。空白与标点被丢弃。 */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();

        var out = new ArrayList<String>();
        var lower = text.toLowerCase(Locale.ROOT);   // 英文统一小写；中文不受影响
        var m = WORD.matcher(lower);
        int last = 0;
        while (m.find()) {
            addBigrams(text.substring(last, m.start()), out); // 英文词之前的中文片段
            out.add(m.group());                               // 英文/数字整词
            last = m.end();
        }
        addBigrams(text.substring(last), out);                // 末尾剩下的中文片段
        return out;
    }

    /** 中文相邻两字成词；非 CJK 字符跳过（标点、空白直接丢掉）。 */
    private static void addBigrams(String segment, List<String> out) {
        var chars = segment.toCharArray();
        for (int i = 0; i + 1 < chars.length; i++) {
            char a = chars[i], b = chars[i + 1];
            if (isCjk(a) && isCjk(b)) out.add("" + a + b);
        }
    }

    /** 基本汉字区（覆盖技术笔记里的常用字，够了）。 */
    private static boolean isCjk(char c) {
        return c >= 0x4e00 && c <= 0x9fff;
    }
}
