package com.sidehustle.daily.model;

/**
 * A single post pulled from any source, normalized.
 *
 * @param source         "reddit" | "hackernews" | "v2ex" | "freelancer" | "github"
 * @param id             source-native id (unique within the source)
 * @param community      subreddit / "ask_hn" / v2ex node name
 * @param createdAt      epoch seconds (UTC)
 * @param matchedKeyword the search keyword that surfaced this post (may be "new-listing")
 */
public record Post(
        String source,
        String id,
        String title,
        String body,
        String url,
        String community,
        String author,
        long createdAt,
        int score,
        int numComments,
        String matchedKeyword
) {
    public String key() {
        return source + ":" + id;
    }
}
