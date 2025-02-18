package nl.inl.blacklab.codec;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.NormsProducer;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MappedMultiFields;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderSlice;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.BytesRef;

import it.unimi.dsi.fastutil.ints.IntArrays;
import nl.inl.blacklab.analysis.PayloadUtils;
import nl.inl.blacklab.codec.TokensCodec.VALUE_PER_TOKEN_PARAMETER;
import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.forwardindex.Collators;
import nl.inl.blacklab.search.BlackLabIndexIntegrated;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;

/**
 * BlackLab FieldsConsumer: writes postings information to the index,
 * using a delegate and extending its functionality by also writing a forward
 * index.
 *
 * Adapted from <a href="https://github.com/meertensinstituut/mtas/">MTAS</a>.
 */
public class BlackLab40PostingsWriter extends FieldsConsumer {

    protected static final Logger logger = LogManager.getLogger(BlackLab40PostingsWriter.class);

    private static final int NO_TERM = nl.inl.blacklab.forwardindex.Terms.NO_TERM;

    /** The FieldsConsumer we're adapting and delegating some requests to. */
    private final FieldsConsumer delegateFieldsConsumer;

    /** Holds common information used for writing to index files. */
    private final SegmentWriteState state;

    /** Name of the postings format we've adapted. */
    private final String delegatePostingsFormatName;

    /**
     * Instantiates a fields consumer.
     *
     * @param delegateFieldsConsumer FieldsConsumer to be adapted by us
     * @param state holder class for common parameters used during write
     * @param delegatePostingsFormatName name of the delegate postings format
     *                                   (the one our PostingsFormat class adapts)
     */
    public BlackLab40PostingsWriter(FieldsConsumer delegateFieldsConsumer, SegmentWriteState state,
            String delegatePostingsFormatName) {
        this.delegateFieldsConsumer = delegateFieldsConsumer;
        this.state = state;
        this.delegatePostingsFormatName = delegatePostingsFormatName;
    }

    /**
     * Merges in the fields from the readers in <code>mergeState</code>.
     *
     * Identical to {@link FieldsConsumer#merge}, essentially cancelling the delegate's
     * own merge method, e.g. FieldsWriter#merge in
     * {@link org.apache.lucene.codecs.perfield.PerFieldPostingsFormat}}.
     *
     * As suggested by the name and above comments, this seems to be related to segment merging.
     * Notice the call to write() at the end of the method, writing the merged segment to disk.
     *
     * (not sure why this is done; presumably the overridden merge method caused problems?
     * the javadoc for FieldsConsumer's version does mention that subclasses can provide more sophisticated
     * merging; maybe that interferes with this FieldsConsumer's customizations?)
     */
    @Override
    public void merge(MergeState mergeState, NormsProducer norms) throws IOException {
        final List<Fields> fields = new ArrayList<>();
        final List<ReaderSlice> slices = new ArrayList<>();

        int docBase = 0;

        for (int readerIndex = 0; readerIndex < mergeState.fieldsProducers.length; readerIndex++) {
            final FieldsProducer f = mergeState.fieldsProducers[readerIndex];

            final int maxDoc = mergeState.maxDocs[readerIndex];
            f.checkIntegrity();
            slices.add(new ReaderSlice(docBase, maxDoc, readerIndex));
            fields.add(f);
            docBase += maxDoc;
        }

        Fields mergedFields = new MappedMultiFields(mergeState,
                new MultiFields(fields.toArray(Fields.EMPTY_ARRAY),
                        slices.toArray(ReaderSlice.EMPTY_ARRAY)));
        write(mergedFields, norms);
    }

    /**
     * Called by Lucene to write fields, terms and postings.
     *
     * Seems to be called whenever a segment is written, either initially or after
     * a segment merge.
     *
     * Delegates to the default fields consumer, but also uses the opportunity
     * to write our forward index.
     *
     * @param fields fields to write
     * @param norms norms (not used by us)
     */
    @Override
    public void write(Fields fields, NormsProducer norms) throws IOException {


        // Content store: implement custom type of stored field for content store
        //   (that is removed before calling the delegate)

        // TODO: expand write() to recognize content store fields and write those to a content store file
        write(state.fieldInfos, fields);

        // TODO: wrap fields to filter out content store fields (that will be handled in our own write method)
        delegateFieldsConsumer.write(fields, norms);

    }

    /** 
     * Information about a Lucene field that represents a BlackLab annotation in the forward index.
     * A Field's information is only valid for the segment (leafreadercontext) of the index it was read from.
     * Contains offsets into files comprising the terms strings and forward index information.
     * Such as where in the term strings file the strings for this field begin.
     * See integrated.md
    */
    public static class Field {
        private final String fieldName;
        protected int numberOfTerms;
        protected long termOrderOffset;
        protected long termIndexOffset;
        protected long tokensIndexOffset;

        protected Field(String fieldName) {  this.fieldName = fieldName; }
        
        /** Read our values from the file */
        public Field(IndexInput file) throws IOException {
            this.fieldName = file.readString();
            this.numberOfTerms = file.readInt();
            this.termOrderOffset = file.readLong();
            this.termIndexOffset = file.readLong();
            this.tokensIndexOffset = file.readLong();
        }

        public Field(String fieldName, int numberOfTerms, long termIndexOffset, long termOrderOffset, long tokensIndexOffset) {
            this.fieldName = fieldName;
            this.numberOfTerms = numberOfTerms;
            this.termOrderOffset = termOrderOffset;
            this.termIndexOffset = termIndexOffset;
            this.tokensIndexOffset = tokensIndexOffset;
        }

        public String getFieldName() { return fieldName; }
        public int getNumberOfTerms() { return numberOfTerms; }
        public long getTermIndexOffset() { return termIndexOffset; }
        public long getTermOrderOffset() { return termOrderOffset; }
        public long getTokensIndexOffset() { return tokensIndexOffset; }

        public void write(IndexOutput file) throws IOException {
            file.writeString(getFieldName());
            file.writeInt(getNumberOfTerms());
            file.writeLong(getTermOrderOffset());
            file.writeLong(getTermIndexOffset());
            file.writeLong(getTokensIndexOffset());
        }
    }

    private static class FieldMutable extends Field {
        public FieldMutable(String fieldName) { super(fieldName); }

        public void setNumberOfTerms(int number) { this.numberOfTerms = number; }
        public void setTermIndexOffset(long offset) { this.termIndexOffset = offset; }
        public void setTermOrderOffset(long offset) { this.termOrderOffset = offset; }
        public void setTokensIndexOffset(long offset) { this.tokensIndexOffset = offset; }
    }

    /**
     * Write our additions to the default postings (i.e. the forward index)
     *
     * Iterates over the term vector to build the forward index in a temporary file.
     *
     * Tokens are sorted by field, term, doc, then position, so not by field, doc, position as
     * you might expect with a forward index. This is a temporary measure for efficiency.
     *
     * The second pass links all the doc+position for each term together and writes them to another
     * temporary file.
     *
     * Finally, everything is written to the final objects file in the correct order.
     *
     * This method also records metadata about fields in the FieldInfo attributes.
     */
    private void write(FieldInfos fieldInfos, Fields fields) {

        Map<String, FieldMutable> fiFields = new HashMap<>();

        try (   IndexOutput outTokensIndexFile = createOutput(BlackLab40PostingsFormat.TOKENS_INDEX_EXT);
                IndexOutput outTokensFile = createOutput(BlackLab40PostingsFormat.TOKENS_EXT);
                IndexOutput termIndexFile = createOutput(BlackLab40PostingsFormat.TERMINDEX_EXT);
                IndexOutput termsFile = createOutput(BlackLab40PostingsFormat.TERMS_EXT);
                IndexOutput termsOrderFile = createOutput(BlackLab40PostingsFormat.TERMORDER_EXT)
        ) {

            // Write our postings extension information


            // We'll keep track of doc lengths so we can preallocate our forward index structure.
            Map<Integer, Integer> docLengths = new HashMap<>();

            // First we write a temporary dump of the term vector, and keep track of
            // where we can find term occurrences per document so we can reverse this
            // file later.
            // (we iterate per field & term first, because that is how Lucene's reverse
            //  index stores the information. What we need is per field, then per document
            //  (we're trying to reconstruct the document), so we will do that below.
            //   we use temporary files because this might take a huge amount of memory)
            // (use a LinkedHashMap to maintain the same field order when we write the tokens below)
            Map<String/*field*/, SortedMap<Integer/*lucene doc id*/, Map<Integer/*termID*/, Long/*filePointer*/>>> field2docTermVecFileOffsets = new LinkedHashMap<>();
            try (IndexOutput outTempTermVectorFile = createOutput(BlackLab40PostingsFormat.TERMVEC_TMP_EXT)) {

                // Process fields
                for (String luceneField: fields) { // for each field
                    // If this field should get a forward index...
                    if (!BlackLabIndexIntegrated.isForwardIndexField(fieldInfos.fieldInfo(luceneField))) {
                        continue;
                    }
                    FieldMutable offsets = fiFields.computeIfAbsent(luceneField, FieldMutable::new);

                    // We're creating a forward index for this field.
                    // That also means that the payloads will include an "is-primary-value" indicator,
                    // so we know which value to store in the forward index (the primary value, i.e.
                    // the first value, to be used in concordances, sort, group, etc.).
                    // We must skip these indicators when working with other payload info. See PayloadUtils
                    // for details.

                    // Record starting offset of field in termindex file (written to fields file later)
                    offsets.setTermIndexOffset(termIndexFile.getFilePointer());

                    // Keep track of where to find term positions for each document
                    // (for reversing index)
                    // The map is keyed by docId and stores a list of offsets into the
                    // temporary termvector file where the occurrences for each term can be
                    // found.
                    Map<Integer, Map<Integer, Long>> docId2TermVecFileOffsets =
                            field2docTermVecFileOffsets.computeIfAbsent(luceneField, k -> new TreeMap<>());

                    // For each term in this field...
                    PostingsEnum postingsEnum = null; // we'll reuse this for efficiency
                    Terms terms = fields.terms(luceneField);
                    TermsEnum termsEnum = terms.iterator();


                    int termId = 0;
                    List<String> termsList = new ArrayList<>();

                    while (true) {
                        BytesRef term = termsEnum.next();
                        if (term == null)
                            break;

                        // Write the term to the terms file
                        String termString = term.utf8ToString();
                        termIndexFile.writeLong(termsFile.getFilePointer()); // where to find term string
                        termsFile.writeString(termString);
                        termsList.add(termString);

                        // For each document containing this term...
                        postingsEnum = termsEnum.postings(postingsEnum, PostingsEnum.POSITIONS | PostingsEnum.PAYLOADS);
                        while (true) {
                            Integer docId = postingsEnum.nextDoc();
                            if (docId.equals(DocIdSetIterator.NO_MORE_DOCS))
                                break;

                            // Keep track of term positions offsets in term vector file
                            Map<Integer, Long> vecFileOffsetsPerTermId =
                                    docId2TermVecFileOffsets.computeIfAbsent(docId, k -> new HashMap<>());
                            vecFileOffsetsPerTermId.put(termId, outTempTermVectorFile.getFilePointer());

                            // Go through each occurrence of term in this doc,
                            // gathering the positions where this term occurs as a "primary value"
                            // (the first value at this token position, which we will store in the
                            //  forward index). Also determine docLength.
                            int nOccurrences = postingsEnum.freq();
                            int docLength = docLengths.getOrDefault(docId, 0);
                            byte[] bytesPositions = new byte[nOccurrences * Integer.BYTES];
                            DataOutput positions = new ByteArrayDataOutput(bytesPositions);
                            int numOccurrencesWritten = 0;
                            for (int i = 0; i < nOccurrences; i++) {
                                int position = postingsEnum.nextPosition();
                                if (position >= docLength)
                                    docLength = position + 1;

                                // Is this a primary value or a secondary one?
                                // Primary values are e.g. the original word from the document,
                                // and will be stored in the forward index to be used for concordances,
                                // sorting and grouping. Secondary values may be synonyms or stemmed versions
                                // and will not be stored in the forward index.
                                BytesRef payload = postingsEnum.getPayload();
                                if (PayloadUtils.isPrimaryValue(payload)) {
                                    // primary value; write to buffer
                                    positions.writeInt(position);
                                    numOccurrencesWritten++;
                                }
                            }
                            docLengths.put(docId, docLength);

                            // Write the positions where this term occurs as primary value
                            // (will be reversed below to get the forward index)
                            outTempTermVectorFile.writeInt(numOccurrencesWritten);
                            if (numOccurrencesWritten > 0) {
                                outTempTermVectorFile.writeBytes(bytesPositions, 0,
                                        numOccurrencesWritten * Integer.BYTES);
                            }
                        }

                        termId++;
                    }

                    // begin writing term IDs and sort orders
                    Collators collators = Collators.defaultCollator();
                    int[] sensitivePos2TermID = getTermSortOrder(termsList, collators.get(MatchSensitivity.SENSITIVE));
                    int[] insensitivePos2TermID = getTermSortOrder(termsList, collators.get(MatchSensitivity.INSENSITIVE));
                    int[] termID2SensitivePos = invert(termsList, sensitivePos2TermID, collators.get(MatchSensitivity.SENSITIVE));
                    int[] termID2InsensitivePos = invert(termsList, insensitivePos2TermID, collators.get(MatchSensitivity.INSENSITIVE));

                    int numTerms = termsList.size();
                    fiFields.get(luceneField).setNumberOfTerms(numTerms);
                    fiFields.get(luceneField).setTermOrderOffset(termsOrderFile.getFilePointer());
                    // write out, specific order.
                    for (int i : termID2InsensitivePos) termsOrderFile.writeInt(i);
                    for (int i : insensitivePos2TermID) termsOrderFile.writeInt(i);
                    for (int i : termID2SensitivePos) termsOrderFile.writeInt(i);
                    for (int i : sensitivePos2TermID) termsOrderFile.writeInt(i);
                }
                CodecUtil.writeFooter(outTempTermVectorFile);
            }

            // Reverse the reverse index to create forward index
            // (this time we iterate per field and per document first, then reconstruct the document by
            //  looking at each term's occurrences. This produces our forward index)
            try (IndexInput inTermVectorFile = openInput(BlackLab40PostingsFormat.TERMVEC_TMP_EXT)) {

                // For each field...
                for (Entry<String, SortedMap<Integer, Map<Integer, Long>>> fieldEntry: field2docTermVecFileOffsets.entrySet()) {
                    String luceneField = fieldEntry.getKey();
                    SortedMap<Integer/*docID*/, Map<Integer/*termID*/, Long/*file offset*/>> docPosOffsets = fieldEntry.getValue();

                    // Record starting offset of field in tokensindex file (written to fields file later)
                    fiFields.get(luceneField).setTokensIndexOffset(outTokensIndexFile.getFilePointer());

                    // For each document...
                    for (int docId = 0; docId < state.segmentInfo.maxDoc(); docId++) {
                        int[] termIds = getDocumentContents(docId, docLengths, inTermVectorFile, docPosOffsets);
                        writeTokensInDoc(outTokensIndexFile, outTokensFile, termIds);
                    }
                }
            } finally {
                // Clean up after ourselves
                deleteIndexFile(BlackLab40PostingsFormat.TERMVEC_TMP_EXT);
            }

            // Write fields file, now that we know all the relevant offsets
            try (IndexOutput fieldsFile = createOutput(BlackLab40PostingsFormat.FIELDS_EXT)) {
                // for each field that has a forward index...
                for (Field field : fiFields.values()) {
                    // write the information to fields file, see integrated.md
                    field.write(fieldsFile);
                }
                CodecUtil.writeFooter(fieldsFile);
            }

            CodecUtil.writeFooter(outTokensIndexFile);
            CodecUtil.writeFooter(outTokensFile);
            CodecUtil.writeFooter(termIndexFile);
            CodecUtil.writeFooter(termsFile);
            CodecUtil.writeFooter(termsOrderFile);
        } catch (IOException e) {
            throw new BlackLabRuntimeException(e);
        }
    }

    private int[] getDocumentContents(int docId, Map<Integer, Integer> docLengths,
            IndexInput inTermVectorFile, SortedMap<Integer, Map<Integer, Long>> docPosOffsets)
            throws IOException {

        final Map<Integer/*term ID*/, Long/*file offset*/> termPosOffsets = docPosOffsets.getOrDefault(docId, Collections.emptyMap());
        final int docLength = docLengths.getOrDefault(docId, 0);
        final int[] tokensInDoc = new int[docLength]; // reconstruct the document here

        // NOTE: sometimes docs won't have any values for a field, but we'll
        //   still write all NO_TERMs in this case. This is similar to sparse
        //   fields (e.g. the field that stores <p> <s> etc.) which also have a
        //   lot of NO_TERMs.
        Arrays.fill(tokensInDoc, NO_TERM);

        // For each term...
        for (Entry<Integer/*term ID*/, Long/*file offset*/> e: termPosOffsets.entrySet()) {
            int termId = e.getKey();
            inTermVectorFile.seek(e.getValue());
            int nOccurrences = inTermVectorFile.readInt();
            // For each occurrence...
            for (int i = 0; i < nOccurrences; i++) {
                int position = inTermVectorFile.readInt();
                tokensInDoc[position] = termId;
            }
        }

        return tokensInDoc;
    }

    /**
     * Write the tokens to the tokens file.
     *
     * Also records offset, length and encoding in the tokens index file.
     *
     * Chooses the most appropriate encoding for the tokens and records this choice in
     * the tokens index file.
     *
     * @param outTokensIndexFile token index file
     * @param outTokensFile      tokens file
     * @param tokensInDoc        tokens to write
     * @throws IOException       When failing to write
     */
    private void writeTokensInDoc(IndexOutput outTokensIndexFile, IndexOutput outTokensFile, int[] tokensInDoc) throws IOException {
        int max = 0;
        boolean allTheSame = tokensInDoc.length > 0; // if no tokens, then not all the same.
        int last = -1;
        for (int token: tokensInDoc) {
            max = Math.max(max, token);
            allTheSame = allTheSame && (last == -1 || last == token);
            last = token;
            if (max > Short.MAX_VALUE && !allTheSame) // stop if already at worst case (int per token + not all the same).
                break;
        }

        // determine codec
        TokensCodec tokensCodec = allTheSame ? TokensCodec.ALL_TOKENS_THE_SAME : TokensCodec.VALUE_PER_TOKEN;

        // determine parameter byte for codec.
        byte tokensCodecParameter = 0;
        switch (tokensCodec) {
            case ALL_TOKENS_THE_SAME: tokensCodecParameter = 0; break;
            case VALUE_PER_TOKEN: {
                if (max <= Byte.MAX_VALUE) tokensCodecParameter = VALUE_PER_TOKEN_PARAMETER.BYTE.code;
                else if (max <= Short.MAX_VALUE) tokensCodecParameter = VALUE_PER_TOKEN_PARAMETER.SHORT.code;
                else if (max <= 0xFFFFFF) tokensCodecParameter = VALUE_PER_TOKEN_PARAMETER.THREE_BYTES.code;
                else tokensCodecParameter = VALUE_PER_TOKEN_PARAMETER.INT.code;
                break;
            }
            default: throw new NotImplementedException("Parameter byte determination for tokens codec " + tokensCodec + " not implemented.");
        }

        // Write offset in the tokens file, doc length in tokens and tokens codec used
        outTokensIndexFile.writeLong(outTokensFile.getFilePointer());
        outTokensIndexFile.writeInt(tokensInDoc.length);
        outTokensIndexFile.writeByte(tokensCodec.code);
        outTokensIndexFile.writeByte(tokensCodecParameter);

        if (tokensInDoc.length == 0) {
            return; // done.
        }

        // Write the tokens
        switch (tokensCodec) {
        case VALUE_PER_TOKEN: 
            switch (VALUE_PER_TOKEN_PARAMETER.fromCode(tokensCodecParameter)) {
                case BYTE: 
                    for (int token: tokensInDoc) {
                        outTokensFile.writeByte((byte) token);
                    }
                    break;
                case SHORT: 
                    for (int token: tokensInDoc) {
                        outTokensFile.writeShort((short) token);
                    }
                    break;
                case THREE_BYTES:
                    for (int token : tokensInDoc) {
                        outTokensFile.writeByte((byte)(token >> 16));
                        outTokensFile.writeByte((byte)(token >>  8));
                        outTokensFile.writeByte((byte) token);
                    }
                    break;
                case INT:
                    for (int token: tokensInDoc) {
                        outTokensFile.writeInt((int) token);
                    }
                    break;
                    default: throw new NotImplementedException("Handling for tokens codec " + tokensCodec + " with parameter " + tokensCodecParameter + " not implemented.");
                }
                break;
        case ALL_TOKENS_THE_SAME:
            outTokensFile.writeInt(tokensInDoc[0]);
            break;
        }
    }

    private IndexOutput createOutput(String ext) throws IOException {
        String fileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, ext);
        IndexOutput output = state.directory.createOutput(fileName, state.context);

        // Write standard header, with the codec name and version, segment info.
        // Also write the delegate codec name (Lucene's default codec).
        CodecUtil.writeIndexHeader(output, BlackLab40PostingsFormat.NAME, BlackLab40PostingsFormat.VERSION_CURRENT,
                state.segmentInfo.getId(), state.segmentSuffix);
        output.writeString(delegatePostingsFormatName);

        return output;
    }

    @SuppressWarnings("SameParameterValue")
    private IndexInput openInput(String ext) throws IOException {
        String fileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, ext);
        IndexInput input = state.directory.openInput(fileName, state.context);

        // Read and check standard header, with codec name and version and segment info.
        // Also check the delegate codec name (should be the expected version of Lucene's codec).
        CodecUtil.checkIndexHeader(input, BlackLab40PostingsFormat.NAME, BlackLab40PostingsFormat.VERSION_START,
                BlackLab40PostingsFormat.VERSION_CURRENT, state.segmentInfo.getId(), state.segmentSuffix);
        String delegatePFN = input.readString();
        if (!delegatePostingsFormatName.equals(delegatePFN))
            throw new IOException("Segment file " + fileName +
                    " contains wrong delegate postings format name: " + delegatePFN +
                    " (expected " + delegatePostingsFormatName + ")");

        return input;
    }

    @SuppressWarnings("SameParameterValue")
    private void deleteIndexFile(String ext) throws IOException {
        String fileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, ext);
        state.directory.deleteFile(fileName);
    }

    @Override
    public void close() throws IOException {
        delegateFieldsConsumer.close();
    }
    /**
     * Given a list of terms, return the indices to sort them.
     * E.G: getTermSortOrder(['b','c','a']) --> [2, 0, 1]
     */
    private static int[] getTermSortOrder(List<String> terms, Collator coll) {
        int[] ret = new int[terms.size()];
        for (int i = 0; i < ret.length; ++i) ret[i] = i;
        IntArrays.quickSort(ret, (a, b) -> coll.compare(terms.get(a), terms.get(b)));
        return ret;
    }

    
    /**
     * Invert the given array so the values become the indexes and vice versa.
     *
     * @param array array to invert
     * @return inverted array
     */
    private static int[] invert(List<String> terms, int[] array, Collator collator) {
        int[] result = new int[array.length];
        int prevSortPosition = -1;
        int prevTermId = -1;
        for (int i = 0; i < array.length; i++) {
            int termId = array[i];
            int sortPosition = i;
            if (prevTermId >= 0 && collator.equals(terms.get(prevTermId), terms.get(termId))) {
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
}
