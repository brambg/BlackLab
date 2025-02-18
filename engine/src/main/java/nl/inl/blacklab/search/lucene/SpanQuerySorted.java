package nl.inl.blacklab.search.lucene;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;

import nl.inl.blacklab.search.results.QueryInfo;

/**
 * Ensure hits from a SpanQuery are sorted by start- or endpoint (within
 * document), and optionally eliminate duplicate hits.
 */
class SpanQuerySorted extends BLSpanQuery {
    private final BLSpanQuery src;

    final boolean sortByEndpoint;

    final boolean eliminateDuplicates;

    public SpanQuerySorted(BLSpanQuery src, boolean sortByEndpoint, boolean eliminateDuplicates) {
        super(src.queryInfo);
        this.src = src;
        this.sortByEndpoint = sortByEndpoint;
        this.eliminateDuplicates = eliminateDuplicates;
    }

    @Override
    public BLSpanQuery rewrite(IndexReader reader) throws IOException {
        BLSpanQuery rewritten = src.rewrite(reader);
        if (rewritten != src) {
            boolean mustDedupe = eliminateDuplicates && !rewritten.hitsAreUnique();
            boolean mustSort = !sortByEndpoint && !rewritten.hitsStartPointSorted() ||
                    sortByEndpoint && !rewritten.hitsEndPointSorted();
            if (!mustDedupe && !mustSort)
                return rewritten;
            BLSpanQuery r = new SpanQuerySorted(rewritten, sortByEndpoint, eliminateDuplicates);
            r.setQueryInfo(queryInfo);
            return r;
        }
        return this;
    }

    @Override
    public void visit(QueryVisitor visitor) {
        if (visitor.acceptField(getField())) {
            src.visit(visitor.getSubVisitor(Occur.MUST, this));
        }
    }

    @Override
    public boolean matchesEmptySequence() {
        return src.matchesEmptySequence();
    }

    @Override
    public BLSpanQuery noEmpty() {
        return new SpanQuerySorted(src.noEmpty(), sortByEndpoint, eliminateDuplicates);
    }

    @Override
    public BLSpanWeight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        BLSpanWeight weight = src.createWeight(searcher, scoreMode, boost);
        return new SpanWeightSorted(weight, searcher, scoreMode.needsScores() ? getTermStates(weight) : null, boost);
    }

    class SpanWeightSorted extends BLSpanWeight {

        final BLSpanWeight weight;

        public SpanWeightSorted(BLSpanWeight weight, IndexSearcher searcher, Map<Term, TermStates> terms, float boost)
                throws IOException {
            super(SpanQuerySorted.this, searcher, terms, boost);
            this.weight = weight;
        }

        @Override
        public void extractTerms(Set<Term> terms) {
            weight.extractTerms(terms);
        }

        @Override
        public void extractTermStates(Map<Term, TermStates> contexts) {
            weight.extractTermStates(contexts);
        }

        @Override
        public BLSpans getSpans(final LeafReaderContext context, Postings requiredPostings) throws IOException {
            BLSpans srcSpans = weight.getSpans(context, requiredPostings);
            if (srcSpans == null)
                return null;
            return PerDocumentSortedSpans.get(srcSpans, !sortByEndpoint, eliminateDuplicates);
        }
    }

    @Override
    public String toString(String field) {
        return "SORT(" + src + ", " + (sortByEndpoint ? "END" : "START") + ", " + eliminateDuplicates + ")";
    }

    @Override
    public String getField() {
        return src.getField();
    }

    @Override
    public String getRealField() {
        return src.getRealField();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (eliminateDuplicates ? 1231 : 1237);
        result = prime * result + (sortByEndpoint ? 1231 : 1237);
        result = prime * result + ((src == null) ? 0 : src.hashCode());
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
        SpanQuerySorted other = (SpanQuerySorted) obj;
        if (eliminateDuplicates != other.eliminateDuplicates)
            return false;
        if (sortByEndpoint != other.sortByEndpoint)
            return false;
        if (src == null) {
            if (other.src != null)
                return false;
        } else if (!src.equals(other.src))
            return false;
        return true;
    }

    @Override
    public boolean hitsAllSameLength() {
        return src.hitsAllSameLength();
    }

    @Override
    public int hitsLengthMin() {
        return src.hitsLengthMin();
    }

    @Override
    public int hitsLengthMax() {
        return src.hitsLengthMax();
    }

    @Override
    public boolean hitsStartPointSorted() {
        return true;
    }

    @Override
    public boolean hitsEndPointSorted() {
        return src.hitsEndPointSorted();
    }

    @Override
    public boolean hitsHaveUniqueStart() {
        return src.hitsHaveUniqueStart();
    }

    @Override
    public boolean hitsHaveUniqueEnd() {
        return src.hitsHaveUniqueEnd();
    }

    @Override
    public boolean hitsAreUnique() {
        return true;
    }

    @Override
    public long reverseMatchingCost(IndexReader reader) {
        return src.reverseMatchingCost(reader);
    }

    @Override
    public int forwardMatchingCost() {
        return src.forwardMatchingCost();
    }

    @Override
    public void setQueryInfo(QueryInfo queryInfo) {
        super.setQueryInfo(queryInfo);
        src.setQueryInfo(queryInfo);
    }
}
