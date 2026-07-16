package dev.rusty.app.renderer

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class SoapRequest(val service: UpnpService, val action: String, val args: Map<String, String>)

object SoapProtocol {
    const val ERR_INVALID_ACTION = 401
    const val ERR_INVALID_ARGS = 402
    const val ERR_TRANSITION_NOT_AVAILABLE = 701
    const val ERR_SEEKMODE_NOT_SUPPORTED = 710
    const val ERR_ILLEGAL_SEEK_TARGET = 711
    const val ERR_ILLEGAL_MIME = 714
    const val ERR_RESOURCE_NOT_FOUND = 716

    fun parse(soapActionHeader: String?, body: String): SoapRequest? {
        val header = soapActionHeader?.trim()?.trim('"') ?: return null
        val serviceType = header.substringBefore('#', "")
        val action = header.substringAfter('#', "")
        if (action.isBlank()) return null
        val service = UpnpService.values().firstOrNull { it.serviceType == serviceType } ?: return null
        // Portable XXE defense: reject any DOCTYPE outright. The Xerces
        // disallow-doctype-decl feature below is not supported by Android's parser.
        if (body.contains("<!DOCTYPE", ignoreCase = true)) return null
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Best-effort hardening where the parser supports it (desktop Xerces);
                // Android's DocumentBuilderFactory throws on this feature URI.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return null
        val actionEl = doc.getElementsByTagNameNS(serviceType, action).item(0) ?: return null
        val args = LinkedHashMap<String, String>()
        val children = actionEl.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == org.w3c.dom.Node.ELEMENT_NODE) args[n.localName ?: n.nodeName] = n.textContent
        }
        return SoapRequest(service, action, args)
    }

    fun response(service: UpnpService, action: String, outArgs: List<Pair<String, String>>): String {
        val argsXml = outArgs.joinToString("") { (k, v) -> "<$k>${UpnpXml.escape(v)}</$k>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"${service.serviceType}\">$argsXml</u:${action}Response>" +
            "</s:Body></s:Envelope>"
    }

    fun fault(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>" +
        "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
        "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
        "<errorCode>$code</errorCode><errorDescription>${UpnpXml.escape(description)}</errorDescription>" +
        "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
}
