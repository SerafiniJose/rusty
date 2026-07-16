package dev.rusty.app.renderer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Parsed subset of a DIDL-Lite item's metadata. All fields nullable — casters vary widely. */
data class DidlMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtUri: String?,
) {
    companion object {
        val EMPTY = DidlMetadata(null, null, null, null)
    }
}

/**
 * Parses the raw DIDL-Lite blob a control point sends in CurrentURIMetaData.
 *
 * SECURITY: the outer SOAP parser reconstructs this DIDL from escaped text into raw XML, so the
 * SOAP body's own guard does not cover this second parse. We parse namespace-aware with DOCTYPE
 * and all external entities disabled, and fail closed (return EMPTY) on any malformed input.
 */
object DidlParser {

    fun parse(didl: String?): DidlMetadata {
        if (didl.isNullOrBlank()) return DidlMetadata.EMPTY
        // Portable XXE defense: reject any DOCTYPE outright. The Xerces
        // disallow-doctype-decl feature below is not supported by Android's parser.
        if (didl.contains("<!DOCTYPE", ignoreCase = true)) return DidlMetadata.EMPTY
        return try {
            val doc = hardenedFactory().newDocumentBuilder()
                .parse(InputSource(StringReader(didl)))
            val item = firstItem(doc.documentElement) ?: return DidlMetadata.EMPTY
            DidlMetadata(
                title = firstText(item, "title"),
                artist = firstText(item, "artist") ?: firstText(item, "creator"),
                album = firstText(item, "album"),
                albumArtUri = firstText(item, "albumArtURI")?.let { httpUriOrNull(it) },
            )
        } catch (_: Exception) {
            DidlMetadata.EMPTY
        }
    }

    private fun hardenedFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // EVERY hardening call below is best-effort: Android's DocumentBuilderFactory throws
            // (UnsupportedOperationException / ParserConfigurationException) on the feature URIs AND
            // on setXIncludeAware. An unguarded call aborts hardenedFactory() before any parse, so
            // parse() would return EMPTY for all on-device input (title/artist/album never render).
            // The portable defenses are namespace-awareness + the <!DOCTYPE string guard in parse();
            // these are belt-and-suspenders where the parser supports them (desktop Xerces).
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setXIncludeAware(false) }
        }

    /**
     * The local name of a node, portable across Android's and desktop Xerces' DOM. We match on the
     * bare local name by walking the tree ourselves instead of `getElementsByTagNameNS`: Android's
     * namespace-aware DOM diverges from Xerces (namespace lookups that resolve on the host JVM return
     * nothing on-device), which silently broke title/artist/album for real casters. Falls back to the
     * prefix-stripped `nodeName` when `localName` is null (non-namespace-aware nodes). The DIDL field
     * names we read (title/artist/album/albumArtURI/creator) don't collide across the dc/upnp
     * namespaces, so bare-local-name matching is unambiguous here.
     */
    private fun localNameOf(n: Node): String = n.localName ?: n.nodeName.substringAfterLast(':')

    /** The first `<item>` element anywhere under the DIDL-Lite root. */
    private fun firstItem(root: Element?): Element? {
        root ?: return null
        val matches = mutableListOf<Element>()
        collectByLocalName(root, "item", matches)
        return matches.firstOrNull()
    }

    /** Depth-first collect of every descendant element whose local name equals [name], in document order. */
    private fun collectByLocalName(root: Element, name: String, out: MutableList<Element>) {
        val kids = root.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val el = n as Element
            if (localNameOf(el) == name) out.add(el)
            collectByLocalName(el, name, out)
        }
    }

    /** First non-blank text of the first descendant with local name [name], else null. */
    private fun firstText(item: Element, name: String): String? {
        val matches = mutableListOf<Element>()
        collectByLocalName(item, name, matches)
        for (el in matches) {
            val t = el.textContent?.trim()
            if (!t.isNullOrEmpty()) return t
        }
        return null
    }

    private fun httpUriOrNull(raw: String): String? {
        val lower = raw.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) raw else null
    }
}
