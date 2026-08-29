package com.scheduler.api.dto;

import java.util.List;

/**
 * Paginated list response for {@code GET /api/v1/tasks}.
 *
 * <p>Shape (per blueprint Section 9):
 * <pre>{@code
 * {
 *   "data": [ {...}, {...} ],
 *   "pagination": {
 *     "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2...",
 *     "hasMore": true
 *   }
 * }
 * }</pre>
 *
 * <h2>Cursor strategy</h2>
 * <p>The cursor encodes the {@code (createdAt, id)} of the last returned row as
 * Base64(JSON). The next-page query uses:
 * <pre>{@code
 * WHERE (created_at < :cursorCreatedAt)
 *    OR (created_at = :cursorCreatedAt AND id > :cursorId)
 * ORDER BY created_at DESC, id ASC
 * LIMIT :limit
 * }</pre>
 *
 * <p>Using a composite {@code (createdAt, id)} key rather than just {@code createdAt}
 * makes the cursor stable when multiple tasks share the same timestamp (e.g., batch
 * inserts). The UUID {@code id} acts as a deterministic tiebreaker.
 */
public class PagedTaskResponse {

    private List<TaskResponse> data;
    private Pagination pagination;

    public PagedTaskResponse(List<TaskResponse> data, String nextCursor, boolean hasMore) {
        this.data = data;
        this.pagination = new Pagination(nextCursor, hasMore);
    }

    public List<TaskResponse> getData() { return data; }
    public Pagination getPagination() { return pagination; }

    public static class Pagination {
        private final String nextCursor;
        private final boolean hasMore;

        public Pagination(String nextCursor, boolean hasMore) {
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }

        public String getNextCursor() { return nextCursor; }
        public boolean isHasMore() { return hasMore; }
    }
}
