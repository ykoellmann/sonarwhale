package dev.sonarwhale.testapi.model;
import java.util.List;
public class PagedResult<T> {
    public final List<T> items;
    public final int page;
    public final int pageSize;
    public final long totalCount;
    public PagedResult(List<T> items, int page, int pageSize, long totalCount) {
        this.items = items; this.page = page; this.pageSize = pageSize; this.totalCount = totalCount;
    }
}
