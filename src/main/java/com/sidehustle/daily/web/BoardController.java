package com.sidehustle.daily.web;

import com.sidehustle.daily.model.Post;
import com.sidehustle.daily.store.Store;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class BoardController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);
    private static final List<Integer> SIZE_CHOICES = List.of(10, 15, 50, 100);
    private static final int MAX_SIZE = 200;

    private final Store store;
    private final FetchJob fetchJob;

    public BoardController(Store store, FetchJob fetchJob) {
        this.store = store;
        this.fetchJob = fetchJob;
    }

    @GetMapping("/")
    public String index(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String source,
                        @RequestParam(name = "from", required = false) String from,
                        @RequestParam(name = "to", required = false) String to,
                        @RequestParam(required = false) String page,
                        @RequestParam(required = false) String size,
                        Model model) throws Exception {
        String text = blankToNull(q);
        String platform = blankToNull(source);
        LocalDate start = parseDate(from);
        LocalDate end = parseDate(to);
        if (start != null && end != null && start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        int pageSize = clamp(size, 15, 1, MAX_SIZE);
        Long fromEpoch = start == null ? null : start.atStartOfDay(ZONE).toEpochSecond();
        Long toEpoch = end == null ? null : end.plusDays(1).atStartOfDay(ZONE).toEpochSecond() - 1;
        Store.PostFilter filter = new Store.PostFilter(text, platform, fromEpoch, toEpoch);

        List<Store.SourceCount> counts = store.countBySource();
        int total = counts.stream().mapToInt(Store.SourceCount::count).sum();
        int matched = store.countPosts(filter);
        int pages = Math.max(1, (matched + pageSize - 1) / pageSize);
        int current = Math.min(clamp(page, 1, 1, pages), pages);
        List<Post> posts = store.searchPosts(filter, pageSize, (current - 1) * pageSize);

        model.addAttribute("days", group(posts));
        model.addAttribute("counts", counts);
        model.addAttribute("total", total);
        model.addAttribute("matched", matched);
        model.addAttribute("shown", posts.size());
        model.addAttribute("q", text == null ? "" : text);
        model.addAttribute("source", platform == null ? "" : platform);
        model.addAttribute("from", start == null ? "" : start.format(DAY));
        model.addAttribute("to", end == null ? "" : end.format(DAY));
        model.addAttribute("page", current);
        model.addAttribute("pages", pages);
        model.addAttribute("size", pageSize);
        model.addAttribute("sizeChoices", SIZE_CHOICES);
        model.addAttribute("pageLinks", pageLinks(current, pages));
        model.addAttribute("dbPath", "data/sidehustle.db");
        model.addAttribute("fetch", fetchJob.status());
        return "index";
    }

    @GetMapping("/fetch/status")
    @ResponseBody
    public FetchJob.Status fetchStatus() {
        return fetchJob.status();
    }

    @PostMapping("/fetch")
    public String fetch(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String source,
                        @RequestParam(name = "from", required = false) String from,
                        @RequestParam(name = "to", required = false) String to,
                        @RequestParam(required = false) String page,
                        @RequestParam(required = false) String size,
                        RedirectAttributes ra) {
        fetchJob.start();
        addQuery(ra, "q", q);
        addQuery(ra, "source", source);
        addQuery(ra, "from", from);
        addQuery(ra, "to", to);
        addQuery(ra, "page", page);
        addQuery(ra, "size", size);
        return "redirect:/";
    }

    private static void addQuery(RedirectAttributes ra, String name, String value) {
        if (value != null && !value.isBlank()) ra.addAttribute(name, value);
    }

    private static List<DayGroup> group(List<Post> posts) {
        Map<LocalDate, List<Card>> buckets = new LinkedHashMap<>();
        for (Post post : posts) {
            long epoch = post.createdAt() > 0 ? post.createdAt() : 0;
            LocalDate day = epoch == 0
                    ? LocalDate.ofEpochDay(0)
                    : Instant.ofEpochSecond(epoch).atZone(ZONE).toLocalDate();
            buckets.computeIfAbsent(day, k -> new ArrayList<>()).add(toCard(post, epoch));
        }
        List<DayGroup> days = new ArrayList<>();
        for (var e : buckets.entrySet()) {
            LocalDate day = e.getKey();
            String label = day.toEpochDay() == 0 ? "未知日期" : day.format(DAY) + " " + day.format(WEEKDAY);
            days.add(new DayGroup(label, e.getValue()));
        }
        return days;
    }

    private static Card toCard(Post post, long epoch) {
        String time = epoch == 0 ? "" : Instant.ofEpochSecond(epoch).atZone(ZONE).format(TIME);
        String body = post.body() == null ? "" : post.body()
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (body.length() > 220) body = body.substring(0, 220) + "…";
        return new Card(post, time, body);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int clamp(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int n = Integer.parseInt(raw.trim());
            return Math.min(max, Math.max(min, n));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), DAY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<PageLink> pageLinks(int page, int pages) {
        List<PageLink> links = new ArrayList<>();
        if (pages <= 7) {
            for (int i = 1; i <= pages; i++) links.add(PageLink.of(i));
            return links;
        }
        int from = Math.max(2, page - 2);
        int to = Math.min(pages - 1, page + 2);
        links.add(PageLink.of(1));
        if (from > 2) links.add(PageLink.ellipsis());
        for (int i = from; i <= to; i++) links.add(PageLink.of(i));
        if (to < pages - 1) links.add(PageLink.ellipsis());
        links.add(PageLink.of(pages));
        return links;
    }

    public record DayGroup(String label, List<Card> cards) {}

    public record Card(Post post, String time, String excerpt) {}

    public record PageLink(Integer n, boolean gap) {
        static PageLink of(int n) { return new PageLink(n, false); }
        static PageLink ellipsis() { return new PageLink(null, true); }
    }
}
