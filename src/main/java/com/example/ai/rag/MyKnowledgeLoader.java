package com.example.ai.rag;

import com.example.ai.common.MyMap;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * My* = 本工程「自己写的」类（命名约定见 MsAiApplication）。
 *
 * <p>知识库灌库器：把 md / txt 文档读出来 → 切段 → 灌进向量库。
 *
 * <p><b>RAG 的第一公里就是这里。</b>很多人以为 RAG 难在向量模型，其实真正影响效果的往往是这一步：
 * 文档怎么切、切多大、保留什么元数据。〈检索质量差 → 模型答非所问〉的根因八成在切分。
 *
 * <p>两个语料来源：
 * <ol>
 *   <li><b>classpath:knowledge/</b> —— 打进 jar 的内置示例文档，保证开箱就有数据可查</li>
 *   <li><b>外部目录</b>（application.yml 的 msai.rag.external-dir，默认 ./knowledge）——
 *       你的 ms-demo 学习笔记放这里，改文档不用重新打包，调 /ai/rag/reload 即可重灌</li>
 * </ol>
 *
 * <p>★ 每个通道各有一个独立 VectorStore（见 MyAiConfig）：这里对【所有】VectorStore 都灌一遍同样的文档 ——
 * embedding 向量是和模型绑定的，DashScope 生成的向量拿给 OpenAI 通道检索毫无意义，两边必须各存一份。
 *
 * <p>★ 本类的 Logger 由 Lombok 的 {@code @Slf4j} 生成（等价于手写 LoggerFactory.getLogger），构造器由
 * {@code @RequiredArgsConstructor} 生成 —— 省掉 12 行样板，读代码时直接看业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyKnowledgeLoader {

    /** Spring 会把容器里所有 VectorStore bean 收进来：key = bean 名（通道名） */
    private final Map<String, VectorStore> vectorStores;
    private final MyChunkService chunkService;

    @Value("${msai.rag.external-dir:./knowledge}")
    private String externalDir;

    @Value("${msai.rag.auto-load:true}")
    private boolean autoLoad;

    @Value("${msai.rag.load-classpath:true}")
    private boolean loadClasspath;

    /**
     * 可选注入：仅用于「启动自检 pg 向量库是否已有数据」。
     * 用 {@code required=false} 而不是 final 构造器注入：纯内存跑法（没配 spring.datasource）时
     * 容器里没有 JdbcTemplate，这里为 null，不影响启动。
     */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /** 灌库结果快照，给 /ai/rag/stats 用 */
    private volatile LoadStats lastStats;

    /**
     * 最近一次灌库的全量文档快照 —— 给 BM25 关键词检索当语料。
     * 向量检索走 VectorStore，BM25 需要「原文」而不是向量，所以这里留一份内存副本。
     * volatile 保证灌库线程写完、请求线程立刻可见。
     */
    private volatile List<Document> corpus = List.of();

    /** 是否正在灌库（/ai/rag/stats 会返回给前端，避免「查到一半」的误判） */
    private volatile boolean loading = false;

    /** 灌库专用单线程：串行执行，避免两次 reload 打架 */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ms-ai-rag-loader");
        t.setDaemon(true);
        return t;
    });

    /**
     * 服务启动后自动灌一次库。
     *
     * <p>★ 刻意的【异步】：灌库要调 Embedding API，速度由网络和对端决定（实测 7 个片段约 3 秒，
     * 上百片段轻松超过 1 分钟）。塞在启动主线程里会让服务「重启像卡死」；异步之后端口秒开，
     * RAG 边加载边可用，/ai/rag/stats 里能看到 loading 状态。
     */
    @PostConstruct
    public void init() {
        if (!autoLoad) {
            log.info("[RAG] msai.rag.auto-load=false，跳过启动灌库，需要时手动调 POST /ai/rag/reload");
            return;
        }
        // ★ 幂等自检：pg 向量库已有数据就不重复自动灌库。
        //   落盘的向量重启还在，每次启动都重灌 = 白烧免费 embedding 额度 + 启动变慢；
        //   首次空库（或没配 pg）才自动灌，之后启动秒过，手动 /ai/rag/reload 始终可强制全量重灌。
        if (pgHasData()) {
            log.info("[RAG] pg 向量库已有数据，跳过启动自动灌库（需要重灌时 POST /ai/rag/reload）");
            return;
        }
        loader.submit(() -> {
            try {
                reload();
            } catch (Exception e) {
                // ★ 灌库失败不能让服务起不来：RAG 挂了，/ai/chat 至少还能用
                log.error("[RAG] 启动灌库失败，服务继续运行（排查后调 /ai/rag/reload 重试）", e);
            }
        });
    }

    /**
     * 探测 pg 向量库是否已有数据。
     * 有数据 → true（跳过自动灌库）；没配 pg / 首次未建表 → false（按空库照常自动灌，Fail open）。
     */
    private boolean pgHasData() {
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
            return n != null && n > 0;
        } catch (Exception e) {
            // 首次启动表还没建：PgVectorStore 的 initializeSchema=true 会在后续自己建表，这里按空库处理
            log.warn("[RAG] 启动自检 pg 向量库失败（首次未建表？），按空库处理继续自动灌库");
            return false;
        }
    }

    /**
     * 重新灌库：清空现有向量 → 重新读文件、切段、写入。
     *
     * <p>★ 为什么是「全量重灌」而不是增量？删除一篇文档时我们并不知道它对应的向量是哪几条
     * （除非额外维护 id 映射）。学习笔记这种小语料（几百 KB）全量重灌成本极低，逻辑却简单可靠得多。
     */
    public synchronized LoadStats reload() {
        loading = true;
        long start = System.currentTimeMillis();
        try {
            var docs = loadDocuments();
            corpus = docs;                          // 更新 BM25 语料快照（向量库存的是向量，关键词检索要原文）

            if (vectorStores.isEmpty()) {
                log.warn("[RAG] 容器里没有任何 VectorStore bean —— 可能两个通道的 api-key 都没配");
                return lastStats = new LoadStats(docs.size(), 0, docs.size(), Map.of(), 0, LocalDateTime.now(),
                        List.of("没有可用的 VectorStore，请检查 DASHSCOPE_KEY 是否注入"));
            }

            // 每个通道单独写一遍（分别调用各自的 embedding 模型）
            var perStore = new LinkedHashMap<String, Integer>();
            var errors = new ArrayList<String>();
            vectorStores.forEach((storeName, store) -> {
                try {
                    clearStore(store);                    // 先清旧数据，否则重复 reload 会让片段成倍堆积
                    store.add(docs);
                    perStore.put(storeName, docs.size());
                    log.info("[RAG] 通道 {} 灌入 {} 条文档片段", storeName, docs.size());
                } catch (Exception e) {
                    log.error("[RAG] 通道 " + storeName + " 灌库失败", e);
                    errors.add(storeName + ": " + e.getMessage());
                }
            });

            long files = docs.stream().map(d -> String.valueOf(d.getMetadata().get("source"))).distinct().count();
            lastStats = new LoadStats(docs.size(), (int) files, docs.size(), perStore,
                    System.currentTimeMillis() - start, LocalDateTime.now(), errors);
            log.info("[RAG] 灌库完成：{} 文件 → {} 片段，耗时 {}ms", lastStats.files(), lastStats.chunks(), lastStats.elapsedMs());
            return lastStats;
        } finally {
            loading = false;                              // 不管成功失败都要复位，否则前端永远显示「加载中」
        }
    }

    public boolean isLoading() {
        return loading;
    }

    /**
     * 清空某个 store 里本应用写入的数据。
     *
     * <p>SimpleVectorStore 没有 truncate，只支持按 id / metadata 过滤删除。
     * 我们的约定是：所有文档 metadata 都带 {@code app=ms-ai} 标记，用 filter 表达式精准删掉，不误伤其它数据。
     */
    private void clearStore(VectorStore store) {
        try {
            store.delete("app == 'ms-ai'");
        } catch (Exception e) {
            // 不是所有 VectorStore 实现都支持 delete-by-filter。
            // 真上线时若这里失败，最稳妥是重启服务让内存库重建 —— 内存库反正不留持久数据。
            log.warn("[RAG] 清空旧向量失败（不支持 filter 删除？）：{}", e.getMessage());
        }
    }

    /** 读取所有语料文档并切成 Document 列表 */
    public List<Document> loadDocuments() {
        var sources = new ArrayList<SourceDoc>();

        // ① 外部目录（主要来源：你的学习笔记）
        var dir = Paths.get(externalDir);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).filter(p -> matches(p.getFileName().toString()))
                    .forEach(p -> sources.add(new SourceDoc(p.getFileName().toString(), readFile(p), "external")));
            } catch (Exception e) {
                log.error("[RAG] 读取外部目录失败：" + externalDir, e);
            }
        } else {
            log.info("[RAG] 外部知识库目录不存在：{}（跳过，不影响启动）", dir.toAbsolutePath());
        }

        // ② classpath 内置文档
        if (loadClasspath) {
            var resolver = new PathMatchingResourcePatternResolver();
            for (String pattern : new String[]{"classpath*:knowledge/**/*.md", "classpath*:knowledge/**/*.txt"}) {
                try {
                    for (var res : resolver.getResources(pattern)) {
                        sources.add(new SourceDoc(res.getFilename(), res.getContentAsString(StandardCharsets.UTF_8), "classpath"));
                    }
                } catch (IOException e) {
                    log.error("[RAG] 读取 classpath 内置知识库失败", e);
                }
            }
        }

        // ③ 切段 → Document（每个片段都打上元数据：来源、第几段、共几段）
        var counter = new AtomicInteger(0);
        return sources.stream().flatMap(src -> {
            var chunks = chunkService.split(src.content());
                          return java.util.stream.IntStream.range(0, chunks.size())
                                                           .mapToObj(i -> new Document(
                                                                   "ms-ai-" + counter.incrementAndGet(),
                                                                   chunks.get(i),
                                                                   MyMap.of(
                                                                           "app", "ms-ai",          // 删除时用来精确圈出本应用自己的数据
                                                                           "source", src.name(),    // 来源文件名：页面展示「答案来自哪篇笔记」
                                                                           "origin", src.origin(),  // external / classpath
                                                                           "chunkIndex", i,
                                                                           "chunkTotal", chunks.size()
                                                                           )
                                                           ));
                      })
                      .toList();
    }

    private static boolean matches(String filename) {
        var lower = filename.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown");
    }

    private static String readFile(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[RAG] 读取文件失败：" + p, e);
            return "";
        }
    }

    public LoadStats getLastStats() {
        return lastStats;
    }

    /** 给 BM25 关键词检索用的全量文档快照（未灌库时为空列表）。 */
    public List<Document> getCorpus() {
        return corpus;
    }

    /** 中间结构：一个源文件的内容 */
    private record SourceDoc(String name, String content, String origin) {
    }

    /** 一次灌库的结果快照（给前端展示，也是「实验闭环」的证据留存） */
    public record LoadStats(int chunks, int files, int total, Map<String, Integer> perStore,
                            long elapsedMs, LocalDateTime at, List<String> errors) {
    }
}
