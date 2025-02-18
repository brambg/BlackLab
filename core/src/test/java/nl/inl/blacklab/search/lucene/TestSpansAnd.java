package nl.inl.blacklab.search.lucene;

import java.io.IOException;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.spans.Spans;
import org.junit.Assert;
import org.junit.Test;

import nl.inl.blacklab.TestUtil;
import nl.inl.blacklab.mocks.MockSpans;

public class TestSpansAnd {
    private static SpansAnd getSpans() {
        BLSpans a = MockSpans.fromLists(
                new int[] { 1, 1, 2, 2, 2, 3 },
                new int[] { 10, 20, 10, 10, 30, 10 },
                new int[] { 15, 25, 15, 20, 35, 15 });
        BLSpans b = MockSpans.fromLists(
                new int[] { 1, 2, 2, 3 },
                new int[] { 10, 10, 20, 20 },
                new int[] { 15, 20, 25, 25 });
        return new SpansAnd(a, b);
    }

    @Test
    public void testAndSpans() throws IOException {
        Spans exp = MockSpans.fromLists(
                new int[] { 1, 2 },
                new int[] { 10, 10 },
                new int[] { 15, 20 });
        TestUtil.assertEquals(exp, getSpans());
    }

    @Test
    public void testAndSpansAdvance() throws IOException {
        Spans exp = MockSpans.single(2, 10, 20);
        SpansAnd spans = getSpans();
        Assert.assertEquals(2, spans.advance(2));
        TestUtil.assertEquals(exp, spans, true);
    }

    @Test
    public void testAndSpansAdvanceToCurrent() throws IOException {
        Spans exp = MockSpans.single(2, 10, 20);
        SpansAnd spans = getSpans();
        Assert.assertEquals(1, spans.nextDoc());
        Assert.assertEquals(2, spans.advance(1));
        TestUtil.assertEquals(exp, spans, true);
    }

    @Test
    public void testAndSpansAdvanceNoResults() throws IOException {
        MockSpans exp = MockSpans.emptySpans();
        SpansAnd spans = getSpans();
        Assert.assertEquals(DocIdSetIterator.NO_MORE_DOCS, spans.advance(3));
        TestUtil.assertEquals(exp, spans, true);
    }

    @Test
    public void testAndSpansAdvanceBeyond() throws IOException {
        MockSpans exp = MockSpans.emptySpans();
        SpansAnd spans = getSpans();
        Assert.assertEquals(DocIdSetIterator.NO_MORE_DOCS, spans.advance(1000));
        TestUtil.assertEquals(exp, spans, true);
    }
}
