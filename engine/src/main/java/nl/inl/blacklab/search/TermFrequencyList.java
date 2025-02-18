package nl.inl.blacklab.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.collections.api.map.primitive.MutableIntIntMap;
import org.eclipse.collections.api.tuple.primitive.IntIntPair;
import org.eclipse.collections.impl.factory.primitive.IntIntMaps;

import nl.inl.blacklab.Constants;
import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.forwardindex.Terms;
import nl.inl.blacklab.resultproperty.PropertyValue;
import nl.inl.blacklab.resultproperty.ResultProperty;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;
import nl.inl.blacklab.search.results.ContextSize;
import nl.inl.blacklab.search.results.Contexts;
import nl.inl.blacklab.search.results.Hits;
import nl.inl.blacklab.search.results.QueryInfo;
import nl.inl.blacklab.search.results.ResultGroups;
import nl.inl.blacklab.search.results.ResultsList;
import nl.inl.blacklab.search.results.SampleParameters;

/**
 * A collection of tokens and their (absolute) frequencies.
 *
 * This class calculates the total frequency of the entries added, but you can
 * also set the total frequency explicitly (after all entries have been added)
 * if you want to calculate relative frequencies based on a different total.
 */
public class TermFrequencyList extends ResultsList<TermFrequency, ResultProperty<TermFrequency>> {

    /**
     * Count occurrences of context words around hit.
     *
     * @param hits hits to get collocations for
     * @param annotation annotation to use for the collocations, or null if default
     * @param contextSize how many words around hits to use
     * @param sensitivity what sensitivity to use
     * @param sort whether or not to sort the list by descending frequency
     *
     * @return the frequency of each occurring token
     */
    public synchronized static TermFrequencyList collocations(Hits hits, Annotation annotation, ContextSize contextSize, MatchSensitivity sensitivity, boolean sort) {
        BlackLabIndex index = hits.index();
        if (annotation == null)
            annotation = index.mainAnnotatedField().mainAnnotation();
        if (contextSize == null)
            contextSize = index.defaultContextSize();
        if (sensitivity == null)
            sensitivity = annotation.sensitivity(index.defaultMatchSensitivity()).sensitivity();

        List<Annotation> annotations = List.of(annotation);
        Contexts contexts = new Contexts(hits, annotations, contextSize);
        MutableIntIntMap countPerWord = IntIntMaps.mutable.empty();
        for (int[] context: contexts) {
            // Count words
            int contextHitStart = context[Contexts.HIT_START_INDEX];
            int contextRightStart = context[Contexts.RIGHT_START_INDEX];
            int contextLength = context[Contexts.LENGTH_INDEX];
            int indexInContent = Contexts.NUMBER_OF_BOOKKEEPING_INTS;
            for (int i = 0; i < contextLength; i++, indexInContent++) {
                if (i >= contextHitStart && i < contextRightStart)
                    continue; // don't count words in hit itself, just around [option..?]
                int wordId = context[indexInContent];
                int count;
                if (!countPerWord.containsKey(wordId))
                    count = 1;
                else
                    count = countPerWord.get(wordId) + 1;
                countPerWord.put(wordId, count);
            }
        }

        // Get the actual words from the sort positions
        Terms terms = index.annotationForwardIndex(contexts.annotations().get(0)).terms();
        Map<String, Integer> wordFreq = new HashMap<>();
        for (IntIntPair e : countPerWord.keyValuesView()) {
            int wordId = e.getOne();
            int count = e.getTwo();
            String word = sensitivity.desensitize(terms.get(wordId));
            // Note that multiple ids may map to the same word (because of sensitivity settings)
            // Here, those groups are merged.
            Integer mergedCount = wordFreq.get(word);
            if (mergedCount == null) {
                mergedCount = 0;
            }
            mergedCount += count;
            wordFreq.put(word, mergedCount);
        }

        // Transfer from map to list
        return new TermFrequencyList(hits.queryInfo(), wordFreq, sort);
    }

    long totalFrequency;

    public TermFrequencyList(QueryInfo queryInfo, Map<String, Integer> wordFreq, boolean sort) {
        super(queryInfo);
        if (wordFreq.size() >= Constants.JAVA_MAX_ARRAY_SIZE) {
            // (NOTE: List.size() will return Integer.MAX_VALUE if there's more than that number of items)
            throw new BlackLabRuntimeException("Cannot handle more than " + Constants.JAVA_MAX_ARRAY_SIZE + " termfrequencies");
        }
        results = new ArrayList<>(wordFreq.size());
        for (Map.Entry<String, Integer> e : wordFreq.entrySet()) {
            results.add(new TermFrequency(e.getKey(), e.getValue()));
        }
        if (sort) {
            results.sort(Comparator.naturalOrder());
        }
        calculateTotalFrequency();
    }

    TermFrequencyList(QueryInfo queryInfo, List<TermFrequency> list) {
        super(queryInfo);
        if (list.size() >= Constants.JAVA_MAX_ARRAY_SIZE) {
            // (NOTE: List.size() will return Integer.MAX_VALUE if there's more than that number of items)
            throw new BlackLabRuntimeException("Cannot handle more than " + Constants.JAVA_MAX_ARRAY_SIZE + " termfrequencies");
        }
        this.results = list;
        calculateTotalFrequency();
    }

    private void calculateTotalFrequency() {
        totalFrequency = 0;
        for (TermFrequency fr: results) {
            totalFrequency += fr.frequency;
        }
    }

    @Override
    public long size() {
        return results.size();
    }

    @Override
    public Iterator<TermFrequency> iterator() {
        return results.iterator();
    }

    @Override
    public TermFrequency get(long index) {
        return results.get((int)index);
    }

    /**
     * Get the frequency of a specific token
     *
     * @param token the token to get the frequency for
     * @return the frequency
     */
    public long frequency(String token) {
        // TODO: maybe speed this up by keeping a map of tokens and frequencies?
        //       (or if sorted by freq, use binary search)
        for (TermFrequency tf : results) {
            if (tf.term.equals(token))
                return tf.frequency;
        }
        return 0;
    }

    public long totalFrequency() {
        return totalFrequency;
    }

    public TermFrequencyList subList(long fromIndex, long toIndex) {
        if (fromIndex < 0 || toIndex < 0 || fromIndex > results.size() || toIndex > results.size())
            throw new BlackLabRuntimeException("index out of range");
        return new TermFrequencyList(queryInfo(), results.subList((int)fromIndex, (int)toIndex));
    }

    @Override
    protected void ensureResultsRead(long number) {
        // NOP
    }

    @Override
    public ResultGroups<TermFrequency> group(ResultProperty<TermFrequency> criteria,
                                             long maxResultsToStorePerGroup) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TermFrequencyList filter(ResultProperty<TermFrequency> property, PropertyValue value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TermFrequencyList sort(ResultProperty<TermFrequency> sortProp) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TermFrequencyList window(long first, long windowSize) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TermFrequencyList sample(SampleParameters sampleParameters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean doneProcessingAndCounting() {
        return true;
    }

    @Override
    public long numberOfResultObjects() {
        return results.size();
    }

}
