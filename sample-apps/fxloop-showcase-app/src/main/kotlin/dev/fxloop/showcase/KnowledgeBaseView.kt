package dev.fxloop.showcase

import java.util.ResourceBundle
import javafx.scene.Parent
import javafx.scene.web.WebView

class KnowledgeBaseView(private val bundle: ResourceBundle) {

    val node: Parent = WebView().apply {
        id = "knowledgeWeb"
        engine.loadContent(
            KnowledgeBaseView::class.java.getResource("knowledge-base.html")!!.readText()
                .replace("{{title}}", bundle["nav.knowledge"]),
        )
    }
}
