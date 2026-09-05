package com.scheduler.api.dto;

import java.util.List;

/**
 * Cursor-paginated list response for {@code GET /api/v1/tasks}.
 *
 * <h3>Pagination shape</h3>
 * <pre>
 * {
 *   "data": [...],
 *   "pagination": {
 *     "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2LTA4LTI5VDEwOjAwOjAwWiIsImlkIjoiZjQ3YWMxMGItLi4uIn0=",
 *     "hasMore": true,
 *     "limit": 20
 *   }
 * }
 * </pre>
 *
 * <p>When {@code hasMore} is {@code false}, {@code nextCursor} is {@code null}.
 * To fetch the next page, pass {@code nextCursor} as the {@code cursor} query
 * parameter in the subsequent request.</p>
 *
 * <h3>Cursor encoding</h3>
 * <p>The cursor is a Base64-encoded JSON object containing the {@code createdAt}
 * timestamp and {@code id} of the last row on the current page.  This is a
 * keyset / seek cursor — it encodes a row position, not an offset — and allows
 * O(limit) pagination regardless of page depth.</p>
 */
public class PagedTaskResponse {

    private final List<TaskResponse> data;
    private final Pagination pagination;

    public PagedTaskResponse(List<TaskResponse> data, Pagination pagination) {
        this.data = data;
        this.pagination = pagination;
    }

    public List<TaskResponse> getData() { return data; }
    public Pagination getPagination() { return pagination; }

    // -------------------------------------------------------------------------
    // Nested pagination metadata
    // -------------------------------------------------------------------------

    public static class Pagination {
        private final String nextCursor;
        private final boolean hasMore;
        private final int limit;

        public Pagination(String nextCursor, boolean hasMore, int limit) {
            this.nextCursor = nextCursor;
            this.hasMore    = hasMore;
            this.limit      = limit;
        }

        public String getNextCursor() { return nextCursor; }
        public boolean isHasMore() { return hasMore; }
        public int getLimit() { return limit; }
    }
}
