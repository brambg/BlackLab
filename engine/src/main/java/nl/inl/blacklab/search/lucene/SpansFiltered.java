package nl.inl.blacklab.search.lucene;

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.spans.SpanCollector;

import nl.inl.blacklab.search.Span;

/**
 * Apply a Filter to a Spans.
 *
 * This allows us to only consider certain documents (say, only documents in a
 * certain domain) when executing our query.
 */
class SpansFiltered extends BLSpans {
    final BLSpans spans;

    /**
     * Set of accepted docs. NOTE: this is not segment-based, but for the whole
     * index!
     */
    final DocIdSetIterator docIdSetIter;

    boolean more;

    public SpansFiltered(BLSpans spans, Scorer filterDocs) throws IOException {
        this.spans = spans;
        docIdSetIter = filterDocs == null ? null : filterDocs.iterator(); //docIdSetIter = filterDocs.iterator();
        more = false;
        if (docIdSetIter != null) {
            more = (docIdSetIter.nextDoc() != NO_MORE_DOCS);
        }
    }

    private int synchronize() throws IOException {
        while (more && spans.docID() != docIdSetIter.docID()) {
            if (spans.docID() < docIdSetIter.docID()) {
                more = spans.advance(docIdSetIter.docID()) != NO_MORE_DOCS;
            } else if (docIdSetIter.advance(spans.docID()) == NO_MORE_DOCS) {
                more = false;
            }
        }
        return more ? spans.docID() : NO_MORE_DOCS;
    }

    @Override
    public int nextDoc() throws IOException {
        if (!more)
            return NO_MORE_DOCS;
        more = spans.nextDoc() != NO_MORE_DOCS;
        return synchronize();
    }

    @Override
    public int nextStartPosition() throws IOException {
        if (!more)
            return NO_MORE_POSITIONS;
        return spans.nextStartPosition();
    }

    @Override
    public int advanceStartPosition(int target) throws IOException {
        if (!more)
            return NO_MORE_POSITIONS;
        return spans.advanceStartPosition(target);
    }

    @Override
    public int advance(int target) throws IOException {
        if (!more)
            return NO_MORE_DOCS;
        more = spans.advance(target) != NO_MORE_DOCS;
        return synchronize();
    }

    @Override
    public int docID() {
        return spans.docID();
    }

    @Override
    public int endPosition() {
        return spans.endPosition();
    }

    @Override
    public int startPosition() {
        return spans.startPosition();
    }

    @Override
    public void passHitQueryContextToClauses(HitQueryContext context) {
        spans.setHitQueryContext(context);
    }

    @Override
    public void getCapturedGroups(Span[] capturedGroups) {
        if (!childClausesCaptureGroups)
            return;
        spans.getCapturedGroups(capturedGroups);
    }

    @Override
    public int width() {
        return spans.width();
    }

    @Override
    public void collect(SpanCollector collector) throws IOException {
        spans.collect(collector);
    }

    @Override
    public float positionsCost() {
        return spans.positionsCost();
    }

}
