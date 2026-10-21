package com.sidehustle.daily.store;

import com.sidehustle.daily.model.KnownDemand;
import com.sidehustle.daily.model.Opportunity;
import com.sidehustle.daily.model.Post;

import java.util.HashSet;
import java.util.Set;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite persistence. Posts are deduplicated on (source, id). */
public final class Store implements AutoCloseable {

    private final Connection conn;

    public Store(Path dbPath) throws SQLException, java.io.IOException {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS posts (
                  source TEXT NOT NULL,
                  id TEXT NOT NULL,
                  title TEXT,
                  body TEXT,
                  url TEXT,
                  community TEXT,
                  author TEXT,
                  created_at INTEGER,
                  score INTEGER,
                  num_comments INTEGER,
                  matched_keyword TEXT,
                  fetched_at INTEGER,
                  PRIMARY KEY (source, id)
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_posts_created ON posts(created_at)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS opportunities (
                  source TEXT NOT NULL,
                  id TEXT NOT NULL,
                  is_opportunity INTEGER NOT NULL,
                  confidence REAL,
                  pain_key TEXT,
                  pain_point TEXT,
                  audience TEXT,
                  existing_tools TEXT,
                  paying_evidence TEXT,
                  score INTEGER,
                  summary_zh TEXT,
                  classified_at INTEGER,
                  PRIMARY KEY (source, id)
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_opp_pain ON opportunities(pain_key)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_opp_classified ON opportunities(classified_at)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS demands (
                  pain_key TEXT PRIMARY KEY,
                  pain_point TEXT,
                  audience TEXT,
                  existing_tools TEXT,
                  paying_evidence TEXT,
                  summary_zh TEXT,
                  top_score INTEGER,
                  mention_count INTEGER,
                  first_seen_at INTEGER,
                  last_seen_at INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS runs (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  started_at INTEGER,
                  finished_at INTEGER,
                  fetched INTEGER,
                  new_posts INTEGER,
                  classified INTEGER,
                  opportunities INTEGER
                )""");
        }
        rebuildDemands();
    }

    /** @return true if the post was new. */
    public synchronized boolean upsertPost(Post p, long fetchedAt) throws SQLException {
        return upsertAll(List.of(p), fetchedAt) > 0;
    }

    /** Writes one source's posts in a single transaction. @return how many were new. */
    public synchronized int upsertAll(List<Post> posts, long fetchedAt) throws SQLException {
        if (posts == null || posts.isEmpty()) return 0;
        String sql = """
            INSERT INTO posts(source,id,title,body,url,community,author,created_at,score,num_comments,matched_keyword,fetched_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source,id) DO UPDATE SET
              score=excluded.score, num_comments=excluded.num_comments, url=excluded.url
            """;
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        int fresh = 0;
        try (PreparedStatement insert = conn.prepareStatement(sql);
             PreparedStatement lookup = conn.prepareStatement("SELECT 1 FROM posts WHERE source=? AND id=?")) {
            for (Post p : posts) {
                lookup.setString(1, p.source());
                lookup.setString(2, p.id());
                boolean found;
                try (ResultSet rs = lookup.executeQuery()) { found = rs.next(); }
                if (!found) fresh++;
                insert.setString(1, p.source());
                insert.setString(2, p.id());
                insert.setString(3, p.title());
                insert.setString(4, p.body());
                insert.setString(5, p.url());
                insert.setString(6, p.community());
                insert.setString(7, p.author());
                insert.setLong(8, p.createdAt());
                insert.setInt(9, p.score());
                insert.setInt(10, p.numComments());
                insert.setString(11, p.matchedKeyword());
                insert.setLong(12, fetchedAt);
                insert.executeUpdate();
            }
            conn.commit();
            return fresh;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    public synchronized List<Post> listPosts(String source) throws SQLException {
        return searchPosts(new PostFilter(null, source, null, null), Integer.MAX_VALUE, 0);
    }

    /** Newest posts first. Text matches title, body, community, author, or keyword. */
    public synchronized List<Post> searchPosts(PostFilter filter, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM posts WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, filter);
        sql.append(" ORDER BY created_at DESC, rowid DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        List<Post> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readPost(rs));
            }
        }
        return out;
    }

    public synchronized int countPosts(PostFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM posts WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilter(sql, args, filter);
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void appendFilter(StringBuilder sql, List<Object> args, PostFilter filter) {
        if (filter.source() != null && !filter.source().isBlank()) {
            sql.append(" AND source = ?");
            args.add(filter.source());
        }
        if (filter.text() != null && !filter.text().isBlank()) {
            String like = likePattern(filter.text());
            sql.append("""
                     AND (title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\'
                       OR community LIKE ? ESCAPE '\\' OR author LIKE ? ESCAPE '\\'
                       OR matched_keyword LIKE ? ESCAPE '\\')""");
            for (int i = 0; i < 5; i++) args.add(like);
        }
        if (filter.fromEpoch() != null) {
            sql.append(" AND created_at >= ?");
            args.add(filter.fromEpoch());
        }
        if (filter.toEpoch() != null) {
            sql.append(" AND created_at <= ?");
            args.add(filter.toEpoch());
        }
    }

    private static String likePattern(String text) {
        String escaped = text.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Integer n) ps.setInt(i + 1, n);
            else if (arg instanceof Long n) ps.setLong(i + 1, n);
            else ps.setString(i + 1, String.valueOf(arg));
        }
    }

    public record PostFilter(String text, String source, Long fromEpoch, Long toEpoch) {}

    public synchronized List<SourceCount> countBySource() throws SQLException {
        String sql = "SELECT source, COUNT(*) FROM posts GROUP BY source ORDER BY COUNT(*) DESC";
        List<SourceCount> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new SourceCount(rs.getString(1), rs.getInt(2)));
        }
        return out;
    }

    /** True when this source+id already has a judgement, so it must not be sent to the model again. */
    public synchronized boolean isClassified(String source, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM opportunities WHERE source=? AND id=?")) {
            ps.setString(1, source);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public synchronized List<Post> listUnclassified(int limit) throws SQLException {
        String sql = """
            SELECT p.* FROM posts p
            LEFT JOIN opportunities o ON o.source=p.source AND o.id=p.id
            WHERE o.id IS NULL
            ORDER BY p.created_at DESC
            LIMIT ?""";
        List<Post> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readPost(rs));
            }
        }
        return out;
    }

    public synchronized int countUnclassified() throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM posts p
            LEFT JOIN opportunities o ON o.source=p.source AND o.id=p.id
            WHERE o.id IS NULL""";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public synchronized List<OpportunityRow> listOpportunities() throws SQLException {
        String sql = """
            SELECT o.*, p.title, p.url, p.community, p.score AS post_score, p.num_comments, p.created_at
            FROM opportunities o JOIN posts p ON p.source=o.source AND p.id=o.id
            WHERE o.is_opportunity=1
            ORDER BY o.score DESC, p.num_comments DESC""";
        List<OpportunityRow> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(readOpportunityRow(rs));
        }
        return out;
    }

    /**
     * Saves one post judgement and folds it into the demands table.
     *
     * @return true when this post created a new demand row; false when it joined an existing one or was not a demand
     */
    public synchronized boolean saveOpportunity(Opportunity o, long classifiedAt) throws SQLException {
        String previous = previousPainKey(o.source(), o.id());
        String sql = """
            INSERT OR REPLACE INTO opportunities
              (source,id,is_opportunity,confidence,pain_key,pain_point,audience,existing_tools,paying_evidence,score,summary_zh,classified_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, o.source());
            ps.setString(2, o.id());
            ps.setInt(3, o.isOpportunity() ? 1 : 0);
            ps.setDouble(4, o.confidence());
            ps.setString(5, o.painKey());
            ps.setString(6, o.painPoint());
            ps.setString(7, o.audience());
            ps.setString(8, o.existingTools());
            ps.setString(9, o.payingEvidence());
            ps.setInt(10, o.score());
            ps.setString(11, o.summaryZh());
            ps.setLong(12, classifiedAt);
            ps.executeUpdate();
        }
        boolean created = false;
        if (o.isOpportunity() && o.painKey() != null && !o.painKey().isBlank()) {
            created = upsertDemand(o, classifiedAt);
        }
        if (previous != null && !previous.equals(o.painKey())) refreshDemandCount(previous);
        return created;
    }

    /** If the new label is the same problem as an existing demand, reuse that pain_key. */
    public synchronized Opportunity canonicalize(Opportunity o) throws SQLException {
        if (!o.isOpportunity() || o.painKey() == null || o.painKey().isBlank()) return o;
        String canonical = matchPainKey(o.painKey());
        if (canonical == null || canonical.equals(o.painKey())) return o;
        return new Opportunity(o.source(), o.id(), true, o.confidence(), canonical,
                o.painPoint(), o.audience(), o.existingTools(), o.payingEvidence(), o.score(), o.summaryZh());
    }

    public synchronized List<KnownDemand> listKnownDemands() throws SQLException {
        String sql = "SELECT pain_key, pain_point FROM demands ORDER BY mention_count DESC, top_score DESC LIMIT 80";
        List<KnownDemand> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new KnownDemand(rs.getString(1), rs.getString(2)));
        }
        return out;
    }

    public synchronized List<DemandRow> listDemands() throws SQLException {
        String sql = "SELECT * FROM demands ORDER BY mention_count DESC, top_score DESC, last_seen_at DESC";
        List<DemandRow> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new DemandRow(
                        rs.getString("pain_key"),
                        rs.getString("pain_point"),
                        rs.getString("audience"),
                        rs.getString("existing_tools"),
                        rs.getString("paying_evidence"),
                        rs.getString("summary_zh"),
                        rs.getInt("top_score"),
                        rs.getInt("mention_count"),
                        rs.getLong("first_seen_at"),
                        rs.getLong("last_seen_at")));
            }
        }
        return out;
    }

    /** Opportunities (joined with their posts) classified at or after the given epoch second. */
    public List<OpportunityRow> opportunitiesClassifiedSince(long sinceEpoch, int minScore) throws SQLException {
        String sql = """
            SELECT o.*, p.title, p.url, p.community, p.score AS post_score, p.num_comments, p.created_at
            FROM opportunities o JOIN posts p ON p.source=o.source AND p.id=o.id
            WHERE o.is_opportunity=1 AND o.classified_at>=? AND o.score>=?
            ORDER BY o.score DESC, p.num_comments DESC""";
        List<OpportunityRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sinceEpoch);
            ps.setInt(2, minScore);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readOpportunityRow(rs));
            }
        }
        return out;
    }

    /** Pain keys that recur across posts created within the window. */
    public List<PainCluster> recurringPains(long sinceEpoch, int threshold) throws SQLException {
        String sql = """
            SELECT o.pain_key,
                   COUNT(*) AS cnt,
                   COUNT(DISTINCT p.source || '/' || p.community) AS communities,
                   AVG(o.score) AS avg_score,
                   MAX(o.score) AS max_score,
                   (SELECT p2.title FROM opportunities o2 JOIN posts p2 ON p2.source=o2.source AND p2.id=o2.id
                      WHERE o2.pain_key=o.pain_key AND o2.is_opportunity=1 ORDER BY o2.score DESC, p2.num_comments DESC LIMIT 1) AS sample_title,
                   (SELECT p2.url FROM opportunities o2 JOIN posts p2 ON p2.source=o2.source AND p2.id=o2.id
                      WHERE o2.pain_key=o.pain_key AND o2.is_opportunity=1 ORDER BY o2.score DESC, p2.num_comments DESC LIMIT 1) AS sample_url
            FROM opportunities o JOIN posts p ON p.source=o.source AND p.id=o.id
            WHERE o.is_opportunity=1 AND o.pain_key<>'' AND p.created_at>=?
            GROUP BY o.pain_key
            HAVING cnt>=?
            ORDER BY cnt DESC, avg_score DESC""";
        List<PainCluster> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sinceEpoch);
            ps.setInt(2, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PainCluster(
                            rs.getString("pain_key"),
                            rs.getInt("cnt"),
                            rs.getInt("communities"),
                            rs.getDouble("avg_score"),
                            rs.getInt("max_score"),
                            rs.getString("sample_title"),
                            rs.getString("sample_url")));
                }
            }
        }
        return out;
    }

    public int countClassifiedSince(long sinceEpoch, boolean opportunityOnly) throws SQLException {
        String sql = "SELECT COUNT(*) FROM opportunities WHERE classified_at>=?" + (opportunityOnly ? " AND is_opportunity=1" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sinceEpoch);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public List<SourceStat> sourceStatsSince(long sinceEpoch) throws SQLException {
        String sql = """
            SELECT p.source, COUNT(*) AS fetched,
                   SUM(CASE WHEN o.is_opportunity=1 THEN 1 ELSE 0 END) AS opps
            FROM posts p LEFT JOIN opportunities o ON o.source=p.source AND o.id=p.id
            WHERE p.fetched_at>=?
            GROUP BY p.source ORDER BY p.source""";
        List<SourceStat> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sinceEpoch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SourceStat(rs.getString(1), rs.getInt(2), rs.getInt(3)));
            }
        }
        return out;
    }

    public long recordRun(long startedAt, long finishedAt, int fetched, int newPosts, int classified, int opportunities) throws SQLException {
        String sql = "INSERT INTO runs(started_at,finished_at,fetched,new_posts,classified,opportunities) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, startedAt);
            ps.setLong(2, finishedAt);
            ps.setInt(3, fetched);
            ps.setInt(4, newPosts);
            ps.setInt(5, classified);
            ps.setInt(6, opportunities);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        }
    }

    private static Post readPost(ResultSet rs) throws SQLException {
        return new Post(
                rs.getString("source"), rs.getString("id"), rs.getString("title"), rs.getString("body"),
                rs.getString("url"), rs.getString("community"), rs.getString("author"), rs.getLong("created_at"),
                rs.getInt("score"), rs.getInt("num_comments"), rs.getString("matched_keyword"));
    }

    private static OpportunityRow readOpportunityRow(ResultSet rs) throws SQLException {
        Opportunity o = new Opportunity(
                rs.getString("source"), rs.getString("id"), rs.getInt("is_opportunity") == 1, rs.getDouble("confidence"),
                rs.getString("pain_key"), rs.getString("pain_point"), rs.getString("audience"), rs.getString("existing_tools"),
                rs.getString("paying_evidence"), rs.getInt("score"), rs.getString("summary_zh"));
        return new OpportunityRow(o, rs.getString("title"), rs.getString("url"), rs.getString("community"),
                rs.getInt("post_score"), rs.getInt("num_comments"), rs.getLong("created_at"));
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    public record OpportunityRow(Opportunity opp, String title, String url, String community, int postScore, int numComments, long createdAt) {}

    public record PainCluster(String painKey, int count, int communities, double avgScore, int maxScore, String sampleTitle, String sampleUrl) {}

    public record SourceStat(String source, int fetched, int opportunities) {}

    public record SourceCount(String source, int count) {}

    public record DemandRow(
            String painKey,
            String painPoint,
            String audience,
            String existingTools,
            String payingEvidence,
            String summaryZh,
            int topScore,
            int mentionCount,
            long firstSeenAt,
            long lastSeenAt
    ) {}

    private void rebuildDemands() throws SQLException {
        Set<String> existing = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pain_key FROM demands")) {
            while (rs.next()) existing.add(rs.getString(1));
        }
        for (OpportunityRow row : listOpportunities()) {
            Opportunity o = row.opp();
            if (o.painKey() == null || o.painKey().isBlank() || !existing.add(o.painKey())) continue;
            upsertDemand(o, row.createdAt() > 0 ? row.createdAt() : System.currentTimeMillis() / 1000);
        }
        for (String key : existing) refreshDemandCount(key);
    }

    /** @return true if this pain_key was not in the table before */
    private boolean upsertDemand(Opportunity o, long seenAt) throws SQLException {
        boolean exists;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM demands WHERE pain_key=?")) {
            ps.setString(1, o.painKey());
            try (ResultSet rs = ps.executeQuery()) { exists = rs.next(); }
        }
        if (!exists) {
            String insert = """
                INSERT INTO demands(pain_key,pain_point,audience,existing_tools,paying_evidence,summary_zh,top_score,mention_count,first_seen_at,last_seen_at)
                VALUES(?,?,?,?,?,?,?,0,?,?)""";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, o.painKey());
                ps.setString(2, o.painPoint());
                ps.setString(3, o.audience());
                ps.setString(4, o.existingTools());
                ps.setString(5, o.payingEvidence());
                ps.setString(6, o.summaryZh());
                ps.setInt(7, o.score());
                ps.setLong(8, seenAt);
                ps.setLong(9, seenAt);
                ps.executeUpdate();
            }
        } else {
            String update = """
                UPDATE demands SET
                  last_seen_at=?,
                  top_score=MAX(top_score, ?),
                  pain_point=CASE WHEN ?>top_score THEN ? ELSE pain_point END,
                  audience=CASE WHEN ?>top_score THEN ? ELSE audience END,
                  existing_tools=CASE WHEN ?>top_score THEN ? ELSE existing_tools END,
                  paying_evidence=CASE WHEN ?>top_score THEN ? ELSE paying_evidence END,
                  summary_zh=CASE WHEN ?>top_score THEN ? ELSE summary_zh END
                WHERE pain_key=?""";
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setLong(1, seenAt);
                ps.setInt(2, o.score());
                ps.setInt(3, o.score());
                ps.setString(4, o.painPoint());
                ps.setInt(5, o.score());
                ps.setString(6, o.audience());
                ps.setInt(7, o.score());
                ps.setString(8, o.existingTools());
                ps.setInt(9, o.score());
                ps.setString(10, o.payingEvidence());
                ps.setInt(11, o.score());
                ps.setString(12, o.summaryZh());
                ps.setString(13, o.painKey());
                ps.executeUpdate();
            }
        }
        refreshDemandCount(o.painKey());
        return !exists;
    }

    private void refreshDemandCount(String painKey) throws SQLException {
        if (painKey == null || painKey.isBlank()) return;
        int count;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM opportunities WHERE is_opportunity=1 AND pain_key=?")) {
            ps.setString(1, painKey);
            try (ResultSet rs = ps.executeQuery()) { count = rs.next() ? rs.getInt(1) : 0; }
        }
        if (count == 0) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM demands WHERE pain_key=?")) {
                ps.setString(1, painKey);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE demands SET mention_count=? WHERE pain_key=?")) {
            ps.setInt(1, count);
            ps.setString(2, painKey);
            ps.executeUpdate();
        }
    }

    private String previousPainKey(String source, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pain_key FROM opportunities WHERE source=? AND id=? AND is_opportunity=1")) {
            ps.setString(1, source);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String matchPainKey(String candidate) throws SQLException {
        String best = null;
        double bestScore = 0;
        for (DemandRow d : listDemands()) {
            if (d.painKey().equals(candidate)) return candidate;
            double score = jaccard(candidate, d.painKey());
            if (score > bestScore) {
                bestScore = score;
                best = d.painKey();
            }
        }
        return bestScore >= 0.75 ? best : candidate;
    }

    private static double jaccard(String a, String b) {
        Set<String> left = tokens(a);
        Set<String> right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        int inter = 0;
        for (String t : left) if (right.contains(t)) inter++;
        return (double) inter / (left.size() + right.size() - inter);
    }

    private static Set<String> tokens(String key) {
        Set<String> out = new HashSet<>();
        for (String part : key.split("-")) {
            if (part.length() >= 3) out.add(part);
        }
        return out;
    }
}
