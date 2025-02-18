package nl.inl.blacklab.server.requesthandlers;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import nl.inl.blacklab.exceptions.InterruptedSearch;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.instrumentation.RequestInstrumentationProvider;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.server.BlackLabServer;
import nl.inl.blacklab.server.datastream.DataFormat;
import nl.inl.blacklab.server.datastream.DataStream;
import nl.inl.blacklab.server.exceptions.BlsException;
import nl.inl.blacklab.server.exceptions.IndexNotFound;
import nl.inl.blacklab.server.index.Index;
import nl.inl.blacklab.server.index.Index.IndexStatus;
import nl.inl.blacklab.server.index.IndexManager;
import nl.inl.blacklab.server.lib.IndexUtil;
import nl.inl.blacklab.server.lib.PlainWebserviceParams;
import nl.inl.blacklab.server.lib.User;
import nl.inl.blacklab.server.lib.WebserviceParamsImpl;
import nl.inl.blacklab.server.search.SearchManager;
import nl.inl.blacklab.server.util.WebserviceUtil;

/**
 * Base class for request handlers, to handle the different types of requests.
 * The static handle() method will dispatch the request to the appropriate
 * subclass.
 */
public abstract class RequestHandler {
    static final Logger logger = LogManager.getLogger(RequestHandler.class);

    public static final int HTTP_OK = HttpServletResponse.SC_OK;

    protected RequestInstrumentationProvider instrumentationProvider;

    /** The available request handlers by name */
    static final Map<String, Class<? extends RequestHandler>> availableHandlers;

    // Fill the map with all the handler classes
    static {
        availableHandlers = new HashMap<>();
        availableHandlers.put("debug", RequestHandlerDebug.class);
        availableHandlers.put("docs", RequestHandlerDocs.class);
        availableHandlers.put("docs-grouped", RequestHandlerDocsGrouped.class);
        availableHandlers.put("docs-csv", RequestHandlerDocsCsv.class);
        availableHandlers.put("docs-grouped-csv", RequestHandlerDocsCsv.class);
        availableHandlers.put("doc-contents", RequestHandlerDocContents.class);
        availableHandlers.put("doc-snippet", RequestHandlerDocSnippet.class);
        availableHandlers.put("doc-info", RequestHandlerDocInfo.class);
        availableHandlers.put("fields", RequestHandlerFieldInfo.class);
        //availableHandlers.put("help", RequestHandlerBlsHelp.class);
        availableHandlers.put("hits", RequestHandlerHits.class);
        availableHandlers.put("hits-grouped", RequestHandlerHitsGrouped.class);
        availableHandlers.put("hits-csv", RequestHandlerHitsCsv.class);
        availableHandlers.put("hits-grouped-csv", RequestHandlerHitsCsv.class);
        availableHandlers.put("status", RequestHandlerIndexStatus.class);
        availableHandlers.put("termfreq", RequestHandlerTermFreq.class);
        availableHandlers.put("", RequestHandlerIndexMetadata.class);
        availableHandlers.put("explain", RequestHandlerExplain.class);
        availableHandlers.put("autocomplete", RequestHandlerAutocomplete.class);
        availableHandlers.put("sharing", RequestHandlerSharing.class);
    }

    /**
     * Handle a request by dispatching it to the corresponding subclass.
     *
     * @param request the servlet, request and response objects
     * @param debugMode debug mode request? Allows extra parameters to be used
     * @param outputType output type requested (XML, JSON or CSV)
     * @return the response data
     */
    public static RequestHandler create(UserRequestServlet userRequest, boolean debugMode,
            DataFormat outputType, RequestInstrumentationProvider instrumentationProvider) {

        HttpServletRequest request = userRequest.getRequest();
        BlackLabServer servlet = userRequest.getServlet();

        // See if a user is logged in
        String requestId = instrumentationProvider.getRequestID(request).orElse("");
        ThreadContext.put("requestId", requestId);
        SearchManager searchManager = servlet.getSearchManager();
        User user = searchManager.getAuthSystem().determineCurrentUser(new UserRequestServlet(servlet, request, null));
        String debugHttpHeaderToken = searchManager.config().getAuthentication().getDebugHttpHeaderAuthToken();
        if (!user.isLoggedIn() && !StringUtils.isEmpty(debugHttpHeaderToken)) {
            String xBlackLabAccessToken = request.getHeader("X-BlackLabAccessToken");
            if (xBlackLabAccessToken != null && xBlackLabAccessToken.equals(debugHttpHeaderToken)) {
                user = User.loggedIn(request.getHeader("X-BlackLabUserId"), request.getSession().getId());
            }
        }

        // Parse the URL
        String servletPath = StringUtils.strip(StringUtils.trimToEmpty(request.getPathInfo()), "/");
        String[] parts = servletPath.split("/", 3);
        String indexName = parts.length >= 1 ? parts[0] : "";
        RequestHandlerStaticResponse errorObj = new RequestHandlerStaticResponse(servlet, request, user, indexName);
        if (indexName.startsWith(":")) {
            if (!user.isLoggedIn())
                return errorObj.unauthorized("Log in to access your private index.");
            // Private index. Prefix with user id.
            indexName = user.getUserId() + indexName;
        }
        String urlResource = parts.length >= 2 ? parts[1] : "";
        String urlPathInfo = parts.length >= 3 ? parts[2] : "";
        boolean resourceOrPathGiven = urlResource.length() > 0 || urlPathInfo.length() > 0;
        boolean pathGiven = urlPathInfo.length() > 0;

        // Debug feature: sleep for x ms before carrying out the request
        if (debugMode && !doDebugSleep(request)) {
            return errorObj.error("ROUGH_AWAKENING", "I was taking a nice nap, but something disturbed me", HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }

        // If we're reading a private index, we must own it or be on the share list.
        // If we're modifying a private index, it must be our own.
        Index privateIndex = null;
        //logger.debug("Got indexName = \"" + indexName + "\" (len=" + indexName.length() + ")");
        if (IndexUtil.isUserIndex(indexName)) {
            // It's a private index. Check if the logged-in user has access.
            if (!user.isLoggedIn())
                return errorObj.unauthorized("Log in to access a private index.");
            try {
                privateIndex = searchManager.getIndexManager().getIndex(indexName);
                if (!privateIndex.userMayRead(user))
                    return errorObj.unauthorized("You are not authorized to access this index.");
            } catch (IndexNotFound e) {
                // Ignore this here; this is either not an index name but some other request (e.g. cache-info)
                // or it is an index name but will trigger an error later.
            }
        }

        // Choose the RequestHandler subclass
        RequestHandler requestHandler = null;

        String method = request.getMethod();
        if (method.equals("DELETE")) {
            // Index given and nothing else?
            if (indexName.equals("input-formats")) {
                if (pathGiven)
                    return errorObj.methodNotAllowed("DELETE", null);
                requestHandler = new RequestHandlerDeleteFormat(servlet, request, user, indexName, urlResource,
                        urlPathInfo);
            } else {
                if (indexName.length() == 0 || resourceOrPathGiven) {
                    return errorObj.methodNotAllowed("DELETE", null);
                }
                if (privateIndex == null || !privateIndex.userMayDelete(user))
                    return errorObj.forbidden("You can only delete your own private indices.");
                requestHandler = new RequestHandlerDeleteIndex(servlet, request, user, indexName, null, null);
            }
        } else if (method.equals("PUT")) {
            return errorObj.methodNotAllowed("PUT", "Create new index with POST to /blacklab-server");
        } else {
            boolean postAsGet = false;
            if (method.equals("POST")) {
                if (indexName.length() == 0 && !resourceOrPathGiven) {
                    // POST to /blacklab-server/ : create private index
                    requestHandler = new RequestHandlerCreateIndex(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else if (indexName.equals("cache-clear")) {
                    // Clear the cache
                    if (resourceOrPathGiven) {
                        return errorObj.unknownOperation(indexName);
                    }
                    if (!debugMode) {
                        return errorObj
                                .unauthorized("You (" + ServletUtil.getOriginatingAddress(request) + ") are not authorized to do this.");
                    }
                    requestHandler = new RequestHandlerClearCache(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else if (indexName.equals("input-formats")) {
                    if (!user.isLoggedIn())
                        return errorObj.unauthorized("You must be logged in to add a format.");
                    requestHandler = new RequestHandlerAddFormat(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else if (ServletFileUpload.isMultipartContent(request)) {
                    // Add document to index
                    if (privateIndex == null || !privateIndex.userMayAddData(user))
                        return errorObj.forbidden("Can only POST to your own private indices.");
                    if (urlResource.equals("docs") && urlPathInfo.isEmpty()) {
                        if (!Index.isValidIndexName(indexName))
                            return errorObj.illegalIndexName(indexName);

                        // POST to /blacklab-server/indexName/docs/ : add data to index
                        requestHandler = new RequestHandlerAddToIndex(servlet, request, user, indexName, urlResource,
                                urlPathInfo);
                    } else if (urlResource.equals("sharing") && urlPathInfo.isEmpty()) {
                        if (!Index.isValidIndexName(indexName))
                            return errorObj.illegalIndexName(indexName);
                        // POST to /blacklab-server/indexName/sharing : set list of users to share with
                        requestHandler = new RequestHandlerSharing(servlet, request, user, indexName, urlResource,
                                urlPathInfo);
                    } else {
                        return errorObj.methodNotAllowed("POST", "You can only add new files at .../indexName/docs/");
                    }
                } else {
                    // Some other POST request; handle it as a GET.
                    // (this allows us to handle very large CQL queries that don't fit in a GET URL)
                    postAsGet = true;
                }
            }
            if (method.equals("GET") || (method.equals("POST") && postAsGet)) {
                if (indexName.equals("cache-info")) {
                    if (resourceOrPathGiven) {
                        return errorObj.unknownOperation(indexName);
                    }
                    if (!debugMode) {
                        return errorObj.unauthorized(
                                "You (" + ServletUtil.getOriginatingAddress(request) + ") are not authorized to see this information.");
                    }
                    requestHandler = new RequestHandlerCacheInfo(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else if (indexName.equals("input-formats")) {
                    requestHandler = new RequestHandlerListInputFormats(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else if (indexName.length() == 0) {
                    // No index or operation given; server info
                    requestHandler = new RequestHandlerServerInfo(servlet, request, user, indexName, urlResource,
                            urlPathInfo);
                } else {
                    // Choose based on urlResource
                    try {
                        String handlerName = urlResource;

                        IndexStatus status = searchManager.getIndexManager().getIndex(indexName).getStatus();
                        if (status != IndexStatus.AVAILABLE && handlerName.length() > 0 && !handlerName.equals("debug")
                                && !handlerName.equals("fields") && !handlerName.equals("status")
                                && !handlerName.equals("sharing")) {
                            return errorObj.unavailable(indexName, status.toString());
                        }

                        if (debugMode && !handlerName.isEmpty()
                                && !Arrays.asList("hits", "hits-csv", "hits-grouped-csv", "docs",
                                        "docs-csv", "docs-grouped-csv", "fields", "termfreq",
                                        "status", "autocomplete", "sharing").contains(handlerName)) {
                            handlerName = "debug";
                        }

                        // HACK to avoid having a different url resource for
                        // the lists of (hit|doc) groups: instantiate a different
                        // request handler class in this case.
                        else if (handlerName.equals("docs") && urlPathInfo.length() > 0) {
                            handlerName = "doc-info";
                            String p = urlPathInfo;
                            if (p.endsWith("/"))
                                p = p.substring(0, p.length() - 1);
                            if (urlPathInfo.endsWith("/contents")) {
                                handlerName = "doc-contents";
                            } else if (urlPathInfo.endsWith("/snippet")) {
                                handlerName = "doc-snippet";
                            } else if (!p.contains("/")) {
                                // OK, retrieving metadata
                            } else {
                                return errorObj.unknownOperation(urlPathInfo);
                            }
                        } else if (handlerName.equals("hits") || handlerName.equals("docs")) {
                            if (!StringUtils.isBlank(request.getParameter("group"))) {
                                String viewgroup = request.getParameter("viewgroup");
                                if (StringUtils.isEmpty(viewgroup))
                                    handlerName += "-grouped"; // list of groups instead of contents
                            } else if (!StringUtils.isEmpty(request.getParameter("viewgroup"))) {
                                // "viewgroup" parameter without "group" parameter; error.

                                return errorObj.badRequest("ERROR_IN_GROUP_VALUE",
                                        "Parameter 'viewgroup' specified, but required 'group' parameter is missing.");
                            }
                            if (outputType == DataFormat.CSV)
                                handlerName += "-csv";
                        }

                        if (!availableHandlers.containsKey(handlerName))
                            return errorObj.unknownOperation(handlerName);

                        Class<? extends RequestHandler> handlerClass = availableHandlers.get(handlerName);
                        Constructor<? extends RequestHandler> ctor = handlerClass.getConstructor(BlackLabServer.class,
                                HttpServletRequest.class, User.class, String.class, String.class, String.class);
                        //servlet.getSearchManager().getSearcher(indexName); // make sure it's open
                        requestHandler = ctor.newInstance(servlet, request, user, indexName, urlResource, urlPathInfo);
                    } catch (BlsException e) {
                        return errorObj.error(e.getBlsErrorCode(), e.getMessage(), e.getHttpStatusCode());
                    } catch (ReflectiveOperationException e) {
                        // (can only happen if the required constructor is not available in the RequestHandler subclass)
                        logger.error("Could not get constructor to create request handler", e);
                        return errorObj.internalError(e, debugMode, "INTERR_CREATING_REQHANDLER1");
                    } catch (IllegalArgumentException e) {
                        logger.error("Could not create request handler", e);
                        return errorObj.internalError(e, debugMode, "INTERR_CREATING_REQHANDLER2");
                    }
                }
            }
            if (requestHandler == null) {
                return errorObj.internalError("RequestHandler.create called with wrong method: " + method, debugMode,
                        "INTERR_WRONG_HTTP_METHOD");
            }
        }
        if (debugMode)
            requestHandler.setDebug();

        requestHandler.setInstrumentationProvider(instrumentationProvider);
        return requestHandler;
    }

    private static boolean doDebugSleep(HttpServletRequest request) {
        String sleep = request.getParameter("sleep");
        if (sleep != null) {
            int sleepMs = Integer.parseInt(sleep);
            if (sleepMs > 0 && sleepMs <= 3600000) {
                try {
                    logger.debug("Debug sleep requested (" + sleepMs + "ms). Zzzzz...");
                    Thread.sleep(sleepMs);
                    logger.debug("Ahh, that was a nice snooze!");
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return true;
    }

    protected boolean debugMode;

    /** The servlet object */
    protected BlackLabServer servlet;

    /** The HTTP request object */
    protected HttpServletRequest request;

    /** Interprets parameters to create searches. */
    protected WebserviceParamsImpl params;

    /**
     * The BlackLab index we want to access, e.g. "opensonar" for
     * "/opensonar/doc/1/content"
     */
    protected String indexName;

    /**
     * The type of REST resource we're accessing, e.g. "doc" for
     * "/opensonar/doc/1/content"
     */
    protected String urlResource;

    /**
     * The part of the URL path after the resource name, e.g. "1/content" for
     * "/opensonar/doc/1/content"
     */
    protected String urlPathInfo;

    /** The search manager, which executes and caches our searches */
    protected SearchManager searchMan;

    /** User id (if logged in) and/or session id */
    protected User user;

    protected IndexManager indexMan;

    @SuppressWarnings("unused")
    private RequestInstrumentationProvider requestInstrumentation;

    RequestHandler(BlackLabServer servlet, HttpServletRequest request, User user, String indexName, String urlResource,
            String urlPathInfo) {
        this.servlet = servlet;
        this.request = request;
        searchMan = servlet.getSearchManager();
        indexMan = searchMan.getIndexManager();
        String pathAndQueryString = ServletUtil.getPathAndQueryString(request);

        if (!(this instanceof RequestHandlerStaticResponse) && !pathAndQueryString.startsWith("/cache-info")) { // annoying when monitoring
            logger.info(WebserviceUtil.shortenIpv6(ServletUtil.getOriginatingAddress(request)) + " " + user.uniqueIdShort() + " "
                    + request.getMethod() + " " + pathAndQueryString);
        }

        boolean isDocs = isDocsOperation();
        boolean isDebugMode = searchMan.isDebugMode(ServletUtil.getOriginatingAddress(request));
        PlainWebserviceParams blsParams = new BlackLabServerParams(indexName, request, searchMan, user);
        params = WebserviceParamsImpl.get(isDocs, isDebugMode, blsParams);
        this.indexName = indexName;
        this.urlResource = urlResource;
        this.urlPathInfo = urlPathInfo;
        this.user = user;

    }

    protected BlackLabIndex blIndex() throws BlsException {
        return indexMan.getIndex(indexName).blIndex();
    }

    public User getUser() {
        return user;
    }

    @SuppressWarnings("unused")
    public RequestInstrumentationProvider getInstrumentationProvider() {
        return instrumentationProvider;
    }

    public void setInstrumentationProvider(RequestInstrumentationProvider instrumentationProvider) {
        this.instrumentationProvider = instrumentationProvider;
    }

    public void cleanup() {
        // (no op)
    }

    /**
     * Returns the response data type, if we want to override it.
     *
     * Used for returning doc contents, which are always XML, never JSON.
     *
     * @return the response data type to use, or null for the user requested one
     */
    public DataFormat getOverrideType() {
        return null;
    }

    /**
     * May the client cache the response of this operation?
     *
     * @return false if the client should not cache the response
     */
    public boolean isCacheAllowed() {
        return request.getMethod().equals("GET");
    }

    public boolean omitBlackLabResponseRootElement() {
        return false;
    }

    protected boolean isDocsOperation() {
        return false;
    }

    private void setDebug() {
        this.debugMode = true;
    }

    public void debug(Logger logger, String msg) {
        logger.debug(user.uniqueIdShort() + " " + msg);
    }

    public void warn(Logger logger, String msg) {
        logger.warn(user.uniqueIdShort() + " " + msg);
    }

    public void info(Logger logger, String msg) {
        logger.info(user.uniqueIdShort() + " " + msg);
    }

    public void error(Logger logger, String msg) {
        logger.error(user.uniqueIdShort() + " " + msg);
    }

    /**
     * Child classes should override this to handle the request.
     *
     * @param ds output stream
     * @return the response object
     *
     * @throws BlsException if the query can't be executed
     * @throws InterruptedSearch if the thread was interrupted
     */
    public abstract int handle(DataStream ds) throws BlsException, InvalidQuery;

}
