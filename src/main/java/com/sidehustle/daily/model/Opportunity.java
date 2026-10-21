package com.sidehustle.daily.model;

/**
 * LLM judgement for one post.
 *
 * @param painKey        normalized short English slug describing the PROBLEM (used for cross-period grouping),
 *                       e.g. "etsy-bulk-listing-edit". Empty when not an opportunity.
 * @param score          1-5 attractiveness for a solo developer; 0 when not an opportunity
 * @param summaryZh      one-paragraph Chinese summary
 */
public record Opportunity(
        String source,
        String id,
        boolean isOpportunity,
        double confidence,
        String painKey,
        String painPoint,
        String audience,
        String existingTools,
        String payingEvidence,
        int score,
        String summaryZh
) {
    public static Opportunity notOpportunity(String source, String id, double confidence, String reasonZh) {
        return new Opportunity(source, id, false, confidence, "", "", "", "", "", 0, reasonZh);
    }
}
