package nl.inl.blacklab.indexers.config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.util.BytesRef;

import nl.inl.blacklab.analysis.PayloadUtils;
import nl.inl.blacklab.contentstore.TextContent;
import nl.inl.blacklab.exceptions.BlackLabRuntimeException;
import nl.inl.blacklab.exceptions.InvalidInputFormatConfig;
import nl.inl.blacklab.exceptions.MalformedInputFile;
import nl.inl.blacklab.exceptions.MaxDocsReached;
import nl.inl.blacklab.index.DocIndexer;
import nl.inl.blacklab.index.DocIndexerAbstract;
import nl.inl.blacklab.index.DocumentFormats;
import nl.inl.blacklab.index.DownloadCache;
import nl.inl.blacklab.index.Indexer;
import nl.inl.blacklab.index.annotated.AnnotatedFieldWriter;
import nl.inl.blacklab.index.annotated.AnnotationWriter;
import nl.inl.blacklab.search.BlackLabIndexIntegrated;
import nl.inl.blacklab.search.indexmetadata.AnnotatedFieldNameUtil;
import nl.inl.blacklab.search.indexmetadata.IndexMetadataWriter;
import nl.inl.util.FileProcessor;
import nl.inl.util.StringUtil;
import nl.inl.util.UnicodeStream;

public abstract class DocIndexerBase extends DocIndexerAbstract {

    private static final boolean TRACE = false;

    /**
     * Position of start tags and their index in the annotation arrays, so we can add
     * payload when we find the end tags
     */
    static final class OpenTagInfo {

        public final String name;

        public final int index;

        public OpenTagInfo(String name, int index) {
            this.name = name;
            this.index = index;
        }
    }

    /** Annotated fields we're indexing. */
    private final Map<String, AnnotatedFieldWriter> annotatedFields = new LinkedHashMap<>();

    /**
     * A field named "contents", or, if that doesn't exist, the first annotated field
     * added.
     */
    private AnnotatedFieldWriter mainAnnotatedField;

    /** The indexing object for the annotated field we're currently processing. */
    private AnnotatedFieldWriter currentAnnotatedField;

    /** The tag annotation for the annotated field we're currently processing. */
    private AnnotationWriter annotStartTag;

    /** The main annotation for the annotated field we're currently processing. */
    private AnnotationWriter annotMain;

    /** The main annotation for the annotated field we're currently processing. */
    private AnnotationWriter annotPunct;

    /**
     * If no punctuation expression is defined, add a space between each word by
     * default.
     */
    private boolean addDefaultPunctuation = true;

    /**
     * If true, the next word gets no default punctuation even if
     * addDefaultPunctuation is true. Useful for implementing glue tag behaviour
     * (Sketch Engine WPL format)
     */
    private boolean preventNextDefaultPunctuation = false;

    /** For capturing punctuation between words. */
    private StringBuilder punctuation = new StringBuilder();

    /**
     * If true, we're indexing into an existing Lucene document. Don't overwrite it
     * with a new one.
     */
    private boolean indexingIntoExistingDoc = false;

    /** Currently opened inline tags we still need to add length payload to */
    private final List<OpenTagInfo> openInlineTags = new ArrayList<>();

    /**
     * Store documents? Can be set to false in ConfigInputFormat to if no content
     * store is desired, or via indexSpecificDocument to prevent storing linked
     * documents.
     */
    private boolean storeDocuments = true;

    /**
     * The content store we should store this document in. Also stored the content
     * store id in the field with this name with "Cid" appended, e.g. "metadataCid"
     * if useContentStore equals "metadata". This is used for storing linked
     * document, if desired. Normally null, meaning document should be stored in the
     * default field and content store (usually "contents", with the id in field
     * "contents#cid").
     */
    private String contentStoreName = null;

    /**
     * Total words processed by this indexer. Used for reporting progress, do not
     * reset except when finished with file.
     */
    protected int wordsDone = 0;
    private int wordsDoneAtLastReport = 0;
    private int charsDoneAtLastReport = 0;

    /**
     * What annotations where skipped because they were not declared?
     */
    final Set<String> skippedAnnotations = new HashSet<>();

    protected String getContentStoreName() {
        return contentStoreName;
    }

    protected void addAnnotatedField(AnnotatedFieldWriter field) {
        annotatedFields.put(field.name(), field);
        if (getDocWriter() != null) {
            IndexMetadataWriter indexMetadata = getDocWriter().indexWriter().metadata();
            indexMetadata.registerAnnotatedField(field);
        }
    }

    protected AnnotatedFieldWriter getMainAnnotatedField() {
        if (mainAnnotatedField == null) {
            // The "main annotated field" is the field that stores the document content id.
            // The main annotated field is a field named "contents" or, if that does not exist, the first
            // annotated field
            for (AnnotatedFieldWriter field : annotatedFields.values()) {
                if (mainAnnotatedField == null)
                    mainAnnotatedField = field;
                else if (field.name().equals("contents"))
                    mainAnnotatedField = field;
            }
        }
        return mainAnnotatedField;
    }

    protected AnnotatedFieldWriter getAnnotatedField(String name) {
        return annotatedFields.get(name);
    }

    protected Map<String, AnnotatedFieldWriter> getAnnotatedFields() {
        return Collections.unmodifiableMap(annotatedFields);
    }

    protected void setCurrentAnnotatedFieldName(String name) {
        currentAnnotatedField = getAnnotatedField(name);
        if (currentAnnotatedField == null)
            throw new InvalidInputFormatConfig("Tried to index annotated field " + name
                    + ", but field wasn't created. Likely cause: init() wasn't called. Did you call the base class method in index()?");
        annotStartTag = currentAnnotatedField.tagsAnnotation();
        annotMain = currentAnnotatedField.mainAnnotation();
        annotPunct = currentAnnotatedField.punctAnnotation();
    }

    protected void addStartChar(int pos) {
        currentAnnotatedField.addStartChar(pos);
    }

    protected void addEndChar(int pos) {
        currentAnnotatedField.addEndChar(pos);
    }

    protected AnnotationWriter getAnnotation(String name) {
        return currentAnnotatedField.annotation(name);
    }

    protected int getCurrentTokenPosition() {
        return annotMain.lastValuePosition() + 1;
    }

    protected AnnotationWriter tagsAnnotation() {
        return annotStartTag;
    }

    protected AnnotationWriter punctAnnotation() {
        return annotPunct;
    }

    @Deprecated
    protected AnnotationWriter propTags() {
        return tagsAnnotation();
    }

    @Deprecated
    protected AnnotationWriter propPunct() {
        return punctAnnotation();
    }

    protected void setPreventNextDefaultPunctuation() {
        preventNextDefaultPunctuation = true;
    }

    /**
     * Index a linked document.
     *
     * @param inputFile where the linked document can be found (file or http(s)
     *            reference)
     * @param pathInsideArchive if input file is an archive: the path to the file we
     *            need inside the archive
     * @param documentPath XPath to the specific linked document we need
     * @param inputFormatIdentifier input format of the linked document
     * @param storeWithName if set, store the linked document and store the id to it
     *            in a field with this name with "Cid" (content id) appended to it
     * @throws IOException on error
     */
    protected void indexLinkedDocument(String inputFile, String pathInsideArchive, String documentPath,
            String inputFormatIdentifier, String storeWithName) throws IOException {
        // Fetch the input file (either by downloading it to a temporary location, or opening it from disk)
        File f = resolveFileReference(inputFile);

        // Get the data
        byte[] data;
        String completePath = inputFile;
        if (inputFile.endsWith(".zip") || inputFile.endsWith(".tar") || inputFile.endsWith(".tar.gz")
                || inputFile.endsWith(".tgz")) {
            // It's an archive. Unpack the right file from it.
            completePath += "/" + pathInsideArchive;
            data = FileProcessor.fetchFileFromArchive(f, pathInsideArchive);
        } else {
            // Regular file.
            try (InputStream is = new FileInputStream(f)) {
                data = IOUtils.toByteArray(is);
            } catch (IOException e) {
                throw BlackLabRuntimeException.wrap(e);
            }
        }
        if (data == null) {
            throw new BlackLabRuntimeException("Error reading linked document");
        }

        // Index the data
        try (DocIndexer docIndexer = DocumentFormats.get(inputFormatIdentifier, getDocWriter(), completePath, data,
                Indexer.DEFAULT_INPUT_ENCODING)) {
            if (docIndexer instanceof DocIndexerBase) {
                DocIndexerBase ldi = (DocIndexerBase) docIndexer;
                ldi.indexingIntoExistingDoc = true;
                ldi.currentDoc = currentDoc;
                ldi.metadataFieldValues = metadataFieldValues;
                if (storeWithName != null) {
                    // If specified, store in this content store and under this name instead of the default
                    ldi.contentStoreName = storeWithName;
                }
                ldi.indexSpecificDocument(documentPath);
            } else {
                if (docIndexer == null)
                    throw new BlackLabRuntimeException("Could not instantiate linked DocIndexer, format not found? (" + inputFormatIdentifier + ")");
                else
                    throw new BlackLabRuntimeException("Linked document indexer must be subclass of DocIndexerBase, but is "
                        + docIndexer.getClass().getName());
            }
        }
    }

    /**
     * Index a specific document.
     *
     * Only supported by DocIndexerBase.
     *
     * @param documentExpr Expression (e.g. XPath) used to find the document to
     *            index in the file
     */
    public abstract void indexSpecificDocument(String documentExpr);

    /**
     * Given a URL or file reference, either download to a temp file or find file
     * and return it.
     *
     * @param inputFile URL or (relative) file reference
     * @return the file
     */
    protected File resolveFileReference(String inputFile) throws IOException {
        if (inputFile.startsWith("http://") || inputFile.startsWith("https://")) {
            return DownloadCache.downloadFile(inputFile);
        }
        if (inputFile.startsWith("file://"))
            inputFile = inputFile.substring(7);
        File f = getDocWriter().linkedFile(inputFile);
        if (f == null)
            throw new FileNotFoundException("Referenced file not found: " + inputFile);
        if (!f.canRead())
            throw new IOException("Cannot read referenced file: " + f);
        return f;
    }

    /**
     * If we've already seen this string, return the original copy.
     *
     * This prevents us from storing many copies of the same string.
     *
     * @param possibleDupe string we may already have stored
     * @return unique instance of the string
     */
    protected String dedupe(String possibleDupe) {
        return possibleDupe.intern();
    }

    protected void trace(String msg) {
        if (TRACE)
            System.out.print(msg);
    }

    protected void traceln(String msg) {
        if (TRACE)
            System.out.println(msg);
    }

    protected void setStoreDocuments(boolean storeDocuments) {
        this.storeDocuments = storeDocuments;
    }

    protected boolean isStoreDocuments() {
        return storeDocuments;
    }

    protected void startDocument() {

        traceln("START DOCUMENT");
        if (!indexingIntoExistingDoc) {
            currentDoc = createNewDocument();
            addMetadataField("fromInputFile", documentName);
        }
        if (getDocWriter() != null && !indexingIntoExistingDoc)
            getDocWriter().listener().documentStarted(documentName);
    }

    protected void endDocument() {
        traceln("END DOCUMENT");

        for (AnnotatedFieldWriter field : getAnnotatedFields().values()) {
            AnnotationWriter propMain = field.mainAnnotation();

            // Make sure all the annotations have an equal number of values.
            // See what annotation has the highest position
            // (in practice, only starttags and endtags should be able to have
            // a position one higher than the rest)
            int lastValuePos = 0;
            for (AnnotationWriter prop : field.annotationWriters()) {
                if (prop.lastValuePosition() > lastValuePos)
                    lastValuePos = prop.lastValuePosition();
            }

            // Make sure we always have one more token than the number of
            // words, so there's room for any tags after the last word, and we
            // know we should always skip the last token when matching.
            if (propMain.lastValuePosition() == lastValuePos)
                lastValuePos++;

            // Add empty values to all lagging annotations
            for (AnnotationWriter prop : field.annotationWriters()) {
                while (prop.lastValuePosition() < lastValuePos) {
                    prop.addValue("");
                    if (prop.hasPayload())
                        prop.addPayload(null);
                    if (prop == propMain) {
                        field.addStartChar(getCharacterPosition());
                        field.addEndChar(getCharacterPosition());
                    }
                }
            }
            // Store the different annotations of the annotated field that
            // were gathered in lists while parsing.
            field.addToDoc(currentDoc);

            // Add the field with all its annotations to the forward index
            addToForwardIndex(field);

        }

        if (isStoreDocuments()) {
            storeDocument();
        }

        if (!indexingIntoExistingDoc)
            addMetadataToDocument();
        try {
            // Add Lucene doc to indexer, if not existing already
            if (getDocWriter() != null && !indexingIntoExistingDoc)
                getDocWriter().add(currentDoc);
        } catch (Exception e) {
            throw BlackLabRuntimeException.wrap(e);
        }

        for (AnnotatedFieldWriter annotatedField : getAnnotatedFields().values()) {
            // Reset annotated field for next document
            // don't reuse buffers, they're still referenced by the lucene doc.
            annotatedField.clear();
        }

        // Report progress
        if (getDocWriter() != null) {
            reportCharsProcessed();
            reportTokensProcessed();
        }
        if (getDocWriter() != null && !indexingIntoExistingDoc)
            documentDone(documentName);

        currentDoc = null;

        // Stop if required
        if (getDocWriter() != null) {
            if (!getDocWriter().continueIndexing())
                throw new MaxDocsReached();
        }
    }

    /**
     * Store the entire document at once.
     *
     * Subclasses that simply capture the entire document can use this in their
     * storeDocument implementation.
     *
     * @param document document to store
     */
    protected void storeWholeDocument(String document) {
        storeWholeDocument(new TextContent(document));
    }

    /**
     * Store the entire document at once.
     *
     * Subclasses that simply capture the entire document can use this in their
     * storeDocument implementation.
     *
     * @param document document to store
     */
    protected void storeWholeDocument(TextContent document) {
        // Finish storing the document in the document store,
        // retrieve the content id, and store that in Lucene.
        // (Note that we do this after adding the "extra closing token", so the character
        // positions for the closing token still make (some) sense)
        String contentIdFieldName;
        String contentStoreName = getContentStoreName();
        if (contentStoreName == null) {
            AnnotatedFieldWriter main = getMainAnnotatedField();
            if (main == null) {
                // TODO: get rid of this special case!
                contentStoreName = "metadata";
                contentIdFieldName = "metadataCid";
            } else {
                contentStoreName = main.name();
                contentIdFieldName = AnnotatedFieldNameUtil.contentIdField(main.name());
            }
        } else {
            contentIdFieldName = contentStoreName + "Cid";
        }
        storeInContentStore(getDocWriter(), currentDoc, document, contentIdFieldName, contentStoreName);
    }

    /**
     * Store (or finish storing) the document in the content store.
     *
     * Also set the content id field so we know how to retrieve it later.
     */
    protected abstract void storeDocument();

    protected void inlineTag(String tagName, boolean isOpenTag, Map<String, String> attributes) {
        if (isOpenTag) {
            trace("<" + tagName + ">");

            tagsAnnotation().addValueAtPosition(tagName, getCurrentTokenPosition(), null);
            openInlineTags.add(new OpenTagInfo(tagName, tagsAnnotation().lastValueIndex()));

            for (Entry<String, String> e : attributes.entrySet()) {
                // Index element attribute values
                String name = e.getKey();
                String value = e.getValue();
                tagsAnnotation().addValueAtPosition(AnnotatedFieldNameUtil.tagAttributeIndexValue(name, value), getCurrentTokenPosition(), null);
            }

        } else {
            traceln("</" + tagName + ">");

            int currentPos = getCurrentTokenPosition();

            // Add payload to start tag annotation indicating end position
            if (openInlineTags.isEmpty())
                throw new MalformedInputFile("Close tag " + tagName + " found, but that tag is not open");
            OpenTagInfo openTag = openInlineTags.remove(openInlineTags.size() - 1);
            if (!openTag.name.equals(tagName))
                throw new MalformedInputFile(
                        "Close tag " + tagName + " found, but " + openTag.name + " expected");
            BytesRef payload = PayloadUtils.tagEndPositionPayload(currentPos);
            tagsAnnotation().setPayloadAtIndex(openTag.index, payload);
        }
    }

    protected void punctuation(String punct) {
        trace(punct);
        punctuation.append(punct);
    }

    protected void setAddDefaultPunctuation(boolean addDefaultPunctuation) {
        this.addDefaultPunctuation = addDefaultPunctuation;
    }

    /**
     * calls {@link #getCharacterPosition()}
     */
    protected void beginWord() {
        addStartChar(getCharacterPosition());
    }

    /**
     * calls {@link #getCharacterPosition()}
     */
    protected void endWord() {
        String punct;
        if (punctuation.length() == 0)
            punct = addDefaultPunctuation && !preventNextDefaultPunctuation ? " " : "";
        else
            punct = punctuation.toString();

        preventNextDefaultPunctuation = false;
        // Normalize once more in case we hit more than one adjacent punctuation
        punctAnnotation().addValue(StringUtil.normalizeWhitespace(punct));
        addEndChar(getCharacterPosition());
        wordsDone++;
        if (wordsDone > 0 && wordsDone % 5000 == 0) {
            reportCharsProcessed();
            reportTokensProcessed();
        }
        if (punctuation.length() > 10_000)
            punctuation = new StringBuilder(); // let's not hold on to this much memory
        else
            punctuation.setLength(0);
    }

    protected void annotation(String name, String value, int increment, List<Integer> indexAtPositions) {
        annotation(name, value, increment, indexAtPositions, -1);
    }

    /**
     * Index an annotation.
     *
     * Can be used to add annotation(s) at the current position (indexAtPositions ==
     * null), or to add annotations at specific positions (indexAtPositions contains
     * positions). The latter is used for standoff annotations.
     *
     * Also called for subannotations (with the value already prepared)
     *
     * @param name annotation name
     * @param value annotation value
     * @param increment if indexAtPosition == null: the token increment to use
     * @param indexAtPositions if null: index at the current position; otherwise:
     *            index at these positions
     * @param spanEndPos if >= 0, this is a span annotation and this is the first token position after the span
     */
    protected void annotation(String name, String value, int increment, List<Integer> indexAtPositions,
            int spanEndPos) {
        AnnotationWriter annotation = getAnnotation(spanEndPos >= 0 ? AnnotatedFieldNameUtil.TAGS_ANNOT_NAME : name);
        if (annotation != null) {
            BytesRef payload = null;
            if (spanEndPos >= 0) {
                // This is a span annotation. These are all indexed in one Lucene field (the annotation called "starttag")
                // with the attribute name and value combined.
                payload = PayloadUtils.tagEndPositionPayload(spanEndPos);
                if (name != null) {
                    // Attribute (otherwise it's the span name, e.g. "named-entity", which is indexed unchanged)
                    value = AnnotatedFieldNameUtil.tagAttributeIndexValue(name, value);
                }
            }
            if (indexAtPositions == null) {
                int position = annotation.lastValuePosition() + increment;
                if (name != null && name.equals(AnnotatedFieldNameUtil.DEFAULT_MAIN_ANNOT_NAME))
                    trace(value + " ");
                annotation.addValueAtPosition(value, position, payload);
            } else {
                for (Integer position : indexAtPositions) {
                    annotation.addValueAtPosition(value, position, payload);
                }
            }
        } else {
            // Annotation not declared; report, but keep going
            if (!skippedAnnotations.contains(name)) {
                skippedAnnotations.add(name);
                logger.error(documentName + ": skipping undeclared annotation " + name);
            }
        }
    }

    @Override
    public void addMetadataField(String fieldName, String value) {
        fieldName = optTranslateFieldName(fieldName);
        traceln("METADATA " + fieldName + "=" + value);
        super.addMetadataField(fieldName, value);
    }

    @Override
    public final void reportCharsProcessed() {
        final int charsDone = getCharacterPosition();
        final int charsDoneSinceLastReport;
        if (charsDoneAtLastReport > charsDone)
            charsDoneSinceLastReport = charsDone;
        else
            charsDoneSinceLastReport = charsDone - charsDoneAtLastReport;

        getDocWriter().listener().charsDone(charsDoneSinceLastReport);
        charsDoneAtLastReport = charsDone;
    }

    /**
     * Report the change in wordsDone since the last report
     */
    @Override
    public final void reportTokensProcessed() {
        final int wordsDoneSinceLastReport;
        if (wordsDoneAtLastReport > wordsDone) // wordsDone reset by child class? report everything then
            wordsDoneSinceLastReport = wordsDone;
        else
            wordsDoneSinceLastReport = wordsDone - wordsDoneAtLastReport;

        tokensDone(wordsDoneSinceLastReport);
        wordsDoneAtLastReport = wordsDone;
    }

    /**
     * @deprecated use {@link #setDocument(byte[], Charset)}
     * Set the document to index.
     *
     * NOTE: you should generally prefer calling the File or byte[] versions of this
     * method, as those can be more efficient (e.g. when using DocIndexer that
     * parses using VTD-XML).
     *
     * @param reader document
     */
    @Deprecated
    public abstract void setDocument(Reader reader);

    /**
     * Set the document to index.
     *
     * @param is document contents
     * @param cs charset to use if no BOM found, or null for the default (utf-8)
     */
    public void setDocument(InputStream is, Charset cs) {
        try {
            UnicodeStream unicodeStream = new UnicodeStream(is, cs);
            Charset detectedCharset = unicodeStream.getEncoding();
            setDocument(new InputStreamReader(unicodeStream, detectedCharset));
        } catch (IOException e) {
            throw BlackLabRuntimeException.wrap(e);
        }
    }

    /**
     *
     * Set the document to index.
     *
     * @param contents document contents
     * @param cs charset to use if no BOM found, or null for the default (utf-8)
     */
    public void setDocument(byte[] contents, Charset cs) {
        setDocument(new ByteArrayInputStream(contents), cs);
    }

    /**
     * Set the document to index.
     *
     * @param file file to index
     * @param charset charset to use if no BOM found, or null for the default
     *            (utf-8)
     * @throws FileNotFoundException if not found
     */
    public void setDocument(File file, Charset charset) throws FileNotFoundException {
        setDocument(new FileInputStream(file), charset);
    }

    /**
     * Are dashes forbidden in annotation names?
     *
     * True for classic index format, false for integrated index format.
     * Annotation names must be valid XML element names, which is why we sanitize certain
     * characters. But dashes are valid in XML element names. For compatibility, classic index
     * format still forbids dashes, but the new integrated index format allows them.
     *
     * @return true if dashes should be sanitized from annotation names
     */
    protected boolean disallowDashInname() {
        return !(getDocWriter().indexWriter() instanceof BlackLabIndexIntegrated);
    }

}
