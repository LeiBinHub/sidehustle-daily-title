package com.sidehustle.daily.report;

import com.sidehustle.daily.config.AppConfig;
import com.sidehustle.daily.store.Store;
import com.sidehustle.daily.store.Store.OpportunityRow;
import com.sidehustle.daily.store.Store.PainCluster;
import com.sidehustle.daily.store.Store.SourceStat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders the Markdown daily report. */
public final class ReportGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppConfig cfg;
    private final Store store;

    public ReportGenerator(AppConfig cfg, Store store) {
        this.cfg = cfg;
        this.store = store;
    }

    /**
     * @param runStartedAt epoch second when this run began (new opportunities = classified since then)
     * @return path of the written report
     */
    public Path write(long runStartedAt) throws Exception {
        long now = Instant.now().getEpochSecond();
        long recurrenceSince = now - (long) cfg.recurrenceWindowDays * 86400;

        List<OpportunityRow> fresh = store.opportunitiesClassifiedSince(runStartedAt, cfg.minScoreToReport);
        List<PainCluster> clusters = store.recurringPains(recurrenceSince, cfg.recurrenceThreshold);
        List<SourceStat> stats = store.sourceStatsSince(runStartedAt);
        int classified = store.countClassifiedSince(runStartedAt, false);
        int opps = store.countClassifiedSince(runStartedAt, true);

        String date = LocalDate.now(ZONE).format(DATE);
        StringBuilder sb = new StringBuilder();
        sb.append("# 商机日报 ").append(date).append("\n\n");
        sb.append("> 生成时间 ").append(fmt(now)).append("（Asia/Shanghai）。")
          .append("本次新分类 ").append(classified).append(" 条，其中判定为商机 ").append(opps)
          .append(" 条，评分 ≥ ").append(cfg.minScoreToReport).append(" 的 ").append(fresh.size()).append(" 条列在下方。\n\n");

        // --- recurring pains ---
        sb.append("## 一、反复出现的痛点（近 ").append(cfg.recurrenceWindowDays).append(" 天内 ≥ ")
          .append(cfg.recurrenceThreshold).append(" 次）\n\n");
        if (clusters.isEmpty()) {
            sb.append("_暂无达到阈值的重复痛点。数据积累 1–2 周后这里才会有内容。_\n\n");
        } else {
            sb.append("| 痛点标签 | 次数 | 社区数 | 平均分 | 代表帖 |\n|---|---|---|---|---|\n");
            for (PainCluster c : clusters) {
                sb.append("| `").append(c.painKey()).append("` | ")
                  .append(c.count()).append(" | ")
                  .append(c.communities()).append(" | ")
                  .append(String.format("%.1f", c.avgScore())).append(" | ")
                  .append(link(c.sampleTitle(), c.sampleUrl())).append(" |\n");
            }
            sb.append("\n同一标签同时满足 **次数 ≥ ").append(cfg.recurrenceThreshold)
              .append(" 且社区数 ≥ 2** 时才值得进入选题漏斗。\n\n");
        }

        // --- fresh opportunities ---
        sb.append("## 二、本次新发现的商机（按评分排序）\n\n");
        if (fresh.isEmpty()) {
            sb.append("_本次没有评分 ≥ ").append(cfg.minScoreToReport).append(" 的新商机。_\n\n");
        } else {
            int i = 1;
            for (OpportunityRow r : fresh) {
                var o = r.opp();
                sb.append("### ").append(i++).append(". ").append(stars(o.score())).append(" ")
                  .append(escape(r.title())).append("\n\n");
                sb.append("- 来源：").append(o.source()).append(" / ").append(r.community())
                  .append("　评论 ").append(r.numComments()).append("　分数 ").append(r.postScore())
                  .append("　发布 ").append(fmt(r.createdAt())).append("　[原帖](").append(r.url()).append(")\n");
                sb.append("- 痛点标签：`").append(o.painKey()).append("`　置信度 ")
                  .append(String.format("%.2f", o.confidence())).append("\n");
                sb.append("- 痛点：").append(o.painPoint()).append("\n");
                sb.append("- 人群：").append(o.audience()).append("\n");
                if (!o.existingTools().isBlank()) sb.append("- 现有工具：").append(o.existingTools()).append("\n");
                if (!o.payingEvidence().isBlank()) sb.append("- 付费证据：").append(o.payingEvidence()).append("\n");
                sb.append("- 摘要：").append(o.summaryZh()).append("\n\n");
            }
        }

        // --- stats ---
        sb.append("## 三、数据源统计（本次抓取）\n\n");
        if (stats.isEmpty()) {
            sb.append("_本次没有抓到新帖。_\n\n");
        } else {
            sb.append("| 来源 | 新抓取 | 判定商机 |\n|---|---|---|\n");
            for (SourceStat s : stats) {
                sb.append("| ").append(s.source()).append(" | ").append(s.fetched()).append(" | ").append(s.opportunities()).append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("---\n_仅供个人研究使用；数据来自各平台公开 API，遵守其非商业使用条款。_\n");

        Path dir = Path.of(cfg.reportDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(date + ".md");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("latest.md"), sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String fmt(long epoch) {
        return Instant.ofEpochSecond(epoch).atZone(ZONE).format(DATETIME);
    }

    private static String stars(int n) {
        return "★".repeat(Math.max(0, n)) + "☆".repeat(Math.max(0, 5 - n));
    }

    private static String link(String title, String url) {
        if (url == null || url.isBlank()) return escape(title);
        return "[" + escape(title) + "](" + url + ")";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").trim();
    }
}
