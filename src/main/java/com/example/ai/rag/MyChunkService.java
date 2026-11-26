package com.example.ai.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * My* = 本工程「自己写的」类（命名约定见 MsAiApplication）。
 *
 * <p>中文友好的文本切分器 —— RAG 的「第一公里」。
 *
 * <p><b>为什么不用 Spring AI 自带的 TokenTextSplitter？</b>
 * <ul>
 *   <li>它按 OpenAI 的 tokenizer 分词，对中文效果一般（一个汉字常横跨多个 token），还要拖进词典依赖；</li>
 *   <li>我们的文档全是中文技术笔记（Markdown），「空行分段 → 长段滑窗」更符合直觉，
 *       零依赖、逻辑全看得见 —— Hello World 阶段，可读性 &gt; 玄学。</li>
 * </ul>
 *
 * <p>后续想升级成按 Markdown 标题层级切（# ## ### 各自成 chunk），改 {@link #split(String)} 即可，上层不用动。
 */
@Service
public class MyChunkService {

    /** 单段最大字符数（可由 application.yml 的 msai.rag.chunk-size 覆盖） */
    @Value("${msai.rag.chunk-size:500}")
    private int chunkSize;

    /** 相邻段重叠字符数（msai.rag.chunk-overlap）—— 防止一句话被切两半导致两边语义都不完整 */
    @Value("${msai.rag.chunk-overlap:80}")
    private int overlap;

    /**
     * 把一篇文档切成若干段。
     *
     * <p>策略三步：① 按空行拆自然段 → ② 短段攒够 300 字再切（避免一堆碎渣拉低命中率）→ ③ 超长段滑窗切分。
     *
     * @param text 原文
     * @return 切好的片段列表（原文非空时至少 1 条）
     */
    public List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        // 统一换行符，避免 Windows/Linux 换行混用时切错位置
        var normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        var chunks = new ArrayList<String>();
        var buffer = new StringBuilder();

        for (String para : normalized.split("\\n\\s*\\n")) {          // ① 空行分段
            var trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            buffer.append(trimmed).append("\n\n");                     // ② 攒段落
            if (buffer.length() >= 300) flush(buffer, chunks);
        }
        if (buffer.length() > 0) flush(buffer, chunks);               // 别忘了最后那点零头
        return chunks;
    }

    /** 把 buffer 里的一段文本落到结果里：短的直接收，长的滑窗切（③）。 */
    private void flush(StringBuilder buffer, List<String> out) {
        var text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isEmpty()) return;
        if (text.length() <= chunkSize) {                             // 够短，整段收
            out.add(text);
            return;
        }
        int step = Math.max(1, chunkSize - overlap);                  // 滑窗：每步前进 chunkSize-overlap，相邻窗口重叠 overlap 个字符
        for (int i = 0; i < text.length(); i += step) {
            out.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
    }
}
