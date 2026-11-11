package org.lab59.fxloop.protocol

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
        /**
         * First frame: `{token, fcpVersion, maxFcpVersion, yield?}`. A new connection replaces the current one, except
         * that `yield: true` (the `fxloop:run` inspector helper) is refused with [Error.BUSY] while a non-yielding
         * client (an agent session) is connected, and is itself replaced by any non-yielding client.
         */
        const val HELLO = "hello"
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

        // Live dev loop (PRD 07)
        const val REMOUNT_VIEWS = "remountViews"
        const val SET_BAR_STATE = "setBarState"
        const val GET_BAR_STATE = "getBarState"
        const val GET_PICKED = "getPicked"
        const val SET_PICK_MODE = "setPickMode"

        /** Test-only: synthetic "human" input outside the agent-action bracket; only honoured with `-Dfxloop.test=true`. */
        const val TEST_HUMAN_INPUT = "testHumanInput"

        /** CSS inspector (PRD 08): full per-property cascade for the browser inspector. */
        const val CSS_CASCADE = "cssCascade"
        const val CSS_SUGGEST = "cssSuggest"

        /** How many nodes of the target's scene a selector matches (the inspector's *New rule* scope). */
        const val CSS_SELECTOR = "cssSelector"

        /**
         * Visual-only overlay: draws or clears a node highlight (agent-owned popup, invisible to tools). `hold` makes
         * the node the held selection instead, which outlasts `clear` and stays until the next one or `release`.
         */
        const val HIGHLIGHT = "highlight"

        /** Arms pick mode on a window: the next click is consumed and reported as a [Event.HUMAN_PICK] event (PRD 07 pick mode). */
        const val PICK_START = "pickStart"

        /**
         * Inspector "Try it live" (PRD 08 §5): `{css}` replaces the scratch stylesheet (the last scene stylesheet; parsed
         * before installing, rejected on errors), `cssScratchClear` removes it. Both return the scratch description.
         */
        const val CSS_SCRATCH_SET = "cssScratchSet"
        const val CSS_SCRATCH_CLEAR = "cssScratchClear"

        /**
         * Inspector *Apply to file*: maps every scratch rule onto the project stylesheet that holds its selector (or the
         * scene's first project stylesheet for a new selector) and returns `{files:[{file, absFile, sha256, text,
         * changes}]}` with the edited source text. Read-only: the server checks and writes the files.
         */
        const val CSS_SCRATCH_PLAN = "cssScratchPlan"

        /** Inspector v2 (PRD 09): holds pseudo-classes on one node until cleared (`states: []`). */
        const val FORCE_STATES = "forceStates"

        /** Dead, broken and misspelt rules of the author stylesheets in the open windows (`fx_lint_css`). */
        const val CSS_LINT = "cssLint"

        /** Window-level stylesheet list in effective order; with `sheet`, its rules and how many nodes each matches. */
        const val CSS_SHEETS = "cssSheets"

        /** Read-only plan for writing layout edits into FXML attributes (the server checks and writes the files). */
        const val FXML_EDIT_PLAN = "fxmlEditPlan"

        /** The `onXxx` event handler properties a node has set. */
        const val NODE_HANDLERS = "nodeHandlers"

        /** Test-only: blocks the FX thread; only honoured with `-Dfxloop.test=true`. */
        const val BLOCK_FX_THREAD = "blockFxThread"
    }

    /** Unsolicited agent → server event names. */
    object Event {
        const val UI_ERROR = "uiError"
        const val APP_LOG = "appLog"
        const val HUMAN_ACTION = "humanAction"
        const val HUMAN_PICK = "humanPick"
        const val HUMAN_RESTART_REQUEST = "humanRestartRequest"
        const val CONTROL_MODE_CHANGED = "controlModeChanged"

        /** The developer clicked *Open CSS inspector* on the dev bar: `{path?}` (node to preselect). */
        const val INSPECTOR_REQUESTED = "inspectorRequested"

        /** The scratch stylesheet changed inside the app (dev bar *Clear*): `{active, rules}`. */
        const val SCRATCH_CHANGED = "scratchChanged"

        /** The developer clicked *Take over* on the dev bar while the agent held the input lock: `{via}`. */
        const val INPUT_LOCK_RELEASED = "inputLockReleased"

        /**
         * A window opened, closed or changed title (`{windows:[…]}`, the full `status` window list): an open inspector
         * page refreshes its window picker, so a dialog that appeared after the page loaded is reachable too.
         */
        const val WINDOWS_CHANGED = "windowsChanged"
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
        const val PICK_STALE = "PICK_STALE"
        const val DISABLED = "DISABLED"
        const val INTERNAL = "INTERNAL"
        const val DISCONNECTED = "DISCONNECTED"

        /** A yielding hello while an agent session is connected. */
        const val BUSY = "BUSY"
    }
}
