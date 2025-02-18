package nl.inl.blacklab.search.results;

import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.indexmetadata.AnnotatedField;

/**
 * Information about the original query.
 */
public final class QueryInfo {

    public static QueryInfo create(BlackLabIndex index) {
        return create(index, null, true);
    }

    public static QueryInfo create(BlackLabIndex index, AnnotatedField field) {
        return create(index, field, true);
    }

    public static QueryInfo create(BlackLabIndex index, AnnotatedField field, boolean useCache) {
        return new QueryInfo(index, field, useCache);
    }

    private final BlackLabIndex index;

    /** The field these hits came from (will also be used as concordance field) */
    private final AnnotatedField field;

    /** Should we use the cache for this query, or bypass it? */
    private final boolean useCache;

    private QueryInfo(BlackLabIndex index, AnnotatedField field, boolean useCache) {
        super();
        this.index = index;
        this.field = field == null ? index.mainAnnotatedField() : field;
        this.useCache = useCache;
    }

    /**
     * Return a copy with a different index.
     *
     * If this is the same index, simply returns this object.
     *
     * @param newIndex index to use
     * @return QueryInfo with the specified index
     */
    public QueryInfo withIndex(BlackLabIndex newIndex) {
        if (this.index == newIndex)
            return this;
        return new QueryInfo(newIndex, field, useCache);
    }

    /** @return the index that was searched. */
    public BlackLabIndex index() {
        return index;
    }

    /** @return field that was searched */
    public AnnotatedField field() {
        return field;
    }

    /** @return should we use the cache for this query, or bypass it? */
    public boolean useCache() {
        return useCache;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((field == null) ? 0 : field.hashCode());
        result = prime * result + ((index == null) ? 0 : index.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        QueryInfo other = (QueryInfo) obj;
        if (field == null) {
            if (other.field != null)
                return false;
        } else if (!field.equals(other.field))
            return false;
        if (index == null) {
            if (other.index != null)
                return false;
        } else if (!index.equals(other.index))
            return false;
        return true;
    }

}

