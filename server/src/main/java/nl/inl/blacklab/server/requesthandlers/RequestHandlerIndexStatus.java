package nl.inl.blacklab.server.requesthandlers;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

import nl.inl.blacklab.search.indexmetadata.IndexMetadata;
import nl.inl.blacklab.server.BlackLabServer;
import nl.inl.blacklab.server.datastream.DataStream;
import nl.inl.blacklab.server.exceptions.BlsException;
import nl.inl.blacklab.server.lib.User;
import nl.inl.blacklab.server.lib.results.ResultIndexStatus;
import nl.inl.blacklab.server.lib.results.WebserviceOperations;

/**
 * Get information about the status of an index.
 */
public class RequestHandlerIndexStatus extends RequestHandler {

    public RequestHandlerIndexStatus(BlackLabServer servlet, HttpServletRequest request, User user, String indexName,
            String urlResource, String urlPathPart) {
        super(servlet, request, user, indexName, urlResource, urlPathPart);
    }

    @Override
    public boolean isCacheAllowed() {
        return false; // because status might change
    }

    @Override
    public int handle(DataStream ds) throws BlsException {
        ResultIndexStatus progress = WebserviceOperations.resultIndexStatus(params);
        dstreamIndexStatusResponse(ds, progress);
        return HTTP_OK;
    }

    private void dstreamIndexStatusResponse(DataStream ds, ResultIndexStatus progress) {
        IndexMetadata metadata = progress.getMetadata();
        ds.startMap();
        {
            ds.entry("indexName", indexName);
            ds.entry("displayName", metadata.custom().get("displayName", ""));
            ds.entry("description", metadata.custom().get("description", ""));
            ds.entry("status", progress.getIndexStatus());
            if (!StringUtils.isEmpty(metadata.documentFormat()))
                ds.entry("documentFormat", metadata.documentFormat());
            ds.entry("timeModified", metadata.timeModified());
            ds.entry("tokenCount", metadata.tokenCount());
            DStream.indexProgress(ds, progress);
        }
        ds.endMap();
    }

}
