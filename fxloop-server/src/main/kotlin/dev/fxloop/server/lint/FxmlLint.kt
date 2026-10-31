package dev.fxloop.server.lint

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.StringReader
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Static FXML linter: checks fx:id fields, handlers, imports and controller against compiled classes. */
object FxmlLint {
    data class Finding(
        val check: String,
        val file: String,
        val line: Int?,
        val detail: String,
        val suggestion: String,
    )

    data class Result(val findings: List<Finding>, val filesChecked: Int)

    private const val FX_NS = "http://javafx.com/fxml"

    /** Walks roots for *.fxml files (a root may itself be a file). */
    fun findFxml(roots: List<Path?>): List<Path> {
        val out = LinkedHashSet<Path>()
        for (root in roots) {
            if (root == null || !Files.exists(root)) continue
            if (root.isRegularFile()) {
                if (root.name.endsWith(".fxml")) out.add(root)
                continue
            }
            try {
                Files.walk(root).use { s ->
                    s.filter { it.isRegularFile() && it.name.endsWith(".fxml") }.sorted().forEach { out.add(it) }
                }
            } catch (_: IOException) {
                // unreadable root: skip
            }
        }
        return out.toList()
    }

    @Throws(IOException::class)
    fun lint(fxmlFiles: List<Path>, classpath: List<Path>, projectDir: Path?): Result {
        val urls = classpath.map { it.toUri().toURL() }.toTypedArray()
        val findings = ArrayList<Finding>()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { cl ->
            for (f in fxmlFiles) lintFile(f, cl, projectDir, findings)
        }
        return Result(findings, fxmlFiles.size)
    }

    // ---------------------------------------------------------------- per file

    private class Elem(val name: String, val line: Int, val cls: Class<*>?, val fxId: String?) {
        val handlers = LinkedHashMap<String, String>()
    }

    private fun isFxNs(u: String?) = u != null && (u == FX_NS || u.startsWith("$FX_NS/"))

    private fun lintFile(file: Path, cl: ClassLoader, projectDir: Path?, out: MutableList<Finding>) {
        val rel = relativize(file, projectDir)
        val text = Files.readString(file)
        val classImports = ArrayList<String>()
        val pkgImports = arrayListOf("java.lang")
        val elems = ArrayList<Elem>()
        var controller: String? = null
        var controllerLine = 0
        val importLines = HashMap<String, Int>()

        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            f.setFeature("http://xml.org/sax/features/external-general-entities", false)
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: ParserConfigurationException) {
            // best effort; parser still gets resolveEntity guard below
        } catch (_: SAXException) {
        }

        val h = object : DefaultHandler() {
            var loc: Locator? = null
            var first = true

            override fun setDocumentLocator(l: Locator?) {
                loc = l
            }

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun processingInstruction(target: String?, data: String?) {
                if ("import" != target || data == null) return
                val imp = data.trim()
                importLines[imp] = loc?.lineNumber ?: -1
                if (imp.endsWith(".*")) {
                    pkgImports.add(imp.substring(0, imp.length - 2))
                } else if (imp.isNotEmpty()) {
                    classImports.add(imp)
                }
            }

            override fun startElement(uri: String?, local: String, qName: String?, atts: Attributes) {
                val line = loc?.lineNumber ?: -1
                val fxNs = isFxNs(uri)
                if (first) {
                    first = false
                    val c = attrNs(atts, "controller")
                    if (c != null) {
                        controller = c
                        controllerLine = line
                    }
                }
                if (fxNs || local.isEmpty()) return
                val upper = local[0].isUpperCase()
                val dotted = local.indexOf('.') >= 0
                val candidate = (upper && !dotted) || (!upper && dotted)
                if (!candidate) return
                val e = Elem(local, line, resolve(local, classImports, pkgImports, cl), attrNs(atts, "id"))
                for (i in 0 until atts.length) {
                    val an = atts.getQName(i)
                    val av = atts.getValue(i)
                    if (an.startsWith("on") && av.startsWith("#") && av.length > 1) {
                        e.handlers[an] = av.substring(1).trim()
                    }
                }
                elems.add(e)
            }

            private fun attrNs(atts: Attributes, localName: String): String? {
                for (i in 0 until atts.length) {
                    if (localName == atts.getLocalName(i) && isFxNs(atts.getURI(i))) return atts.getValue(i)
                }
                return null
            }
        }

        try {
            f.newSAXParser().parse(InputSource(StringReader(text)), h)
        } catch (e: SAXParseException) {
            out.add(
                Finding(
                    "parseError", rel, if (e.lineNumber > 0) e.lineNumber else null, e.message ?: "null",
                    "fix the XML syntax error",
                ),
            )
            return
        } catch (e: Exception) {
            if (e is SAXException || e is ParserConfigurationException || e is IOException) {
                out.add(Finding("parseError", rel, null, e.message.toString(), "fix the XML"))
                return
            }
            throw e
        }

        // imports: explicit class imports must load
        for (ci in classImports) {
            if (!loads(ci, cl)) {
                out.add(
                    Finding(
                        "unresolvedImport", rel, importLines[ci], "import $ci cannot be loaded",
                        "check the package/class name or compile the project / add the dependency",
                    ),
                )
            }
        }
        // element types
        val seenUnresolved = HashSet<String>()
        for (e in elems) {
            if (e.cls == null && seenUnresolved.add("${e.name}@${e.line}")) {
                out.add(
                    Finding(
                        "unresolvedImport", rel, e.line, "element <${e.name}> cannot be resolved to a class",
                        "add '<?import fully.qualified.${e.name}?>' or check the classpath",
                    ),
                )
            }
        }

        // duplicate fx:id
        val ids = HashMap<String, Elem>()
        for (e in elems) {
            val id = e.fxId ?: continue
            val prev = ids.putIfAbsent(id, e)
            if (prev != null) {
                out.add(
                    Finding(
                        "duplicateFxId", rel, e.line, "fx:id '$id' already used at line ${prev.line}",
                        "give each element a unique fx:id",
                    ),
                )
            }
        }

        val ctlName = controller ?: return
        val ctl: Class<*> = try {
            Class.forName(ctlName, false, cl)
        } catch (_: ClassNotFoundException) {
            return controllerMissing(out, rel, controllerLine, ctlName)
        } catch (_: LinkageError) {
            return controllerMissing(out, rel, controllerLine, ctlName)
        }

        val fields = ArrayList<Field>()
        val methods = ArrayList<Method>()
        var c: Class<*>? = ctl
        while (c != null && c != Any::class.java) {
            try {
                fields.addAll(c.declaredFields)
                methods.addAll(c.declaredMethods)
            } catch (_: LinkageError) {
                // member types missing from classpath; skip this level
            }
            c = c.superclass
        }
        val eventCls: Class<*>? = try {
            Class.forName("javafx.event.Event", false, cl)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
        val ctlSimple = ctl.simpleName

        for (e in elems) {
            if (e.fxId != null && ids[e.fxId] === e) {
                val match = fields.firstOrNull { it.name == e.fxId }
                if (match == null) {
                    var sim: String? = null
                    for (fd in fields) {
                        val exposed = isFxml(fd.declaredAnnotations) || Modifier.isPublic(fd.modifiers)
                        if (!exposed || Modifier.isStatic(fd.modifiers)) continue
                        if (fd.name.equals(e.fxId, ignoreCase = true) || lev(fd.name, e.fxId) <= 3) {
                            sim = fd.name
                            break
                        }
                    }
                    val sug = if (sim != null) "rename fx:id to '$sim' or the field to '${e.fxId}'"
                    else "add '@FXML private ${e.name} ${e.fxId};' to $ctlSimple"
                    out.add(
                        Finding(
                            "missingFxIdField", rel, e.line,
                            "fx:id '${e.fxId}' has no matching field in ${ctl.name}", sug,
                        ),
                    )
                } else if (e.cls != null && !match.type.isAssignableFrom(e.cls)) {
                    out.add(
                        Finding(
                            "typeMismatch", rel, e.line,
                            "field ${match.type.simpleName} ${e.fxId} but FXML declares ${e.cls.simpleName}",
                            "change the field type to ${e.cls.simpleName} or the FXML element",
                        ),
                    )
                }
            }
            for ((attr, mname) in e.handlers) {
                val named = methods.filter { it.name == mname }
                if (named.isEmpty()) {
                    var sim: String? = null
                    for (m in methods) {
                        if (m.isSynthetic || m.name.contains("$")) continue
                        if (m.name.equals(mname, ignoreCase = true) || lev(m.name, mname) <= 3) {
                            sim = m.name
                            break
                        }
                    }
                    val sug = if (sim != null) "did you mean '#$sim'? or add '@FXML private void $mname(ActionEvent e)'"
                    else "add '@FXML private void $mname(ActionEvent e)' to $ctlSimple"
                    out.add(
                        Finding(
                            "missingHandlerMethod", rel, e.line,
                            "$attr=\"#$mname\" has no method in ${ctl.name}", sug,
                        ),
                    )
                } else {
                    val ok = named.any { m ->
                        val pt = m.parameterTypes
                        pt.isEmpty() || (pt.size == 1 && (eventCls == null || eventCls.isAssignableFrom(pt[0])))
                    }
                    if (!ok) {
                        out.add(
                            Finding(
                                "handlerSignatureInvalid", rel, e.line,
                                "handler '$mname' must take no arguments or a single Event parameter",
                                "change signature to '$mname()' or '$mname(ActionEvent e)'",
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun controllerMissing(out: MutableList<Finding>, rel: String, line: Int, ctl: String) {
        out.add(
            Finding(
                "unresolvedImport", rel, if (line > 0) line else null, "controller class $ctl cannot be loaded",
                "compile the project first / check package",
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun isFxml(anns: Array<Annotation>): Boolean = anns.any { it.annotationClass.java.name == "javafx.fxml.FXML" }

    private fun loadClass(name: String, cl: ClassLoader): Class<*>? = try {
        Class.forName(name, false, cl)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun loads(name: String, cl: ClassLoader): Boolean = loadClass(name, cl) != null

    private fun resolve(name: String, classImports: List<String>, pkgImports: List<String>, cl: ClassLoader): Class<*>? {
        if (name.indexOf('.') >= 0) return loadClass(name, cl)
        for (ci in classImports) {
            if (ci.endsWith(".$name") || ci == name) {
                loadClass(ci, cl)?.let { return it }
            }
        }
        for (pkg in pkgImports) {
            loadClass("$pkg.$name", cl)?.let { return it }
        }
        return null
    }

    private fun relativize(file: Path, projectDir: Path?): String {
        var p = file.toAbsolutePath().normalize()
        if (projectDir != null) {
            val base = projectDir.toAbsolutePath().normalize()
            if (p.startsWith(base)) p = base.relativize(p)
        }
        return p.toString().replace('\\', '/')
    }

    internal fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
