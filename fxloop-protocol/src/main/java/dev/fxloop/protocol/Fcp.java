package dev.fxloop.protocol;

import java.util.Set;

/** FXLoop Control Protocol constants: versions, method names and error codes. */
public final class Fcp {
    private Fcp() {}

    /** Current protocol version spoken by this build. */
    public static final int VERSION = 2;
    /** Oldest protocol version this build still accepts (N-1 negotiation, PRD 03 §12). */
    public static final int MIN_VERSION = 1;
    /** Hard cap on a single NDJSON frame (8 MB). */
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    public static boolean isSupported(int version) {
        return version >= MIN_VERSION && version <= VERSION;
    }

    /** Request method names. Mirror the MCP tool surface 1:1 where applicable. */
    public static final class Method {
        private Method() {}
        public static final String HELLO = "hello";
        public static final String PING = "ping";
        public static final String STATUS = "status";
        public static final String GET_SCENE_GRAPH = "getSceneGraph";
        public static final String FIND_NODES = "findNodes";
        public static final String CLICK = "click";
        public static final String TYPE_KEYS = "typeKeys";
        public static final String SET_TEXT = "setText";
        public static final String GET_PROPERTY = "getProperty";
        public static final String SET_PROPERTY = "setProperty";
        public static final String SCREENSHOT = "screenshot";
        public static final String GET_UI_ERRORS = "getUiErrors";
        public static final String GET_APP_LOGS = "getAppLogs";
        public static final String SNAPSHOT_STATE = "snapshotState";
        public static final String PATH_VISIBLE = "pathVisible";
        public static final String DESCRIBE_AT = "describeAt";
        public static final String NODE_DETAILS = "nodeDetails";
        public static final String ANNOTATION_TARGETS = "annotationTargets";
        public static final String RELOAD_CSS = "reloadCss";
        public static final String RELOAD_FXML = "reloadFxml";
        public static final String REDEFINE_CLASSES = "redefineClasses";
        public static final String LINT_VISUAL = "lintVisual";
        public static final String EXPLAIN_PROPERTY = "explainProperty";
        public static final String EXPLAIN_STYLE = "explainStyle";
        public static final String EXPLAIN_LAYOUT = "explainLayout";
        public static final String EVAL_JS = "evalJs";
        public static final String CAPTURE_STATES = "captureStates";
        /** Test-only: blocks the FX thread; only honoured with {@code -Dfxloop.test=true}. */
        public static final String BLOCK_FX_THREAD = "blockFxThread";
    }

    /** Unsolicited agent → server event names. */
    public static final class Event {
        private Event() {}
        public static final String UI_ERROR = "uiError";
        public static final String APP_LOG = "appLog";
    }

    /** Machine-readable error codes carried in {@link FcpError#code()}. */
    public static final class Error {
        private Error() {}
        public static final String UNAUTHORIZED = "UNAUTHORIZED";
        public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
        public static final String BAD_REQUEST = "BAD_REQUEST";
        public static final String UNKNOWN_METHOD = "UNKNOWN_METHOD";
        public static final String FX_TIMEOUT = "FX_TIMEOUT";
        public static final String NODE_NOT_FOUND = "NODE_NOT_FOUND";
        public static final String AMBIGUOUS_PATH = "AMBIGUOUS_PATH";
        public static final String WINDOW_NOT_FOUND = "WINDOW_NOT_FOUND";
        public static final String PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND";
        public static final String PROPERTY_BOUND = "PROPERTY_BOUND";
        public static final String COERCION_FAILED = "COERCION_FAILED";
        public static final String UNSUPPORTED_TARGET = "UNSUPPORTED_TARGET";
        public static final String REDEFINE_REJECTED = "REDEFINE_REJECTED";
        public static final String FXML_NOT_REGISTERED = "FXML_NOT_REGISTERED";
        public static final String DISABLED = "DISABLED";
        public static final String INTERNAL = "INTERNAL";

        public static final Set<String> ALL = Set.of(UNAUTHORIZED, VERSION_MISMATCH, BAD_REQUEST, UNKNOWN_METHOD,
                FX_TIMEOUT, NODE_NOT_FOUND, AMBIGUOUS_PATH, WINDOW_NOT_FOUND, PROPERTY_NOT_FOUND, PROPERTY_BOUND,
                COERCION_FAILED, UNSUPPORTED_TARGET, REDEFINE_REJECTED, FXML_NOT_REGISTERED, DISABLED, INTERNAL);
    }
}
