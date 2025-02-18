package nl.inl.blacklab.index;

/**
 * A metadata fetcher can fetch the metadata for a document from some external
 * source (a file, the network, etc.) and add it to the Lucene document.
 */
abstract public class MetadataFetcher implements AutoCloseable {

    public final DocIndexer docIndexer;

    public MetadataFetcher(DocIndexerLegacy docIndexer) {
        this.docIndexer = docIndexer;
    }

    /**
     * Fetch the metadata for the document currently being indexed and add it to the
     * document as indexed fields.
     */
    abstract public void addMetadata();

    /**
     * Close the fetcher, releasing any resources it holds
     *
     */
    @Override
    public void close() {
        // Nothing, by default
    }

}
