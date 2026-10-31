package dev.fxloop.protocol

/** FXLoop Control Protocol constants: versions, method names and error codes. */
object Fcp {
    /** Current protocol version spoken by this build. */
    const val VERSION = 2

    /** Oldest protocol version this build still accepts (N-1 negotiation, PRD 03 §12). */
    const val MIN_VERSION = 1

    /** Hard cap on a single NDJSON frame (8 MB). */
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    @JvmStatic
    fun isSupported(version: Int): Boolean = version in MIN_VERSION..VERSION

    /** Request method names. Mirror the MCP tool surface 1:1 where applicable. */
    object Method {
        const val HELLO = "hello"
        const val PING = "ping"
        const val STATUS = "status"
        const val GET_SCENE_GRAPH = "getSceneGraph"
        const val FIND_NODES = "findNodes"
        const val CLICK = "click"
        const val TYPE_KEYS = "typeKeys"
        const val SET_TEXT = "setText"
        const val GET_PROPERTY = "getProperty"
        const val SET_PROPERTY = "setProperty"
        const val SCREENSHOT = "screenshot"
        const val GET_UI_ERRORS = "getUiErrors"
        const val GET_APP_LOGS = "getAppLogs"
        const val SNAPSHOT_STATE = "snapshotState"
        const val PATH_VISIBLE = "pathVisible"
        const val DESCRIBE_AT = "describeAt"
        const val NODE_DETAILS = "nodeDetails"
        const val RELOAD_CSS = "reloadCss"
        const val RELOAD_FXML = "reloadFxml"
        const val REDEFINE_CLASSES = "redefineClasses"
        const val LINT_VISUAL = "lintVisual"
        const val EXPLAIN_PROPERTY = "explainProperty"
        const val EXPLAIN_STYLE = "explainStyle"
        const val EXPLAIN_LAYOUT = "explainLayout"
        const val EVAL_JS = "evalJs"
        const val CAPTURE_STATES = "captureStates"

        /** Test-only: blocks the FX thread; only honoured with `-Dfxloop.test=true`. */
        const val BLOCK_FX_THREAD = "blockFxThread"
    }

    /** Unsolicited agent → server event names. */
    object Event {
        const val UI_ERROR = "uiError"
        const val APP_LOG = "appLog"
    }

    /** Machine-readable error codes carried in [FcpError.code]. */
    object Error {
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val VERSION_MISMATCH = "VERSION_MISMATCH"
        const val BAD_REQUEST = "BAD_REQUEST"
        const val UNKNOWN_METHOD = "UNKNOWN_METHOD"
        const val FX_TIMEOUT = "FX_TIMEOUT"
        const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
        const val AMBIGUOUS_PATH = "AMBIGUOUS_PATH"
        const val WINDOW_NOT_FOUND = "WINDOW_NOT_FOUND"
        const val PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND"
        const val PROPERTY_BOUND = "PROPERTY_BOUND"
        const val COERCION_FAILED = "COERCION_FAILED"
        const val UNSUPPORTED_TARGET = "UNSUPPORTED_TARGET"
        const val REDEFINE_REJECTED = "REDEFINE_REJECTED"
        const val FXML_NOT_REGISTERED = "FXML_NOT_REGISTERED"
        const val DISABLED = "DISABLED"
        const val INTERNAL = "INTERNAL"
        const val DISCONNECTED = "DISCONNECTED"
    }
}
