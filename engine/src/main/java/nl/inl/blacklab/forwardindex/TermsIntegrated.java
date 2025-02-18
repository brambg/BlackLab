package nl.inl.blacklab.forwardindex;

import java.io.IOException;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;

import it.unimi.dsi.fastutil.ints.IntArrays;
import nl.inl.blacklab.codec.BLTerms;
import nl.inl.blacklab.codec.BlackLab40PostingsReader;

/** Keeps a list of unique terms and their sort positions.
 *
 * This version is integrated into the Lucene index.
 */
public class TermsIntegrated extends TermsReaderAbstract {

    private Map<String, CollationKey> collationCacheSensitive = new HashMap<>();
    private Map<String, CollationKey> collationCacheInsensitive = new HashMap<>();

    /** Information about a term in the index, and the sort positions in each segment
     *  it occurs in. We'll use this to speed up comparisons where possible (comparing
     *  sort positions in one of the segments is much faster than calculating CollationKeys).
     */
    public class TermInIndex implements Comparable<TermInIndex> {
        /** Term string */
        String term;

        /** This term's global id */
        int globalTermId;

        /** Sort position within each segment, case-sensitive */
        int[] segmentPosSensitive;

        /** Sort position within each segment, case-insensitive */
        int[] segmentPosInsensitive;

        public TermInIndex(String term, int globalTermId, int numberOfSegments) {
            this.term = term;
            this.globalTermId = globalTermId;
            segmentPosSensitive = new int[numberOfSegments];
            Arrays.fill(segmentPosSensitive, -1);
            segmentPosInsensitive = new int[numberOfSegments];
            Arrays.fill(segmentPosInsensitive, -1);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof TermInIndex))
                return false;
            TermInIndex that = (TermInIndex) o;
            return globalTermId == that.globalTermId;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(globalTermId);
        }

        @Override
        public int compareTo(TermInIndex other) {
            int[] pa, pb;

            if (compareSensitive) {
                pa = segmentPosSensitive;
                pb = other.segmentPosSensitive;
            } else {
                pa = segmentPosInsensitive;
                pb = other.segmentPosInsensitive;
            }
            // See if there's a segment these two terms both occur in.
            // If so, we already know how these terms compare.
            for (int i = 0; i < pa.length; i++) {
                int a = pa[i], b = pb[i];
                if (a >= 0 && b >= 0) {
                    // Both terms occur in this segment.
                    // Their relative ordering in that segment applies here as well.
                    return Integer.compare(a, b);
                }
            }
            // There are no segments that these terms both occur in.
            Collator coll = compareSensitive ? collator : collatorInsensitive;
            Map<String, CollationKey> cache = compareSensitive ? collationCacheSensitive : collationCacheInsensitive;

            CollationKey a = cache.computeIfAbsent(term, __ -> coll.getCollationKey(term));
            CollationKey b = cache.computeIfAbsent(other.term, __ -> coll.getCollationKey(other.term));

            return a.compareTo(b);
        }

        public int globalId() {
            return globalTermId;
        }
    }

    private IndexReader indexReader;

    private final String luceneField;

    /** Per segment (by ord number): the translation of that segment's term ids to
     *  global term ids.
     *  Hopefully eventually no longer needed.
     */
    private final Map<Integer, int[]> segmentToGlobalTermIds = new HashMap<>();

    /** Are we sorting TermInIndex sensitively or insensitively right now? */
    private boolean compareSensitive = true;

    public TermsIntegrated(Collators collators, IndexReader indexReader, String luceneField) {
        super(collators);
        this.indexReader = indexReader;
        this.luceneField = luceneField;
        TermInIndex[] terms = readTermsFromIndex();

        // Determine the sort orders for the terms
        int[] sortedSensitive = determineSort(terms, true);
        int[] sortedInsensitive = determineSort(terms, false);

        // Process the values we've determined so far the same way as with the external forward index.
        int[] termId2SensitivePosition = invert(terms, sortedSensitive, true);
        int[] termId2InsensitivePosition = invert(terms, sortedInsensitive, false);
        
        // TODO: just keep terms in String[] and have the sort arrays separately to avoid this conversion?
        String[] termStrings = Arrays.stream(terms).map(t -> t.term).toArray(String[]::new);

        finishInitialization(termStrings, termId2SensitivePosition, termId2InsensitivePosition);

        // clear temporary variables
        this.collationCacheInsensitive = null;
        this.collationCacheSensitive = null;
        this.indexReader = null;
    }


    private TermInIndex[] readTermsFromIndex() {
        // A list of globally unique terms that occur in our index.
        Map<String, TermInIndex> globalTermIds = new LinkedHashMap<>(); // global term ids, in the correct order
        try {
            List<LeafReaderContext> leaves = indexReader.leaves();
            for (LeafReaderContext l: leaves) {
                readTermsFromSegment(globalTermIds, l);
            }
            return globalTermIds.values().toArray(TermInIndex[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void readTermsFromSegment(Map<String, TermInIndex> globalTermIds, LeafReaderContext lrc) throws IOException {
        BLTerms segmentTerms = (BLTerms) lrc.reader().terms(luceneField);
        if (segmentTerms == null) {
            // can happen if segment only contains index metadata doc
            return;
        }
        segmentTerms.setTermsIntegrated(this, lrc.ord);
        BlackLab40PostingsReader r = BlackLab40PostingsReader.get(lrc);
        TermsIntegratedSegment s = new TermsIntegratedSegment(r, luceneField, lrc.ord);

        Iterator<TermsIntegratedSegment.TermInSegment> it = s.iterator();
        int [] segmentToGlobal = segmentToGlobalTermIds.computeIfAbsent(s.ord(), __ -> new int[s.size()]);
        while (it.hasNext()) {
            TermsIntegratedSegment.TermInSegment t = it.next();
            TermInIndex tii = globalTermIds.computeIfAbsent(t.term, __ -> termInIndex(t.term, globalTermIds.size()));
            // Remember the mapping from segment id to global id
            segmentToGlobal[t.id] = tii.globalTermId;
            // Remember the sort position of this term in this segment, to save time comparing later
            tii.segmentPosSensitive[s.ord()] = t.sortPositionSensitive;
            tii.segmentPosInsensitive[s.ord()] = t.sortPositionInsensitive;
        }

        s.close();
    }

    private int[] determineSort(TermInIndex[] terms, boolean sensitive) {
        compareSensitive = sensitive;
        int[] sorted = new int[terms.length];
        for (int i = 0; i < terms.length; i++) {
            sorted[i] = i;
        }
        IntArrays.quickSort(sorted, (a, b) -> terms[a].compareTo(terms[b]));
        return sorted;
    }

    /**
     * Invert the given array so the values become the indexes and vice versa.
     *
     * @param array array to invert
     * @return inverted array
     */
    private int[] invert(TermInIndex[] terms, int[] array, boolean sensitive) {
        compareSensitive = sensitive;
        int[] result = new int[array.length];
        int prevSortPosition = -1;
        int prevTermId = -1;
        for (int i = 0; i < array.length; i++) {
            int termId = array[i];
            int sortPosition = i;
            if (prevTermId >= 0 && terms[prevTermId].compareTo(terms[termId]) == 0) {
                // Keep the same sort position because the terms are the same
                sortPosition = prevSortPosition;
            } else {
                // Remember the sort position in case the next term is identical
                prevSortPosition = sortPosition;
            }
            result[termId] = sortPosition;
            prevTermId = termId;
        }
        return result;
    }

    @Override
    public int[] segmentIdsToGlobalIds(int ord, int[] snippet) {
        int[] mapping = segmentToGlobalTermIds.get(ord);
        int[] converted = new int[snippet.length];
        for (int i = 0; i < snippet.length; i++) {
            converted[i] = snippet[i] < 0 ? snippet[i] : mapping[snippet[i]];
        }
        return converted;
    }

    public int segmentIdToGlobalId(int ord, int id) {
        int[] mapping = segmentToGlobalTermIds.get(ord);
        return id < 0 ? id : mapping[id];
    }

    public TermInIndex termInIndex(String term, int globalTermId) {
        return new TermInIndex(term, globalTermId, indexReader.leaves().size());
    }
}
