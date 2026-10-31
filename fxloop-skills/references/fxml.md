# FXML essentials

## Wiring

- `fx:controller="com.acme.MainController"` on the root element. The controller needs a no-arg constructor (or a
  `controllerFactory` on the loader).
- `fx:id="nameField"` injects into a field of the **same name**, annotated `@FXML` (needed when the field isn't
  public). In Kotlin: `@FXML private lateinit var nameField: TextField`.
- Handlers: `onAction="#save"` calls `@FXML void save()` or `@FXML void save(ActionEvent e)` (any `Event` subtype for
  other handlers). In Kotlin: `@FXML fun save()`.
- `@FXML void initialize()` (Kotlin `@FXML fun initialize()`) runs after injection. Bindings and cell factories go
  there.
- `fx:include source="detail.fxml" fx:id="detail"` injects the nested root as `detail` and its controller as
  `detailController`.
- Resource bundles: `text="%ticket.save"` with `new FXMLLoader(url, ResourceBundle.getBundle("i18n.messages"))`.
- `fx_lint_fxml all=true` catches the usual mistakes before the app even starts: `missingFxIdField` (fx:id without a
  field, which **also lists harmless ones that are just never used from code**, so check whether the code references
  it), `typeMismatch`, `missingHandlerMethod`, `handlerSignatureInvalid`, `unresolvedImport`, `duplicateFxId`. A
  real `missingFxIdField` (the code uses a differently named field) shows up at runtime as a `NullPointerException` or
  Kotlin `UninitializedPropertyAccessException`.

## Layout in FXML

Static constraints are attributes: `HBox.hgrow="ALWAYS"`, `VBox.vgrow="ALWAYS"`, `GridPane.columnIndex="1"`,
`AnchorPane.rightAnchor="12"`, `<VBox.margin><Insets top="8"/></VBox.margin>`. `<padding><Insets .../></padding>` sets
the container's own padding. `styleClass="card primary"` adds style classes; avoid `style="..."` (inline style beats
every stylesheet).

## Live reload: the swap caveat

When an FXML file changes, `fx_reload` (FXML tier) re-runs `FXMLLoader` for the changed document (it must be in the load registry) and
replaces the old root in place. **The controller is re-created**, so view-local state resets: typed text, selection,
scroll position, and anything `initialize()` didn't rebuild from the model. Data that lives in the model or a
service survives if the new controller re-reads it. When `fx_get_app_status` doesn't list a document in the FXML
registry (e.g. loaded through an unusual path), the reload falls back to a restart with replay.

Prefer FXML edits for layout fixes: they reload live without a restart, unlike constructor or `initialize()` changes
in code.
