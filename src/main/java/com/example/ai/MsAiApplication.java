package com.example.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-ai 启动类：Spring AI 自学工程入口。
 *
 * <p>================= ★ 本工程的代码命名与风格约定（先看这里再读代码）=================
 * <ol>
 *   <li><b>自己写的类一律带 My 前缀</b>：{@code MyAiConfig / MyChatController / MyRagController /
 *       MyChunkService / MyKnowledgeLoader / MyMap}。
 *       目的：一眼区分「我写的」和「Spring / 阿里 starter jar 里的」——
 *       比如看到 {@code DashScopeChatModel} 就知道来自 jar（外面配的），
 *       看到 {@code MyAiConfig} 就知道是我装配的东西。<br>
 *       （本启动类保留 {@code MsAi*} 前缀，它是项目入口，带项目名更好认。）</li>
 *   <li><b>能一行写完的就不写三行</b>（屏幕小、读得快）：
 *       构造器交给 Lombok 的 {@code @RequiredArgsConstructor}，日志对象交给 {@code @Slf4j}，
 *       接口返回值交给 {@link com.example.ai.common.MyMap#of}。
 *       样板代码消失后，一个方法往往就是「几行参数校验 + 一行返回」。</li>
 *   <li><b>注释写「为什么」，不写「是什么」</b>：所有 ★ 标记的注释都是踩过的坑或反直觉结论，
 *       值得反复看；代码本身怎么写，看代码就够了。</li>
 * </ol>
 *
 * <p>================= 工程形态 =================
 * <ul>
 *   <li>不注册 Nacos —— 它是独立学习兴趣的服务，不需要被微服务网关发现；
 *       也省掉注册客户端那几十 MB 常驻内存（云机 2C2G，内存要省着花）。</li>
 *   <li>不打 poller / 不加 actuator —— Hello World 阶段，只留必要的东西。</li>
 *   <li>对外只有一个 nginx 转发：location /ai/ → 127.0.0.1:8086/。</li>
 * </ul>
 *
 * <p>================= 学习顺序建议 =================
 * <ol>
 *   <li>ChatModel / ChatClient 是什么关系 → {@code config/MyAiConfig.java}</li>
 *   <li>一次对话背后的 HTTP 请求长什么样 → 跑 POST /ai/chat，看返回的耗时与 token</li>
 *   <li>向量库 Embedding + 相似度检索 → {@code rag/MyKnowledgeLoader.java}（灌库）、
 *       {@code rag/MyRagController.java}（检索 + 拼 Prompt）</li>
 *   <li>没 Key 跑不起来怎么办 → 教学页 19「大模型 API Key 申请与厂商选型」</li>
 * </ol>
 */
@SpringBootApplication
public class MsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsAiApplication.class, args);
    }
}
