package com.sidehustle.daily.web;

import com.sidehustle.daily.store.Store;
import com.sidehustle.daily.store.Store.OpportunityRow;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class AnalysisController {

    private final Store store;
    private final AnalysisJob job;

    public AnalysisController(Store store, AnalysisJob job) {
        this.store = store;
        this.job = job;
    }

    @GetMapping("/analysis")
    public String page(@RequestParam(name = "repeat", defaultValue = "0") int repeat, Model model) throws Exception {
        List<Demand> all = assemble(store.listDemands(), store.listOpportunities());
        boolean repeatOnly = repeat == 1;
        List<Demand> shown = new ArrayList<>();
        int repeated = 0;
        for (Demand demand : all) {
            if (demand.mentions() > 1) repeated++;
            if (!repeatOnly || demand.mentions() > 1) shown.add(demand);
        }
        model.addAttribute("demands", shown);
        model.addAttribute("archived", all.size());
        model.addAttribute("repeated", repeated);
        model.addAttribute("repeatOnly", repeatOnly);
        model.addAttribute("pending", store.countUnclassified());
        model.addAttribute("status", job.status());
        return "analysis";
    }

    @GetMapping("/analysis/status")
    @ResponseBody
    public AnalysisJob.Status status() {
        return job.status();
    }

    @PostMapping("/analysis")
    public String start() {
        job.start();
        return "redirect:/analysis";
    }

    private static List<Demand> assemble(List<Store.DemandRow> stored, List<OpportunityRow> rows) {
        Map<String, List<OpportunityRow>> buckets = new LinkedHashMap<>();
        for (OpportunityRow row : rows) {
            String key = row.opp().painKey();
            if (key == null || key.isBlank()) continue;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Demand> demands = new ArrayList<>();
        for (Store.DemandRow row : stored) {
            List<OpportunityRow> items = new ArrayList<>(buckets.getOrDefault(row.painKey(), List.of()));
            items.sort(Comparator.comparingInt((OpportunityRow r) -> r.opp().score()).reversed());
            double avg = items.stream().mapToInt(r -> r.opp().score()).average().orElse(0);
            demands.add(new Demand(row.painKey(), row.painPoint(), row.audience(),
                    row.existingTools(), row.payingEvidence(), row.summaryZh(),
                    row.topScore(), avg, row.mentionCount(), items));
        }
        return demands;
    }

    public record Demand(
            String painKey,
            String painPoint,
            String audience,
            String existingTools,
            String payingEvidence,
            String summaryZh,
            int topScore,
            double avgScore,
            int mentionCount,
            List<OpportunityRow> posts
    ) {
        public int mentions() {
            return mentionCount;
        }
    }
}
