package nl.inl.blacklab.forwardindex;

import java.text.Collator;
import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.collections.api.set.primitive.MutableIntSet;

import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.hash.TIntObjectHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import nl.inl.blacklab.Constants;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;

public abstract class TermsReaderAbstract implements Terms {
    protected static final Logger logger = LogManager.getLogger(TermsReaderAbstract.class);

    /**
     * A helper array that's re-used.
     *
     * Returned by getOffsetAndLength() and used by get(termId).
     * Could be eliminated by inlining getOffsetAndLength()?
     */
    protected final ThreadLocal<int[]> arrayAndOffsetAndLength = ThreadLocal.withInitial(() -> new int[3]);

    /** How many terms total are there? (always valid) */
    private int numberOfTerms;

    /**
     * Collator to use for string comparisons
     */
    protected final Collator collator;

    /**
     * Collator to use for insensitive string comparisons
     */
    protected final Collator collatorInsensitive;

    /** Insensitive sort position to start index of group in groupId2TermIds */
    private int[] insensitivePosition2GroupId;

    /** Sensitive sort position to start index of group in groupId2TermIds */
    private int[] sensitivePosition2GroupId;

    /** Term id to sensitive sort position */
    private int[] termId2SensitivePosition;

    /** Term id to sensitive sort position */
    private int[] termId2InsensitivePosition;

    /**
     * Contains a leading int specifying how many ids for a given group, followed by the list of ids.
     * For a group of size 2 containing the ids 4 and 8, contains [...2, 4, 9, ...]
     * {@link #insensitivePosition2GroupId} and {@link #sensitivePosition2GroupId} contain the index of the leading int
     * in this array for all sensitive/insensitive sorting positions respectively.
     */
    private int[] groupId2TermIds;

    /**
     * The character data for all terms. Two-dimensional array because it may be larger than
     * the maximum array size ({@link Constants#JAVA_MAX_ARRAY_SIZE}, roughly Integer.MAX_VALUE)
     */
    private byte[][] termCharData;

    /**
     * Gives a position in the termCharData array for each term id.
     *
     * Lower 32 bits indicate the array, upper 32 bits indicate the index within the {@link #termCharData} array.
     * This is needed to allow more than 2gb of term character data.
     *
     * Term length will follow from the positions of the next term (see {@link #getOffsetAndLength(int)}).
     */
    private long[] termId2CharDataOffset;

    public TermsReaderAbstract(Collators collators) {
        this.collator = collators.get(MatchSensitivity.SENSITIVE);
        this.collatorInsensitive = collators.get(MatchSensitivity.INSENSITIVE);
    }

    protected void finishInitialization(String[] terms, int[] termId2SensitivePosition,
            int[] termId2InsensitivePosition) {

        numberOfTerms = terms.length;

        // Invert the mapping of term id-> insensitive sort position into insensitive sort position -> term ids
        int numGroupsThatAreNotSizeOne = 0;
        TIntObjectHashMap<IntArrayList> insensitivePosition2TermIds = new TIntObjectHashMap<>(numberOfTerms);
        for(int termId = 0; termId < termId2InsensitivePosition.length; ++termId) {
            int insensitivePosition = termId2InsensitivePosition[termId];
            IntArrayList v = new IntArrayList(1);
            v.add(termId);

            IntArrayList prev = insensitivePosition2TermIds.put(insensitivePosition, v);
            if (prev != null) {
                v.addAll(prev);

                if (prev.size() == 1)
                    ++numGroupsThatAreNotSizeOne;
            }
        }

        fillTermDataGroups(terms, termId2SensitivePosition, termId2InsensitivePosition, insensitivePosition2TermIds, numGroupsThatAreNotSizeOne);
        fillTermCharData(terms);
    }

    // TODO optimize by removing the 1 at groupId < terms.length
    // Since we know it's always there (no collisions in this section - length is always 1)
    /**
     * Initializes the following members:
     * - {@link #termId2SensitivePosition}
     * - {@link #termId2InsensitivePosition}
     * - {@link #groupId2TermIds}
     * - {@link #sensitivePosition2GroupId}
     * - {@link #insensitivePosition2GroupId}
     *
     * @param numGroupsThatAreNotSizeOne in the insensitive hashmap - used to initialize the groupId2termIds map at the right length.
     */
    protected void fillTermDataGroups(String[] terms, int[] termId2SortPositionSensitive,
            int[] termId2SortPositionInsensitive, TIntObjectHashMap<IntArrayList> insensitiveSortPosition2TermIds,
            int numGroupsThatAreNotSizeOne) {
        // This is a safe upper bound: one group per sensitive (with one entry) = 2*numberOfTerms.
        // Then for the insensitive side, one group per entry in insensitiveSortPosition2TermIds + 1 int for each term
        // in reality this is the maximum upper bound.
        // to accurately do this we'd need to know the number of groups with only one entry

        int numGroupsOfSizeOne = insensitiveSortPosition2TermIds.size() - numGroupsThatAreNotSizeOne;
        int numTermsInGroupsAboveSizeOne = terms.length - numGroupsOfSizeOne;

        this.termId2SensitivePosition = termId2SortPositionSensitive;
        this.termId2InsensitivePosition = termId2SortPositionInsensitive;
        // to be filled
        this.groupId2TermIds = new int[terms.length * 2 /* sensitive groups - all size 1 */ + numGroupsThatAreNotSizeOne
                + numTermsInGroupsAboveSizeOne];
        this.insensitivePosition2GroupId = new int[terms.length]; // NOTE: since not every insensitive sort position exists, this will have empty spots
        this.sensitivePosition2GroupId = new int[terms.length];

        Arrays.fill(this.insensitivePosition2GroupId, -1);

        // First create all sensitive entries
        int offset = 0;
        for (int termId = 0; termId < termId2SortPositionSensitive.length; ++termId) {
            final int positionSensitive = termId2SortPositionSensitive[termId];

            this.sensitivePosition2GroupId[positionSensitive] = offset;
            this.groupId2TermIds[offset++] = 1; // sensitive positions are unique (1 per term) - so group is size always 1
            this.groupId2TermIds[offset++] = termId; // and contains this term.
        }

        // now place all insensitives
        TIntObjectIterator<IntArrayList> it = insensitiveSortPosition2TermIds.iterator();
        while (it.hasNext()) {
            it.advance();

            final int insensitivePosition = it.key();
            final IntArrayList termIds = it.value();
            final int numTermIds = termIds.size();

            // reuse sensitive group when it contains the same data
            if (numTermIds == 1) {
                final int termId = termIds.getInt(0);
                final int sensitivePosition = this.termId2SensitivePosition[termId];
                final int groupId = this.sensitivePosition2GroupId[sensitivePosition];

                this.insensitivePosition2GroupId[insensitivePosition] = groupId;
                continue;
            }

            // cannot share group - not the same members. Create a new one
            this.insensitivePosition2GroupId[insensitivePosition] = offset;
            this.groupId2TermIds[offset++] = numTermIds;
            for (int i = 0; i < numTermIds; ++i) {
                groupId2TermIds[offset++] = termIds.getInt(
                        i); // NOTE: became termIds.getInt(0) in move to IntArrayList; probably a typo?
            }
        }

        // fill empty spots using the last good entry
        // if we don't do this binary searching over this array won't work (as it contains random uninitialized values and if we land on one of them we'd compare wrong)
        int last = 0;
        for (int i = 0; i < this.insensitivePosition2GroupId.length; ++i) {
            if (this.insensitivePosition2GroupId[i] != -1)
                last = this.insensitivePosition2GroupId[i];
            else
                this.insensitivePosition2GroupId[i] = last;
        }

        if (offset < groupId2TermIds.length) {
            throw new RuntimeException("what is going on here");
        }
    }

    /**
     * Converts terms string array to byte data and stored offsets.
     *
     * Initializes
     * - {@link #termCharData}
     * - {@link #termId2CharDataOffset}
     */
    protected void fillTermCharData(String[] terms) {
        // convert all to byte[] and tally total number of bytes
        // free the String instances while doing this so memory usage doesn't spike so much
        this.termId2CharDataOffset = new long[numberOfTerms];
        byte[][] bytes = new byte[numberOfTerms][];
        long bytesRemainingToBeWritten = 0;
        for (int i = 0; i < numberOfTerms; ++i) {
            byte[] b = terms[i].getBytes(DEFAULT_CHARSET);
            terms[i] = null;
            bytes[i] = b;
            bytesRemainingToBeWritten += b.length;
        }

        byte[][] termCharData = new byte[0][];
        byte[] curArray;
        for (int termIndex = 0; termIndex < numberOfTerms; ++termIndex) {

            // allocate new term bytes array, subtract what will fit
            final int curArrayLength = (int) Long.min(bytesRemainingToBeWritten, Integer.MAX_VALUE);
            curArray = new byte[curArrayLength];

            // now write terms until the array runs out of space or we have written all remaining terms
            // FIXME this code breaks when char term data total more than 2 GB
            //       (because offset will overflow)
            int offset = termCharData.length * Integer.MAX_VALUE; // set to beginning of current array
            while (termIndex < numberOfTerms) {
                final byte[] termBytes = bytes[termIndex];
                if ((offset + termBytes.length) > curArrayLength) {
                    --termIndex; /* note we didn't write this term yet, so re-process it next iteration */
                    break;
                }
                bytes[termIndex] = null;  // free original byte[], only do after we verify it can be copied!

                this.termId2CharDataOffset[termIndex] = offset;

                System.arraycopy(termBytes, 0, curArray, offset, termBytes.length);

                offset += termBytes.length;
                ++termIndex;
                bytesRemainingToBeWritten -= termBytes.length;
            }

            // add the (now filled) current array to the set.
            byte[][] tmp = termCharData;
            termCharData = new byte[tmp.length + 1][];
            System.arraycopy(tmp, 0, termCharData, 0, tmp.length);
            termCharData[termCharData.length - 1] = curArray;

            // and go to the top (allocate new array - copy remaining terms..)
        }

        this.termCharData = termCharData;
    }

    @Override
    public int indexOf(String term) {
        final int groupId = getGroupId(term, MatchSensitivity.SENSITIVE);
        if (groupId == -1)
            return -1;
        // Return the first term in this group
        return this.groupId2TermIds[groupId + 1];
    }

    @Override
    public void indexOf(MutableIntSet results, String term, MatchSensitivity sensitivity) {
        final int groupId = getGroupId(term, sensitivity);
        if (groupId == -1) {
            results.add(-1);
            return;
        }

        final int groupSize = this.groupId2TermIds[groupId];
        for (int i = 0; i < groupSize; ++i) {
            results.add(this.groupId2TermIds[groupId + 1 + i]);
        }
    }

    @Override
    public int numberOfTerms() {
        return numberOfTerms;
    }

    @Override
    public int idToSortPosition(int id, MatchSensitivity sensitivity) {
        return sensitivity.isCaseSensitive() ? this.getSortPositionSensitive(id) : this.getSortPositionInsensitive(id);
    }

    private int getSortPositionSensitive(int termId) {
        if (termId < 0 || termId >= numberOfTerms) {
            return -1;
        }
        return this.termId2SensitivePosition[termId];
    }

    private int getSortPositionInsensitive(int termId) {
        if (termId < 0 || termId >= numberOfTerms) {
            return -1;
        }
        return this.termId2InsensitivePosition[termId];
    }

    @Override
    public String get(int id) {
        if (id >= numberOfTerms || id < 0) {
            return "";
        }
        final int[] arrayAndOffsetAndLength = getOffsetAndLength(id);
        return new String(termCharData[arrayAndOffsetAndLength[0]], arrayAndOffsetAndLength[1],
                arrayAndOffsetAndLength[2], DEFAULT_CHARSET);
    }

    @Override
    public boolean termsEqual(int[] termId, MatchSensitivity sensitivity) {
        if (termId.length < 2)
            return true;

        // sensitive compare - just get the sort index
        if (sensitivity.isCaseSensitive()) {
            int expected = getSortPositionSensitive(termId[0]);
            for (int termIdIndex = 1; termIdIndex < termId.length; ++termIdIndex) {
                int cur = getSortPositionSensitive(termId[termIdIndex]);
                if (cur != expected)
                    return false;
            }
            return true;
        }

        // insensitive compare - get the insensitive sort index
        int expected = getSortPositionInsensitive(termId[0]);
        for (int termIdIndex = 1; termIdIndex < termId.length; ++termIdIndex) {
            int cur = getSortPositionInsensitive(termId[termIdIndex]);
            if (cur != expected)
                return false;
        }
        return true;
    }

    /**
     * Returns the threadlocal arrayAndOffsetAndLength, the array is reused between calls.
     * index 0 contains the char array
     * index 1 contains the offset within the char array
     * index 2 contains the length
     *
     * @return the
     */
    private int[] getOffsetAndLength(int termId) {
        final int[] arrayAndOffsetAndLength = this.arrayAndOffsetAndLength.get();
        final long offset = this.termId2CharDataOffset[termId];
        final int arrayIndex = (int) (offset >> 32);
        final int indexInArray = (int) (offset & 0xffffffffL); // only keep upper 32 bits

        // Determine the length of this term from the positions of the next term
        final boolean isLastTermInArray =
                termId == (numberOfTerms - 1) || (((int) (this.termId2CharDataOffset[termId + 1] >> 32)) != arrayIndex);
        int length = 0;
        if (isLastTermInArray) {
            // This term is the last in its array.
            final byte[] relevantArray = termCharData[arrayIndex];
            // find first null byte, that will terminate the string (or else until the border of the array)
            while (relevantArray.length > (indexInArray + length) && relevantArray[indexInArray + length] != 0) {
                ++length;
            }
        } else {
            // Next term is also in this array, so just take the difference between the two offsets.
            length = (int) (termId2CharDataOffset[termId + 1] - offset);
        }

        arrayAndOffsetAndLength[0] = arrayIndex;
        arrayAndOffsetAndLength[1] = indexInArray;
        arrayAndOffsetAndLength[2] = length;
        return arrayAndOffsetAndLength;
    }

    private int getGroupId(String term, MatchSensitivity sensitivity) {
        final Collator coll = sensitivity.isCaseSensitive() ? this.collator : this.collatorInsensitive;
        final int[] sortPosition2GroupId = sensitivity.isCaseSensitive() ?
                this.sensitivePosition2GroupId :
                this.insensitivePosition2GroupId;

        // binary search
        int l = 0;
        int r = sortPosition2GroupId.length - 1;

        int matchingGroupId = -1;
        while (l <= r) {
            final int sortPositionToCheck = l + (r - l) / 2;
            final int groupId = sortPosition2GroupId[sortPositionToCheck];
            final int termIdToCompareTo = this.groupId2TermIds[groupId + 1]; // TODO < numterms optimization
            final String termToCompareTo = get(termIdToCompareTo);

            final int result = coll.compare(term, termToCompareTo);
            if (result == 0) {
                matchingGroupId = groupId;
                break;
            }

            if (result < 0) {
                // position we're looking for is before this result, move right (upper) boundary to just before current position
                r = sortPositionToCheck - 1;
            } else {
                // position we're looking for is after this result, move left (lower) boundary to just after current position
                l = sortPositionToCheck + 1;
            }
        }

        return matchingGroupId;
    }
}
